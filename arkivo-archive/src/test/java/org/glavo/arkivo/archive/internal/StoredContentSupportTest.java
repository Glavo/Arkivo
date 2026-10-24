// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoCommitTarget;
import org.glavo.arkivo.archive.ArkivoStoredContent;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies shared indexed-content transfer and lifecycle behavior.
@NotNullByDefault
final class StoredContentSupportTest {
    /// Verifies known small content does not require a full-sized transfer array.
    @Test
    void sizesStoredInputTransferBuffer() throws IOException {
        byte[] content = new byte[4096];
        new java.util.Random(42).nextBytes(content);
        RecordingInputStream input = new RecordingInputStream(content);
        try (StoredContentPool pool = new StoredContentPool(ArkivoEditStorage.memory());
             ArkivoStoredContent stored = StoredContentSupport.storeInput(pool, "small", content.length, input);
             InputStream actual = StoredContentSupport.openInputStream(stored)) {
            assertArrayEquals(content, actual.readAllBytes());
            assertEquals(content.length, input.maximumBufferSize);
            assertEquals(1, input.eofReads);
            assertFalse(input.closed);
        }
    }

    /// Verifies exact, unknown, excessive, and underestimated hints preserve full transfer and EOF checks.
    @ParameterizedTest
    @CsvSource({"-1, 7, 65536", "0, 0, 1", "1, 1, 1", "4096, 4096, 4096", "4096, 7, 4096",
            "65535, 65535, 65535", "65536, 65536, 65536", "65537, 65537, 65536",
            "9223372036854775807, 7, 65536", "0, 65537, 65536", "1, 65537, 65536", "3, 5, 65536"})
    void copiesWithSizeHints(long expectedSize, int actualSize, int maximumBufferSize) throws IOException {
        byte[] content = new byte[actualSize];
        new java.util.Random(42).nextBytes(content);
        RecordingInputStream input = new RecordingInputStream(content);
        try (ShortWriteChannel output = new ShortWriteChannel()) {
            StoredContentSupport.copyInput(input, output, expectedSize);
            assertArrayEquals(content, output.bytes.toByteArray());
            assertEquals(maximumBufferSize, input.maximumBufferSize);
            assertEquals(1, input.eofReads);
            assertFalse(input.closed);
            assertTrue(output.isOpen());
        }
    }

    /// Verifies invalid hints are rejected before either endpoint is used or closed.
    @Test
    void rejectsInvalidSizeHint() throws IOException {
        RecordingInputStream input = new RecordingInputStream(new byte[]{1});
        try (ShortWriteChannel output = new ShortWriteChannel()) {
            assertThrows(IllegalArgumentException.class, () -> StoredContentSupport.copyInput(input, output, -2L));
            assertEquals(0, input.maximumBufferSize);
            assertEquals(1, input.available());
            assertEquals(0, output.bytes.size());
            assertFalse(input.closed);
            assertTrue(output.isOpen());
        }
    }

    /// Verifies reaching the size hint does not hide a subsequent read failure or discard completed writes.
    @ParameterizedTest
    @ValueSource(longs = {-1L, 0L, 1L, 3L, 4L})
    void preservesWrittenBytesWhenInputFailsAfterHint(long expectedSize) throws IOException {
        byte[] content = {1, 2, 3};
        try (InputStream input = new SequenceInputStream(new ByteArrayInputStream(content), new FailingInputStream());
             ShortWriteChannel output = new ShortWriteChannel()) {
            IOException failure = assertThrows(IOException.class,
                    () -> StoredContentSupport.copyInput(input, output, expectedSize));
            assertEquals("input failed", failure.getMessage());
            assertArrayEquals(content, output.bytes.toByteArray());
            assertTrue(output.isOpen());
        }
    }

    /// Verifies default storage can allocate empty content.
    @Test
    void selectsDefaultStorage() throws IOException {
        try (ArkivoEditStorage selected = StoredContentSupport.selectStorage(null);
             ArkivoStoredContent content = selected.createContent("selected", 0L)) {
            assertEquals(0L, content.size());
        }
    }

