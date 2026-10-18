// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.internal.ReadOnlyByteArrayChannel;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies terminal state and cleanup retry behavior of ZIP entry streams and channels.
@NotNullByDefault
final class ZipEntryIoLifecycleTest {
    /// Strict closure cancels stored, compressed, and encrypted streams without another physical read.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void cancelsEntryStreamsWithoutDraining(int operation) throws Exception {
        for (ZipMethod method : new ZipMethod[]{ZipMethod.STORED, ZipMethod.DEFLATED, ZipMethod.BZIP2}) {
            for (ZipEncryption encryption : new ZipEncryption[]{ZipEncryption.NONE, ZipEncryption.WINZIP_AES_256}) {
                for (boolean failClose : new boolean[]{false, true}) {
                    var password = ArkivoPasswordProvider.fixed(new byte[]{'p'});
                    var output = new ByteArrayOutputStream();
                    var create = ZipArchiveOptions.CREATE_DEFAULTS.withPasswordProvider(password)
                            .withDefaultEncryption(encryption);
                    try (var writer = ZipArkivoStreamingWriter.open(output, create)) {
                        var entry = writer.beginFile("a");
                        Objects.requireNonNull(entry.attributeView(ZipArkivoEntryAttributeView.class)).setMethod(method);
                        try (var body = entry.openOutputStream()) {
                            body.write(new byte[]{1, 2, 3});
                        }
                    }
                    var physical = new AtomicReference<GatedArchiveChannel>();
                    ArkivoSeekableChannelSource source = () -> {
                        var channel = new GatedArchiveChannel(output.toByteArray());
                        physical.set(channel);
                        return channel;
                    };
                    var defaults = ZipArchiveOptions.READ_DEFAULTS.withPasswordProvider(password);
                    var options = defaults.withCommon(defaults.common().withThreadSafety(ArkivoFileSystemThreadSafety.STRICT));
                    try (var fs = ZipArkivoFileSystem.open(source, options)) {
                        var input = Files.newInputStream(fs.getPath("a"));
                        var gate = physical.get();
                        gate.armed = true;
                        gate.failClose = failClose;
                        var active = new FutureTask<>(() -> {
                            if (operation == 2) input.close();
                            else input.read();
                            return true;
                        });
                        if (operation != 0) startChannelTask(active);
                        try {
                            if (operation != 0) assertTrue(gate.entered.await(5, TimeUnit.SECONDS));
                            var cancel = new FutureTask<>(() -> {
                                if (failClose) assertThrows(IOException.class, fs::close);
                                fs.close();
                                return true;
                            });
                            startChannelTask(cancel);
                            assertTrue(cancel.get(5, TimeUnit.SECONDS));
                            if (operation != 0) {
                                assertTrue(assertThrows(ExecutionException.class,
                                        () -> active.get(5, TimeUnit.SECONDS)).getCause() instanceof IOException);
                            }
                            assertEquals(operation == 0 ? 0 : 1, gate.readCalls.get(), method + "/" + encryption);
                            input.close();
                            assertEquals(failClose ? 2 : 1, gate.closeCalls.get());
                            assertThrows(IOException.class, input::read);
                        } finally {
                            gate.release.countDown();
                            input.close();
                        }
                    }
                }
            }
        }
    }

    /// A completed entry still honors zero-length reads without changing EOF state.
    @Test
    void zeroLengthReadAfterEndOfEntry() throws IOException {
        byte[] archive = ZipTestArchiveFixtures.singleStoredZipArchive("a", new byte[]{1});
        try (var fs = ZipArkivoFileSystem.open(() -> new ReadOnlyByteArrayChannel(archive));
             var input = Files.newInputStream(fs.getPath("a"))) {
            assertArrayEquals(new byte[]{1}, input.readAllBytes());
            assertEquals(0, input.read(new byte[0], 0, 0));
            assertEquals(-1, input.read());
        }
    }

    /// Public stored-entry reads do not reuse a slice position before the preceding CRC update completes.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void serializesStoredChannelReadsAndSeeks(boolean seek) throws Exception {
        byte[] bytes = ZipTestArchiveFixtures.singleStoredZipArchive("a", new byte[]{1, 2, 3});
        var physical = new AtomicReference<GatedArchiveChannel>();
        ArkivoSeekableChannelSource source = () -> {
            var channel = new GatedArchiveChannel(bytes);
            physical.set(channel);
            return channel;
        };
        try (var fs = ZipArkivoFileSystem.open(source);
             var channel = Files.newByteChannel(fs.getPath("a"))) {
            var gate = physical.get();
            gate.armed = true;
            ByteBuffer first = ByteBuffer.allocate(1);
            ByteBuffer second = ByteBuffer.allocateDirect(1);
            var read = new FutureTask<>(() -> channel.read(first));
            startChannelTask(read);
            try {
                assertTrue(gate.entered.await(5, TimeUnit.SECONDS));
                var next = new FutureTask<>(() -> {
                    if (seek) channel.position(2);
                    return channel.read(second);
                });
                awaitChannelWait(startChannelTask(next));
                assertFalse(next.isDone());
                assertEquals(1, gate.readCalls.get());
                gate.release.countDown();
                assertEquals(1, read.get(5, TimeUnit.SECONDS));
                assertEquals(1, next.get(5, TimeUnit.SECONDS));
                assertEquals(1, first.get(0));
                assertEquals(seek ? 3 : 2, second.get(0));
                assertEquals(seek ? 3 : 2, channel.position());
            } finally {
                gate.release.countDown();
            }
        }
    }

    /// Cancelling a read or graceful close reaches the physical source without starting another validation read.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void cancelsStoredChannelWithoutDraining(int operation) throws Exception {
        for (boolean failClose : new boolean[]{false, true}) {
            byte[] bytes = ZipTestArchiveFixtures.singleStoredZipArchive("a", new byte[]{1, 2, 3});
            var physical = new AtomicReference<GatedArchiveChannel>();
            ArkivoSeekableChannelSource source = () -> {
                var channel = new GatedArchiveChannel(bytes);
                physical.set(channel);
                return channel;
            };
            var defaults = ZipArchiveOptions.READ_DEFAULTS;
            var options = defaults.withCommon(defaults.common().withThreadSafety(ArkivoFileSystemThreadSafety.STRICT));
            try (var fs = ZipArkivoFileSystem.open(source, options)) {
                var channel = Files.newByteChannel(fs.getPath("a"));
                var gate = physical.get();
                gate.armed = true;
                gate.failClose = failClose;
                var active = new FutureTask<>(() -> {
                    if (operation == 2) channel.close();
                    else channel.read(ByteBuffer.allocate(1));
                    return true;
                });
                startChannelTask(active);
                try {
                    assertTrue(gate.entered.await(5, TimeUnit.SECONDS));
                    var cancel = new FutureTask<>(() -> {
                        if (failClose) {
                            assertThrows(IOException.class, () -> {
                                if (operation == 0) channel.close();
                                else fs.close();
                            });
                        }
                        if (operation == 0) channel.close();
                        else fs.close();
                        return true;
                    });
                    startChannelTask(cancel);
                    assertTrue(cancel.get(5, TimeUnit.SECONDS));
                    assertTrue(assertThrows(ExecutionException.class,
                            () -> active.get(5, TimeUnit.SECONDS)).getCause() instanceof IOException);
                    assertEquals(1, gate.readCalls.get());
                    assertFalse(channel.isOpen());
                    channel.close();
                    assertEquals(failClose ? 2 : 1, gate.closeCalls.get());
                } finally {
                    gate.release.countDown();
                    channel.close();
                }
            }
        }
    }

    /// Starts a bounded test operation without leaving a non-daemon thread behind on a deadlock regression.
    private static Thread startChannelTask(FutureTask<?> task) {
        Thread thread = new Thread(task, "zip-entry-channel-test");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /// Waits for a worker to reach the channel's operation lock.
    private static void awaitChannelWait(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.isAlive() && System.nanoTime() < deadline) {
            if (thread.getState() == Thread.State.BLOCKED || thread.getState() == Thread.State.WAITING) return;
            Thread.sleep(1);
        }
        throw new AssertionError("ZIP channel operation did not wait");
    }

    /// Gives each archive opening its own cursor and pauses one armed data read after copying bytes.
    @NotNullByDefault
    private static final class GatedArchiveChannel implements SeekableByteChannel {
        /// The independently positioned archive snapshot.
        private final ReadOnlyByteArrayChannel delegate;
        /// Signals the blocked data read.
        private final CountDownLatch entered = new CountDownLatch(1);
        /// Releases the blocked read, either explicitly or on physical closure.
        private final CountDownLatch release = new CountDownLatch(1);
        /// Selects the first data read for blocking.
        private final AtomicBoolean gated = new AtomicBoolean();
        /// Counts reads after the entry channel has been opened.
        private final AtomicInteger readCalls = new AtomicInteger();
        /// Counts physical close attempts.
        private final AtomicInteger closeCalls = new AtomicInteger();
        /// Enables the gate after metadata parsing.
        private volatile boolean armed;
        /// Whether the next physical close should fail without closing the source.
        private boolean failClose;
        /// Whether physical closure completed.
        private volatile boolean closed;

        /// Creates an independent archive cursor.
        private GatedArchiveChannel(byte[] bytes) {
            delegate = new ReadOnlyByteArrayChannel(bytes);
        }

        @Override
        public int read(ByteBuffer target) throws IOException {
            int count = delegate.read(target);
            if (armed) {
                readCalls.incrementAndGet();
                if (gated.compareAndSet(false, true)) {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Timed out waiting for source close");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IOException(exception);
                    }
                    if (closed) throw new AsynchronousCloseException();
                }
            }
            return count;
        }

        @Override
        public int write(ByteBuffer source) { throw new NonWritableChannelException(); }

        @Override
        public long position() throws IOException { return delegate.position(); }

        @Override
        public SeekableByteChannel position(long value) throws IOException { delegate.position(value); return this; }

        @Override
        public long size() throws IOException { return delegate.size(); }

        @Override
        public SeekableByteChannel truncate(long size) { throw new NonWritableChannelException(); }

        @Override
        public boolean isOpen() { return !closed; }

        @Override
        public void close() throws IOException {
            closeCalls.incrementAndGet();
            if (failClose) {
                failClose = false;
                throw new IOException("Physical close failed");
            }
            closed = true;
            delegate.close();
            release.countDown();
        }
    }

    /// The directory containing generated archives.
    @TempDir
    Path temporaryDirectory;

    /// Verifies that writable entry channels report closed state before read capability checks.
    @Test
    void writeChannelOperationsAfterCloseAreRejectedAsClosed() throws IOException {
        Path archivePath = temporaryDirectory.resolve("write-channel-close.zip");

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.create(archivePath)) {
            SeekableByteChannel channel = Files.newByteChannel(
                    fileSystem.getPath("/channel.bin"),
                    Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            );

            assertThrows(NonReadableChannelException.class, () -> channel.read(ByteBuffer.allocate(1)));
            assertEquals(7, channel.write(ByteBuffer.wrap("channel".getBytes(StandardCharsets.UTF_8))));
            channel.close();

            assertFalse(channel.isOpen());
            assertThrows(ClosedChannelException.class, () -> channel.read(ByteBuffer.allocate(1)));
            assertThrows(ClosedChannelException.class, () -> channel.write(ByteBuffer.allocate(1)));
            assertThrows(ClosedChannelException.class, channel::position);
            assertThrows(ClosedChannelException.class, channel::size);
            assertThrows(ClosedChannelException.class, () -> channel.truncate(0));
            channel.close();
        }
    }

    /// Verifies that decoded read-only entry channels consistently reject operations after close.
    @Test
    void decodedChannelOperationsAfterCloseAreRejectedAsClosed() throws IOException {
        Path archivePath = ZipTestArchiveFixtures.writeDeflatedArchive(
                temporaryDirectory.resolve("decoded-channel-close.zip")
        );

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
            SeekableByteChannel channel = Files.newByteChannel(fileSystem.getPath("/dir/hello.txt"));

            ByteBuffer buffer = ByteBuffer.allocate(5);
            assertEquals(5, channel.read(buffer));
            assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), buffer.array());
            assertOpenReadOnly(channel);

            channel.close();

            assertClosed(channel);
            channel.close();
        }
    }

    /// Verifies that read-only entry channels and streams reject writable open options.
    @Test
    void readOnlyEntryRejectsWritableOpenOptions() throws IOException {
        Path archivePath = ZipTestArchiveFixtures.writeDeflatedArchive(
                temporaryDirectory.resolve("read-only-entry-options.zip")
        );

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
            Path file = fileSystem.getPath("/dir/hello.txt");

            try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
                ByteBuffer buffer = ByteBuffer.allocate(5);
                assertEquals(5, channel.read(buffer));
                assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), buffer.array());
            }
            try (var input = Files.newInputStream(file, StandardOpenOption.READ)) {
                assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), input.readAllBytes());
            }

            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.newByteChannel(file, StandardOpenOption.WRITE)
            );
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.newInputStream(file, StandardOpenOption.APPEND)
            );
        }
    }

    /// Verifies that decoded entry input streams reject reads after close and ignore repeated close calls.
    @Test
    void decodedInputStreamReadsAfterCloseAreRejected() throws IOException {
        Path archivePath = ZipTestArchiveFixtures.writeDeflatedArchive(
                temporaryDirectory.resolve("decoded-stream-close.zip")
        );

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
            var input = Files.newInputStream(fileSystem.getPath("/dir/hello.txt"));

            assertEquals('h', input.read());
            input.close();

            assertThrows(IOException.class, input::read);
            assertThrows(IOException.class, () -> input.read(new byte[1]));
            input.close();
        }
    }

    /// Verifies that a malformed decoded stream remains terminal when its first close call reports failure.
    @Test
    void malformedDeflatedInputCloseFailureIsTerminal() throws IOException {
        Path archivePath = ZipTestArchiveFixtures.writeDeflatedArchive(
                temporaryDirectory.resolve("malformed-deflated-close.zip")
        );
        corruptEntryBody(archivePath, "dir/hello.txt");

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
            var input = Files.newInputStream(fileSystem.getPath("/dir/hello.txt"));

            assertThrows(IOException.class, input::close);
            assertThrows(IOException.class, input::read);
            input.close();
        }
    }

    /// Verifies that stored-entry reads validate CRC-32 values against the actual body.
    @Test
    void storedEntryRejectsCrc32Mismatch() throws IOException {
        byte[] content = "stored data".getBytes(StandardCharsets.UTF_8);
        Path archivePath = writeStoredArchive("stored-crc.txt", content, true);

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
            IOException exception = assertThrows(
                    IOException.class,
                    () -> Files.readAllBytes(fileSystem.getPath("/stored-crc.txt"))
            );
            assertTrue(exception.getMessage().contains("ZIP entry data does not match central directory"));
        }
    }

    /// Verifies that closing a partially consumed stored-entry channel drains and validates unread data.
    @Test
    void storedChannelCloseValidatesUnreadData() throws IOException {
        byte[] content = "stored close validation".getBytes(StandardCharsets.UTF_8);
        Path archivePath = writeStoredArchive("stored-close-crc.txt", content, true);

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
            SeekableByteChannel channel = Files.newByteChannel(fileSystem.getPath("/stored-close-crc.txt"));
            ByteBuffer prefix = ByteBuffer.allocate(5);
            prefix.position(1);

            assertEquals(4, channel.read(prefix));
            assertEquals(4L, channel.position());
            assertEquals(channel, channel.position(channel.position()));

            IOException exception = assertThrows(IOException.class, channel::close);
            assertTrue(exception.getMessage().contains("ZIP entry data does not match central directory"));
            assertFalse(channel.isOpen());
            channel.close();
        }
    }

    /// Verifies that reordered reads do not apply sequential CRC validation to a valid stored entry.
    @Test
    void storedChannelSupportsRandomAccessWithoutFalseValidation() throws IOException {
        byte[] content = "stored random access".getBytes(StandardCharsets.UTF_8);
        Path archivePath = writeStoredArchive("stored-random.txt", content, false);

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
            SeekableByteChannel channel = Files.newByteChannel(fileSystem.getPath("/stored-random.txt"));

            assertEquals(content.length, channel.size());
            assertEquals(channel, channel.position(7L));
            ByteBuffer suffix = ByteBuffer.allocate(content.length - 7);
            assertEquals(content.length - 7, channel.read(suffix));
            assertArrayEquals(Arrays.copyOfRange(content, 7, content.length), suffix.array());

            assertEquals(channel, channel.position(0L));
            ByteBuffer complete = ByteBuffer.allocate(content.length);
            assertEquals(content.length, channel.read(complete));
            assertArrayEquals(content, complete.array());

            assertEquals(channel, channel.position(content.length + 1L));
            assertEquals(-1, channel.read(ByteBuffer.allocate(1)));
            assertThrows(IllegalArgumentException.class, () -> channel.position(-1L));
            assertOpenReadOnly(channel);
            channel.close();
            assertClosed(channel);
        }
    }

    /// Verifies that a stored-entry channel can retry backing volume cleanup after close failure.
    @Test
    void storedChannelRetriesVolumeCleanupAfterCloseFailure() throws IOException {
        Path archivePath = temporaryDirectory.resolve("stored-channel-close-failure.zip");
        byte[] content = "stored close failure".getBytes(StandardCharsets.UTF_8);

        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
            var storedEntry = writer.beginFile("stored.bin");
            ZipArkivoEntryAttributeView view = storedEntry.attributeView(ZipArkivoEntryAttributeView.class);
            assertNotNull(view);
            view.setMethod(ZipMethod.STORED);
            view.setUncompressedSizeAndCrc32(content.length, crc32(content));
            try (OutputStream output = storedEntry.openOutputStream()) {
                output.write(content);
            }
        }

        CloseFailingArchiveVolumeSource volumes =
                new CloseFailingArchiveVolumeSource(Files.readAllBytes(archivePath), 3);
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(volumes)) {
            SeekableByteChannel channel = Files.newByteChannel(fileSystem.getPath("/stored.bin"));

            IOException exception = assertThrows(IOException.class, channel::close);
            assertTrue(exception.getMessage().contains("close failed"));
            assertClosed(channel);
            channel.close();
            channel.close();
        }
    }

    /// Writes a stored-entry archive, optionally changing both recorded CRC fields to the same incorrect value.
    ///
    /// @param name the entry name
    /// @param content the entry body
    /// @param corruptCrc whether to corrupt the local and central CRC fields
    /// @return the generated archive path
    private Path writeStoredArchive(String name, byte[] content, boolean corruptCrc) throws IOException {
        byte[] archive = ZipTestArchiveFixtures.singleStoredZipArchive(name, content);
        if (corruptCrc) {
            corruptStoredCrc32(archive);
        }
        Path archivePath = temporaryDirectory.resolve(name + ".zip");
        Files.write(archivePath, archive);
        return archivePath;
    }

    /// Corrupts matching local-header and central-directory CRC-32 fields in place.
    ///
    /// @param archive the single-entry archive to modify
    private static void corruptStoredCrc32(byte[] archive) {
        ByteBuffer buffer = ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.remaining() < 30 || buffer.getInt(0) != 0x04034b50) {
            throw new AssertionError("Test ZIP local header not found");
        }
        buffer.putInt(14, buffer.getInt(14) ^ 1);

        for (int offset = 30; offset <= archive.length - 46; offset++) {
            if (buffer.getInt(offset) == 0x02014b50) {
                buffer.putInt(offset + 16, buffer.getInt(offset + 16) ^ 1);
                return;
            }
        }
        throw new AssertionError("Test ZIP central directory entry not found");
    }

    /// Replaces the first byte of a named entry's raw deflate body with a reserved block type.
    ///
    /// @param archivePath the archive file to modify
    /// @param entryName the local-header entry name to locate
    private static void corruptEntryBody(Path archivePath, String entryName) throws IOException {
        byte[] archive = Files.readAllBytes(archivePath);
        byte[] expectedName = entryName.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);

        for (int offset = 0; offset <= archive.length - 30; offset++) {
            if (buffer.getInt(offset) != 0x04034b50) {
                continue;
            }
            int nameLength = Short.toUnsignedInt(buffer.getShort(offset + 26));
            int extraLength = Short.toUnsignedInt(buffer.getShort(offset + 28));
            int dataOffset = offset + 30 + nameLength + extraLength;
            if (dataOffset >= archive.length || nameLength != expectedName.length) {
                continue;
            }
            if (Arrays.equals(archive, offset + 30, offset + 30 + nameLength, expectedName, 0, expectedName.length)) {
                archive[dataOffset] = 0x07;
                Files.write(archivePath, archive);
                return;
            }
        }
        throw new AssertionError("Test ZIP local header not found: " + entryName);
    }

    /// Asserts the capabilities of an open read-only channel.
    ///
    /// @param channel the channel to inspect
    private static void assertOpenReadOnly(SeekableByteChannel channel) {
        assertTrue(channel.isOpen());
        assertThrows(NonWritableChannelException.class, () -> channel.write(ByteBuffer.allocate(1)));
        assertThrows(NonWritableChannelException.class, () -> channel.truncate(0));
    }

    /// Asserts that every stateful operation observes a closed channel.
    ///
    /// @param channel the channel to inspect
    private static void assertClosed(SeekableByteChannel channel) {
        assertFalse(channel.isOpen());
        assertThrows(ClosedChannelException.class, () -> channel.read(ByteBuffer.allocate(1)));
        assertThrows(ClosedChannelException.class, () -> channel.write(ByteBuffer.allocate(1)));
        assertThrows(ClosedChannelException.class, channel::position);
        assertThrows(ClosedChannelException.class, channel::size);
        assertThrows(ClosedChannelException.class, () -> channel.truncate(0));
    }

    /// Returns the unsigned CRC-32 value of the given content.
    ///
    /// @param content the bytes to checksum
    /// @return the unsigned 32-bit checksum in a `long`
    private static long crc32(byte @Unmodifiable [] content) {
        CRC32 crc32 = new CRC32();
        crc32.update(content);
        return crc32.getValue();
    }
}