    /// Verifies configured storage selection, successful input storage, copying, and empty-body adapters.
    @Test
    void storesAndCopiesIndexedContent() throws IOException {
        ArkivoEditStorage storage = ArkivoEditStorage.memory();
        assertSame(storage, StoredContentSupport.selectStorage(
                () -> storage
        ));

        StoredContentPool pool = new StoredContentPool(storage);
        ArkivoStoredContent source = StoredContentSupport.storeInput(
                pool,
                "source",
                4L,
                new ByteArrayInputStream(new byte[]{1, 2, 3, 4})
        );
        try (InputStream input = StoredContentSupport.openInputStream(source)) {
            assertArrayEquals(new byte[]{1, 2, 3, 4}, input.readAllBytes());
        }

        ArkivoStoredContent destination = pool.createContent("destination", 0L);
        StoredContentSupport.copyContent(source, destination);
        try (SeekableByteChannel input = StoredContentSupport.openReadChannel(destination)) {
            ByteBuffer bytes = ByteBuffer.allocate(4);
            assertEquals(4, input.read(bytes));
            assertArrayEquals(new byte[]{1, 2, 3, 4}, bytes.array());
        }

        try (InputStream empty = StoredContentSupport.openInputStream(null)) {
            assertEquals(0, empty.readAllBytes().length);
        }
        try (SeekableByteChannel empty = StoredContentSupport.openReadChannel(null)) {
            assertEquals(0L, empty.size());
            assertEquals(-1, empty.read(ByteBuffer.allocate(1)));
        }

        StoredContentSupport.closeAfterOpenFailure(pool, new IOException("open failed"));
        assertThrows(IOException.class, source::size);
        assertThrows(IOException.class, destination::size);
    }

    /// Verifies memory-backed stored content follows NIO positioning, EOF, access-mode, and progress contracts.
    @Test
    void exposesContractCompliantMemoryChannels() throws IOException {
        try (ArkivoEditStorage storage = ArkivoEditStorage.memory();
             ArkivoStoredContent content = storage.createContent("memory", 0L)) {
            try (SeekableByteChannel writer = content.openChannel(Set.of(StandardOpenOption.WRITE))) {
                assertEquals(3, writer.write(ByteBuffer.wrap(new byte[]{1, 2, 3})));
                assertSame(writer, writer.position(Long.MAX_VALUE));
                assertEquals(0, writer.write(ByteBuffer.allocate(0)));

                ByteBuffer rejected = ByteBuffer.wrap(new byte[]{4});
                IOException exception = assertThrows(IOException.class, () -> writer.write(rejected));
                assertEquals("Memory stored content exceeds the maximum array size", exception.getMessage());
                assertEquals(0, rejected.position());
                assertEquals(Long.MAX_VALUE, writer.position());

                assertSame(writer, writer.truncate(Long.MAX_VALUE));
                assertEquals(Long.MAX_VALUE, writer.position());
                assertSame(writer, writer.truncate(100L));
                assertEquals(100L, writer.position());
                assertEquals(3L, writer.size());
                assertSame(writer, writer.truncate(2L));
                assertEquals(2L, writer.position());
                assertEquals(2L, writer.size());
            }

            SeekableByteChannel reader = content.openChannel(Set.of(StandardOpenOption.READ));
            assertSame(reader, reader.position(2L));
            assertEquals(0, reader.read(ByteBuffer.allocate(0)));
            assertEquals(-1, reader.read(ByteBuffer.allocate(1)));
            assertSame(reader, reader.position(Long.MAX_VALUE));
            assertEquals(Long.MAX_VALUE, reader.position());
            assertThrows(IllegalArgumentException.class, () -> reader.position(-1L));
            assertEquals(Long.MAX_VALUE, reader.position());

            ByteBuffer writeSource = ByteBuffer.wrap(new byte[]{9});
            assertThrows(NonWritableChannelException.class, () -> reader.write(writeSource));
            assertEquals(0, writeSource.position());
            assertThrows(NonWritableChannelException.class, () -> reader.truncate(0L));
            assertEquals(2L, content.size());

            reader.close();
            assertFalse(reader.isOpen());
            assertThrows(ClosedChannelException.class, reader::position);
            assertThrows(ClosedChannelException.class, () -> reader.read(ByteBuffer.allocate(1)));
        }
    }

    /// Verifies every built-in storage preserves already-open channels after its content handle is closed.
    @Test
    void preservesIndependentChannelLifecycles(@TempDir Path directory) {
        assertAll(
                () -> verifyIndependentChannelLifecycle(
                        ArkivoEditStorage.temporaryFiles(directory.resolve("temporary")),
                        ArkivoEditStorage.UNKNOWN_SIZE,
                        directory.resolve("temporary")
                ),
                () -> verifyIndependentChannelLifecycle(
                        ArkivoEditStorage.hybrid(4L, directory.resolve("hybrid-file")),
                        8L,
                        directory.resolve("hybrid-file")
                ),
                () -> verifyIndependentChannelLifecycle(
                        ArkivoEditStorage.memory(),
                        ArkivoEditStorage.UNKNOWN_SIZE,
                        directory.resolve("memory")
                ),
                () -> verifyIndependentChannelLifecycle(
                        ArkivoEditStorage.hybrid(8L, directory.resolve("hybrid-memory")),
                        8L,
                        directory.resolve("hybrid-memory")
                )
        );
    }

    /// Verifies one storage retains channel-owned state while releasing its closed content handle.
    private static void verifyIndependentChannelLifecycle(
            ArkivoEditStorage storage,
            long expectedSize,
            Path temporaryDirectory
    ) throws IOException {
        try (storage; ArkivoStoredContent content = storage.createContent("entry", expectedSize)) {
            SeekableByteChannel writer = content.openChannel(Set.of(
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE
            ));
            assertEquals(4, writer.write(ByteBuffer.wrap(new byte[]{1, 2, 3, 4})));
            SeekableByteChannel reader = content.openChannel(Set.of(StandardOpenOption.READ));

            content.close();
            content.close();
            assertThrows(IOException.class, content::size);
            assertThrows(
                    IOException.class,
                    () -> content.openChannel(Set.of(StandardOpenOption.READ))
            );

            writer.position(1L);
            assertEquals(2, writer.write(ByteBuffer.wrap(new byte[]{9, 8})));
            reader.position(0L);
            ByteBuffer actual = ByteBuffer.allocate(4);
            assertEquals(4, reader.read(actual));
            assertArrayEquals(new byte[]{1, 9, 8, 4}, actual.array());

            writer.close();
            reader.position(3L);
            ByteBuffer tail = ByteBuffer.allocate(1);
            assertEquals(1, reader.read(tail));
            assertEquals(4, Byte.toUnsignedInt(tail.array()[0]));
            reader.close();
        }

        if (Files.exists(temporaryDirectory)) {
            try (var children = Files.list(temporaryDirectory)) {
                assertEquals(0L, children.count());
            }
        }
    }

    /// Verifies failed stores retain content whose first cleanup fails and suppress all cleanup failures.
    @Test
    void retainsFailedCleanupForRetry() throws IOException {
        RetryCloseStorage storage = new RetryCloseStorage(true);
        StoredContentPool pool = new StoredContentPool(storage);

        IOException storeFailure = assertThrows(IOException.class, () -> StoredContentSupport.storeInput(
                pool,
                "failed",
                ArkivoEditStorage.UNKNOWN_SIZE,
                new FailingInputStream()
        ));
        assertEquals("input failed", storeFailure.getMessage());
        assertEquals(1, storeFailure.getSuppressed().length);
        assertEquals("content close failed", storeFailure.getSuppressed()[0].getMessage());
        assertFalse(pool.isClosed());

        IOException openFailure = new IOException("open failed");
        StoredContentSupport.closeAfterOpenFailure(pool, openFailure);
        assertEquals(1, openFailure.getSuppressed().length);
        assertEquals("storage close failed", openFailure.getSuppressed()[0].getMessage());
        assertEquals(2, storage.content.closeAttempts);
        assertTrue(storage.closed);
        assertFalse(storage.content.delegateOpen());
    }

    /// Verifies nonempty transfers reject writable channels that make no progress.
    @ParameterizedTest
    @ValueSource(longs = {-1L, 0L, 1L, 65536L})
    void rejectsZeroProgressOutput(long expectedSize) {
        ZeroProgressChannel output = new ZeroProgressChannel();
        IOException exception = assertThrows(IOException.class, () -> StoredContentSupport.copyInput(
                new ByteArrayInputStream(new byte[]{1}),
                output,
                expectedSize
        ));
        assertEquals("Stored content channel made no write progress", exception.getMessage());
        output.close();
        assertFalse(output.isOpen());
    }

    /// Verifies channel stalls cannot become successful EOF through a stream adapter.
    @ParameterizedTest
    @ValueSource(strings = {"copy", "commit", "single-byte", "bulk"})
    void rejectsStalledContentReads(String operation, @TempDir Path directory) throws IOException {
        StalledContentChannel channel = new StalledContentChannel();
        try (ArkivoStoredContent source = new ChannelContent(channel);
             ArkivoEditStorage storage = ArkivoEditStorage.memory();
             ArkivoStoredContent destination = storage.createContent("destination", 0L);
             var output = ArkivoCommitTarget.writeTo(directory.resolve("archive")).openOutput(null)) {
            IOException failure = assertThrows(IOException.class, () -> {
                switch (operation) {
                    case "copy" -> StoredContentSupport.copyContent(source, destination);
                    case "commit" -> StoredContentSupport.copyToCommitOutput(source, output);
                    default -> {
                        try (InputStream input = StoredContentSupport.openInputStream(source)) {
                            if (operation.equals("single-byte")) {
                                input.read();
                            } else {
                                input.readAllBytes();
                            }
                        }
                    }
                }
            });
            assertEquals("Readable channel made no progress", failure.getMessage());
            assertEquals(1, channel.readAttempts);
            assertEquals(0L, channel.position());
            assertFalse(channel.isOpen());
            assertEquals(0L, destination.size());
        }
    }

    /// Verifies a transient zero-length stream read falls back to one byte and physical EOF remains empty.
    @ParameterizedTest
    @ValueSource(longs = {-1L, 0L, 1L, 65536L})
    void handlesZeroProgressInputStreams(long expectedSize) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (WritableByteChannel channel = Channels.newChannel(output)) {
            StoredContentSupport.copyInput(new ZeroThenDataInputStream(new byte[]{1, 2, 3}), channel, expectedSize);
        }
        assertArrayEquals(new byte[]{1, 2, 3}, output.toByteArray());

        ByteArrayOutputStream emptyOutput = new ByteArrayOutputStream();
        try (WritableByteChannel channel = Channels.newChannel(emptyOutput)) {
            StoredContentSupport.copyInput(new ZeroThenDataInputStream(new byte[0]), channel, expectedSize);
        }
        assertEquals(0, emptyOutput.size());
    }

    /// Verifies absent source content still truncates an existing destination body.
    @Test
    void truncatesDestinationForAbsentContent() throws IOException {
        try (ArkivoEditStorage storage = ArkivoEditStorage.memory();
             ArkivoStoredContent destination = storage.createContent("destination", 3L)) {
            try (SeekableByteChannel output = destination.openChannel(Set.of(StandardOpenOption.WRITE))) {
                assertEquals(3, output.write(ByteBuffer.wrap(new byte[]{1, 2, 3})));
            }
            assertEquals(3L, destination.size());

            StoredContentSupport.copyContent(null, destination);
            assertEquals(0L, destination.size());
        }
    }

    /// Verifies failed content cleanup prevents premature storage closure during failed-open cleanup.
    @Test
    void defersStorageCloseAfterContentFailure() throws IOException {
        RetryCloseStorage storage = new RetryCloseStorage(false);
        StoredContentPool pool = new StoredContentPool(storage);
        pool.createContent("entry", 0L);
        IOException failure = new IOException("open failed");

        StoredContentSupport.closeAfterOpenFailure(pool, failure);

        assertEquals(1, failure.getSuppressed().length);
        assertEquals("content close failed", failure.getSuppressed()[0].getMessage());
        assertEquals(1, storage.content.closeAttempts);
        assertTrue(storage.content.delegateOpen());
        assertFalse(storage.closed);
        pool.close();
        assertTrue(pool.isClosed());
        assertFalse(storage.content.delegateOpen());
        assertTrue(storage.closed);
    }

    /// Verifies failed-open cleanup does not attempt to suppress the primary exception on itself.
    @Test
    void avoidsSelfSuppressionDuringCleanup() throws IOException {
        RetryCloseStorage storage = new RetryCloseStorage(true);
        StoredContentPool pool = new StoredContentPool(storage);
        ArkivoStoredContent content = pool.createContent("entry", 0L);
        assertThrows(IOException.class, content::close);
        IOException failure = java.util.Objects.requireNonNull(storage.closeFailure);

        StoredContentSupport.closeAfterOpenFailure(pool, failure);

        assertEquals(0, failure.getSuppressed().length);
        assertEquals(2, storage.content.closeAttempts);
        assertFalse(storage.content.delegateOpen());
    }

    /// Verifies shared storage helpers reject null mandatory arguments before allocating or transferring content.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void validatesArguments() throws IOException {
        ArkivoEditStorage storage = ArkivoEditStorage.memory();
        StoredContentPool pool = new StoredContentPool(storage);
        IOException failure = new IOException("failure");
        InputStream input = InputStream.nullInputStream();
        WritableByteChannel output = Channels.newChannel(new ByteArrayOutputStream());

        try (pool; input; output) {
            assertThrows(
                    NullPointerException.class,
                    () -> StoredContentSupport.closeAfterOpenFailure(null, failure)
            );
            assertThrows(
                    NullPointerException.class,
                    () -> StoredContentSupport.closeAfterOpenFailure(pool, null)
            );
            assertThrows(
                    NullPointerException.class,
                    () -> StoredContentSupport.storeInput(null, "entry", 0L, input)
            );
            assertThrows(
                    NullPointerException.class,
                    () -> StoredContentSupport.storeInput(pool, null, 0L, input)
            );
            assertThrows(
                    NullPointerException.class,
                    () -> StoredContentSupport.storeInput(pool, "entry", 0L, null)
            );
            assertThrows(NullPointerException.class, () -> StoredContentSupport.copyInput(null, output));
            assertThrows(NullPointerException.class, () -> StoredContentSupport.copyInput(input, null));
            assertThrows(NullPointerException.class, () -> StoredContentSupport.copyInput(null, output, 0L));
            assertThrows(NullPointerException.class, () -> StoredContentSupport.copyInput(input, null, 0L));
            assertThrows(NullPointerException.class, () -> StoredContentSupport.copyContent(null, null));
        }
    }

    /// Supplies one channel for a single stored-content transfer.
    ///
    /// @param channel the test-owned source channel
    @NotNullByDefault
    private record ChannelContent(SeekableByteChannel channel) implements ArkivoStoredContent {
        /// Returns the source channel for the tested read operation.
        @Override
        public SeekableByteChannel openChannel(Set<? extends OpenOption> options) {
            assertEquals(Set.of(StandardOpenOption.READ), options);
            return channel;
        }

        /// Returns the source channel's declared size.
        @Override
        public long size() throws IOException {
            return channel.size();
        }

        /// Releases the test-owned source channel.
        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    /// Models a nonempty source whose reads cannot make progress.
    @NotNullByDefault
    private static final class StalledContentChannel implements SeekableByteChannel {
        /// Whether the channel is still open.
        private boolean open = true;

        /// Number of attempted reads.
        private int readAttempts;

        /// Creates a stalled source at its beginning.
        private StalledContentChannel() {
        }

        /// Reports no progress without consuming bytes.
        @Override
        public int read(ByteBuffer target) {
            readAttempts++;
            return 0;
        }

        /// Rejects writes to the read-only source.
        @Override
        public int write(ByteBuffer source) {
            throw new NonWritableChannelException();
        }

        /// Returns the unchanged offset, including after closure for test assertions.
        @Override
        public long position() {
            return 0;
        }

        /// Rejects positioning, which these transfer paths do not require.
        @Override
        public SeekableByteChannel position(long position) {
            throw new UnsupportedOperationException();
        }

        /// Reports a nonempty body even though no bytes can be read.
        @Override
        public long size() {
            return 3;
        }

        /// Rejects truncation of the read-only source.
        @Override
        public SeekableByteChannel truncate(long size) {
            throw new NonWritableChannelException();
        }

        /// Returns whether transfer cleanup has closed the channel.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Records successful transfer cleanup.
        @Override
        public void close() {
            open = false;
        }
    }

    /// Captures output while consuming at most three bytes from each source buffer.
    @NotNullByDefault
    private static final class ShortWriteChannel implements WritableByteChannel {
        /// Bytes accepted by completed writes.
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        /// Whether cleanup has closed the channel.
        private boolean open = true;

        /// Creates an empty short-write destination.
        private ShortWriteChannel() {
        }

        /// Accepts a bounded prefix while advancing the supplied buffer.
        @Override
        public int write(ByteBuffer source) throws IOException {
            if (!open) throw new ClosedChannelException();
            int count = Math.min(3, source.remaining());
            for (int index = 0; index < count; index++) bytes.write(source.get());
            return count;
        }

        /// Returns whether the caller has closed this destination.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Records closure.
        @Override
        public void close() {
            open = false;
        }
    }

    /// Records transfer array sizes, EOF checks, and close propagation without changing input data.
    @NotNullByDefault
    private static final class RecordingInputStream extends ByteArrayInputStream {
        /// The largest array supplied to a bulk read, including the EOF check.
        private int maximumBufferSize;

        /// The number of bulk reads that observed physical EOF.
        private int eofReads;

        /// Whether the caller closed this input.
        private boolean closed;

        /// Creates a reader over the test data.
        private RecordingInputStream(byte[] content) {
            super(content);
        }

        /// Records the transfer array and reads through the actual input boundary.
        @Override
        public int read(byte[] target, int offset, int length) {
            maximumBufferSize = Math.max(maximumBufferSize, target.length);
            int count = super.read(target, offset, length);
            if (count < 0) eofReads++;
            return count;
        }

        /// Records closure without invalidating the inherited byte-array storage.
        @Override
        public void close() {
            closed = true;
        }
    }

    /// Returns zero once from bulk reads, then supplies fixed bytes through ordinary reads.
    @NotNullByDefault
    private static final class ZeroThenDataInputStream extends InputStream {
        /// Immutable bytes remaining to be returned.
        private final byte @Unmodifiable [] content;

        /// Current content offset.
        private int position;

        /// Whether the first bulk read still needs to report zero progress.
        private boolean zeroPending = true;

        /// Creates a stream over a defensive copy of the supplied bytes.
        private ZeroThenDataInputStream(byte[] content) {
            this.content = content.clone();
        }

        /// Returns one byte or physical end of input.
        @Override
        public int read() {
            return position < content.length ? Byte.toUnsignedInt(content[position++]) : -1;
        }

        /// Returns zero once and then supplies all remaining bytes.
        @Override
        public int read(byte[] bytes, int offset, int length) {
            if (zeroPending) {
                zeroPending = false;
                return 0;
            }
            if (position >= content.length) {
                return -1;
            }
            int count = Math.min(length, content.length - position);
            System.arraycopy(content, position, bytes, offset, count);
            position += count;
            return count;
        }
    }

    /// Produces a deterministic checked read failure.
    @NotNullByDefault
    private static final class FailingInputStream extends InputStream {
        /// Creates a failing input stream.
        private FailingInputStream() {
        }

        /// Reports the configured read failure.
        @Override
        public int read() throws IOException {
            throw new IOException("input failed");
        }
    }

    /// Implements a writable channel that never consumes a nonempty source buffer.
    @NotNullByDefault
    private static final class ZeroProgressChannel implements WritableByteChannel {
        /// Whether this channel remains open.
        private boolean open = true;

        /// Creates a zero-progress channel.
        private ZeroProgressChannel() {
        }

        /// Reports no progress without consuming source bytes.
        @Override
        public int write(ByteBuffer source) {
            return 0;
        }

        /// Returns whether this channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this channel.
        @Override
        public void close() {
            open = false;
        }
    }

    /// Provides one stored-content handle whose first close fails.
    @NotNullByDefault
    private static final class RetryCloseStorage implements ArkivoEditStorage {
        /// The content returned by this storage.
        private final RetryCloseContent content;

        /// The failure reported by storage close, or null when close succeeds.
        private final @Nullable IOException closeFailure;

        /// Whether this storage has been closed.
        private boolean closed;

        /// Creates retry-close storage.
        private RetryCloseStorage(boolean failClose) throws IOException {
            this.closeFailure = failClose ? new IOException("storage close failed") : null;
            this.content = new RetryCloseContent();
        }

        /// Returns the shared test content.
        @Override
        public ArkivoStoredContent createContent(String path, long expectedSize) {
            return content;
        }

        /// Closes this storage and optionally reports a failure.
        @Override
        public void close() throws IOException {
            closed = true;
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }

    /// Wraps memory content and fails its first close attempt.
    @NotNullByDefault
    private static final class RetryCloseContent implements ArkivoStoredContent {
        /// The wrapped memory content.
        private final ArkivoStoredContent delegate;

        /// The number of close attempts.
        private int closeAttempts;

        /// Creates retry-close content.
        @SuppressWarnings("resource")
        private RetryCloseContent() throws IOException {
            this.delegate = ArkivoEditStorage.memory().createContent("test", 0L);
        }

        /// Opens a channel over wrapped content.
        @Override
        public SeekableByteChannel openChannel(Set<? extends OpenOption> options) throws IOException {
            return delegate.openChannel(options);
        }

        /// Returns the wrapped content size.
        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        /// Fails the first close attempt and closes wrapped content thereafter.
        @Override
        public void close() throws IOException {
            closeAttempts++;
            if (closeAttempts == 1) {
                throw new IOException("content close failed");
            }
            delegate.close();
        }

        /// Returns whether wrapped content remains open.
        private boolean delegateOpen() {
            try {
                delegate.size();
                return true;
            } catch (IOException exception) {
                return false;
            }
        }
    }
}
