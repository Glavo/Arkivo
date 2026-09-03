// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the forward-only reader cursor and scoped writer entry state machines.
@NotNullByDefault
public final class ArkivoStreamingStateTest {
    /// Verifies advancing is independent from lazy metadata access and preserves returned snapshots.
    @Test
    void readerCursorSeparatesAdvancementFromMetadata() throws Exception {
        TestReader reader = new TestReader();

        assertThrows(IllegalStateException.class, reader::readAttributes);
        assertThrows(IllegalStateException.class, reader::openChannel);

        assertTrue(reader.next());
        assertEquals(0, reader.attributeReadCount);
        ArchiveEntryAttributes first = reader.readAttributes();
        assertEquals(1, reader.attributeReadCount);
        assertEquals("first.bin", first.path());
        assertTrue(first.isRegularFile());

        assertTrue(reader.next());
        assertEquals(1, reader.attributeReadCount);
        assertEquals("first.bin", first.path());
        assertEquals("second.bin", reader.readAttributes().path());
        assertEquals(2, reader.attributeReadCount);

        assertFalse(reader.next());
        assertThrows(IllegalStateException.class, reader::readAttributes);

        reader.close();
        assertTrue(reader.closed);
        assertThrows(ClosedChannelException.class, reader::next);
        assertThrows(ClosedChannelException.class, reader::readAttributes);
    }

    /// Verifies advancing and closing the reader close a body opened for the current cursor position.
    @Test
    void readerOwnsTheCurrentBodyLifecycle() throws Exception {
        TestReader reader = new TestReader();

        assertTrue(reader.next());
        ReadableByteChannel firstBody = reader.openChannel();
        TrackingReadableByteChannel firstDelegate = Objects.requireNonNull(reader.bodyChannel);
        assertTrue(firstBody.isOpen());
        assertThrows(IllegalStateException.class, reader::openChannel);

        assertTrue(reader.next());
        assertFalse(firstBody.isOpen());
        assertFalse(firstDelegate.isOpen());

        try (InputStream input = reader.openInputStream()) {
            assertEquals(2, input.read());
            TrackingReadableByteChannel secondDelegate = Objects.requireNonNull(reader.bodyChannel);
            reader.close();

            assertThrows(ClosedChannelException.class, input::read);
            assertFalse(secondDelegate.isOpen());
        }
        assertTrue(reader.closed);
    }

    /// Verifies a failed body close clears the cursor and is retried before the parser advances.
    @Test
    void readerAdvanceRetriesFailedBodyCloseBeforeMovingTheCursor() throws Exception {
        TestReader reader = new TestReader(true, false);

        assertTrue(reader.next());
        ReadableByteChannel firstBody = reader.openChannel();
        TrackingReadableByteChannel firstDelegate = Objects.requireNonNull(reader.bodyChannel);

        java.io.IOException failure = assertThrows(java.io.IOException.class, reader::next);
        assertEquals("body close failed", failure.getMessage());
        assertTrue(firstBody.isOpen());
        assertEquals(1, firstDelegate.closeAttempts());
        assertThrows(IllegalStateException.class, reader::readAttributes);
        assertThrows(IllegalStateException.class, reader::openChannel);

        assertTrue(reader.next());
        assertFalse(firstBody.isOpen());
        assertEquals(2, firstDelegate.closeAttempts());
        assertEquals("second.bin", reader.readAttributes().path());
        reader.close();
    }

    /// Verifies reader closure aggregates body and parser failures and retries both incomplete cleanups.
    @Test
    void readerCloseAggregatesAndRetriesCleanupFailures() throws Exception {
        TestReader reader = new TestReader(true, true);

        assertTrue(reader.next());
        ReadableByteChannel body = reader.openChannel();
        TrackingReadableByteChannel delegate = Objects.requireNonNull(reader.bodyChannel);

        java.io.IOException failure = assertThrows(java.io.IOException.class, reader::close);
        assertEquals("body close failed", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("reader close failed", failure.getSuppressed()[0].getMessage());
        assertTrue(body.isOpen());
        assertFalse(reader.closed);
        assertEquals(1, delegate.closeAttempts());
        assertEquals(1, reader.readerCloseAttempts);
        assertThrows(ClosedChannelException.class, reader::next);
        assertThrows(ClosedChannelException.class, reader::readAttributes);
        assertThrows(ClosedChannelException.class, reader::openChannel);

        reader.close();
        assertFalse(body.isOpen());
        assertTrue(reader.closed);
        assertEquals(2, delegate.closeAttempts());
        assertEquals(2, reader.readerCloseAttempts);
    }

    /// Verifies failed duplicate begins preserve the pending handle and commits invalidate it.
    @Test
    void writerHandlesTrackThePendingEntry() throws Exception {
        TestWriter writer = new TestWriter();

        ArkivoStreamingWriter.Entry first = writer.beginFile("first.bin");
        assertThrows(IllegalStateException.class, () -> writer.beginDirectory("nested"));
        first.close();
        assertEquals(List.of("first.bin"), writer.committedPaths);
        first.close();

        ArkivoStreamingWriter.Entry custom = writer.beginCustom("custom.bin");
        assertEquals("custom.bin", custom.path());
        WritableByteChannel body = custom.openChannel();
        assertThrows(IllegalStateException.class, () -> writer.beginFile("blocked.bin"));
        assertThrows(IllegalStateException.class, () -> custom.attributeView(FileAttributeView.class));
        body.close();
        assertEquals(List.of("first.bin", "custom.bin"), writer.committedPaths);
        assertThrows(ClosedChannelException.class, () -> custom.attributeView(FileAttributeView.class));

        ArkivoStreamingWriter.Entry directory = writer.beginDirectory("nested");
        directory.close();

        writer.close();
        assertTrue(writer.closed);
        assertThrows(ClosedChannelException.class, () -> writer.beginFile("closed.bin"));
        assertThrows(ClosedChannelException.class, () -> custom.attributeView(FileAttributeView.class));
    }

    /// Verifies closing a writer closes an active body before closing the format writer.
    @Test
    void writerCloseClosesTheActiveBody() throws Exception {
        TestWriter writer = new TestWriter();
        ArkivoStreamingWriter.Entry entry = writer.beginFile("body.bin");
        WritableByteChannel body = entry.openChannel();
        TrackingWritableByteChannel delegate = Objects.requireNonNull(writer.bodyChannel);

        assertTrue(body.isOpen());
        assertTrue(delegate.isOpen());
        writer.close();

        assertFalse(body.isOpen());
        assertFalse(delegate.isOpen());
        assertTrue(writer.closed);
        assertEquals(List.of("body.bin"), writer.committedPaths);
        body.close();
        entry.close();
    }

    /// Verifies closing a writer commits a pending entry that has no opened body.
    @Test
    void writerCloseCommitsAPendingEmptyEntry() throws Exception {
        TestWriter writer = new TestWriter();
        ArkivoStreamingWriter.Entry entry = writer.beginDirectory("empty");

        writer.close();

        assertTrue(writer.closed);
        assertEquals(List.of("empty"), writer.committedPaths);
        entry.close();
    }

    /// Verifies metadata-only entry completion remains pending and retryable after its first failure.
    @Test
    void writerEntryCloseRetriesIncompleteMetadataCommit() throws Exception {
        TestWriter writer = new TestWriter(true, false, false);
        ArkivoStreamingWriter.Entry entry = writer.beginDirectory("retry");

        java.io.IOException failure = assertThrows(java.io.IOException.class, entry::close);
        assertEquals("entry finish failed", failure.getMessage());
        assertEquals(1, writer.entryFinishAttempts);
        assertEquals(List.of(), writer.committedPaths);
        assertThrows(ClosedChannelException.class, () -> entry.attributeView(FileAttributeView.class));
        assertThrows(ClosedChannelException.class, entry::openChannel);
        assertThrows(IllegalStateException.class, () -> writer.beginFile("blocked.bin"));

        entry.close();
        assertEquals(2, writer.entryFinishAttempts);
        assertEquals(List.of("retry"), writer.committedPaths);
        try (ArkivoStreamingWriter.Entry next = writer.beginDirectory("next")) {
            assertEquals("next", next.path());
        }
        writer.close();
    }

    /// Verifies an opened body retains the writer cursor until its delegate closes successfully.
    @Test
    void writerBodyCloseRetriesBeforeReleasingTheEntry() throws Exception {
        TestWriter writer = new TestWriter(false, true, false);
        ArkivoStreamingWriter.Entry entry = writer.beginFile("body.bin");
        WritableByteChannel body = entry.openChannel();
        TrackingWritableByteChannel delegate = Objects.requireNonNull(writer.bodyChannel);
        body.write(ByteBuffer.wrap(new byte[]{1, 2, 3}));

        java.io.IOException failure = assertThrows(java.io.IOException.class, body::close);
        assertEquals("body close failed", failure.getMessage());
        assertFalse(body.isOpen());
        assertTrue(delegate.isOpen());
        assertEquals(1, delegate.closeAttempts());
        assertThrows(ClosedChannelException.class, () -> body.write(ByteBuffer.allocate(1)));
        assertThrows(IllegalStateException.class, () -> writer.beginFile("blocked.bin"));

        entry.close();
        assertFalse(delegate.isOpen());
        assertEquals(2, delegate.closeAttempts());
        assertEquals(List.of("body.bin"), writer.committedPaths);
        try (ArkivoStreamingWriter.Entry next = writer.beginDirectory("next")) {
            assertEquals("next", next.path());
        }
        writer.close();
    }

    /// Verifies writer closure preserves entry failure priority, suppresses finalization failure, and invalidates handles.
    @Test
    void writerCloseAggregatesFailuresAndRetriesFormatCleanup() throws Exception {
        TestWriter writer = new TestWriter(true, false, true);
        ArkivoStreamingWriter.Entry entry = writer.beginDirectory("pending");

        java.io.IOException failure = assertThrows(java.io.IOException.class, writer::close);
        assertEquals("entry finish failed", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("writer close failed", failure.getSuppressed()[0].getMessage());
        assertEquals(1, writer.entryFinishAttempts);
        assertEquals(1, writer.writerCloseAttempts);
        assertFalse(writer.closed);
        assertThrows(ClosedChannelException.class, () -> writer.beginFile("closed.bin"));
        assertThrows(ClosedChannelException.class, () -> entry.attributeView(FileAttributeView.class));
        assertThrows(ClosedChannelException.class, entry::openChannel);

        entry.close();
        assertEquals(1, writer.entryFinishAttempts);
        writer.close();
        assertTrue(writer.closed);
        assertEquals(2, writer.writerCloseAttempts);
    }

    /// Verifies symbolic links, configurable attributes, and stream-backed file bodies use the scoped entry lifecycle.
    @Test
    void writerSupportsSymbolicLinksAttributesAndOutputStreams() throws Exception {
        TestWriter writer = new TestWriter();

        ArkivoStreamingWriter.Entry link = writer.beginSymbolicLink("link", "../target");
        assertEquals("link", link.path());
        assertEquals("../target", writer.lastSymbolicLinkTarget);
        assertNull(link.attributeView(FileAttributeView.class));
        assertThrows(NullPointerException.class, () -> link.attributeView(null));
        link.close();

        ArkivoStreamingWriter.Entry file = writer.beginFile("stream.bin");
        try (OutputStream output = file.openOutputStream()) {
            output.write(new byte[]{1, 2, 3});
            assertThrows(IllegalStateException.class, () -> writer.beginDirectory("blocked"));
        }
        assertEquals(List.of("link", "stream.bin"), writer.committedPaths);

        assertThrows(NullPointerException.class, () -> writer.beginSymbolicLink("invalid", null));
        writer.close();
    }

    /// Verifies reader and writer cleanup rethrow unchecked failures without wrapping and remain retryable.
    @Test
    void streamingCleanupPreservesUncheckedFailures() throws Exception {
        RuntimeException readerRuntime = new IllegalStateException("reader runtime failure");
        TestReader runtimeReader = new TestReader(readerRuntime);
        assertSame(readerRuntime, assertThrows(RuntimeException.class, runtimeReader::close));
        runtimeReader.close();
        assertTrue(runtimeReader.closed);

        Error readerError = new AssertionError("reader error failure");
        TestReader errorReader = new TestReader(readerError);
        assertSame(readerError, assertThrows(AssertionError.class, errorReader::close));
        errorReader.close();
        assertTrue(errorReader.closed);

        RuntimeException writerRuntime = new IllegalStateException("writer runtime failure");
        TestWriter runtimeWriter = new TestWriter(writerRuntime);
        assertSame(writerRuntime, assertThrows(RuntimeException.class, runtimeWriter::close));
        runtimeWriter.close();
        assertTrue(runtimeWriter.closed);

        Error writerError = new AssertionError("writer error failure");
        TestWriter errorWriter = new TestWriter(writerError);
        assertSame(writerError, assertThrows(AssertionError.class, errorWriter::close));
        errorWriter.close();
        assertTrue(errorWriter.closed);
    }

    /// Throws one injected cleanup failure without changing its type or identity.
    private static void throwInjectedFailure(Throwable failure) throws java.io.IOException {
        if (failure instanceof java.io.IOException exception) {
            throw exception;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        throw (Error) failure;
    }

    /// Supplies two deterministic reader entries.
    @NotNullByDefault
    private static final class TestReader extends ArkivoStreamingReader {
        /// Entry attributes in source order.
        private final @Unmodifiable List<TestAttributes> attributes = List.of(
                new TestAttributes("first.bin"),
                new TestAttributes("second.bin")
        );

        /// Current entry index.
        private int index = -1;

        /// Number of requested attribute materializations.
        private int attributeReadCount;

        /// Most recently opened format-specific body channel, or null before body access.
        private @Nullable TrackingReadableByteChannel bodyChannel;

        /// Whether this reader has closed.
        private boolean closed;

        /// Whether the first body close attempt must fail.
        private final boolean failFirstBodyClose;

        /// Failure thrown by the first format-reader close attempt, or null when cleanup succeeds.
        private final @Nullable Throwable firstReaderCloseFailure;

        /// Number of format-reader close attempts.
        private int readerCloseAttempts;

        /// Creates a reader whose cleanup operations succeed.
        private TestReader() {
            this(false, null);
        }

        /// Creates a reader with independently configurable first-close failures.
        private TestReader(boolean failFirstBodyClose, boolean failFirstReaderClose) {
            this(
                    failFirstBodyClose,
                    failFirstReaderClose ? new java.io.IOException("reader close failed") : null
            );
        }

        /// Creates a reader whose first format-reader close throws the supplied unchecked failure.
        private TestReader(Throwable firstReaderCloseFailure) {
            this(false, Objects.requireNonNull(firstReaderCloseFailure, "firstReaderCloseFailure"));
        }

        /// Creates a reader with independently configurable body and format-reader failures.
        private TestReader(
                boolean failFirstBodyClose,
                @Nullable Throwable firstReaderCloseFailure
        ) {
            this.failFirstBodyClose = failFirstBodyClose;
            this.firstReaderCloseFailure = firstReaderCloseFailure;
        }

        /// Advances to the next test entry.
        @Override
        protected boolean advance() {
            index++;
            return index < attributes.size();
        }

        /// Returns current test entry attributes.
        @Override
        protected <A extends BasicFileAttributes> A readCurrentAttributes(Class<A> type) {
            attributeReadCount++;
            TestAttributes current = attributes.get(index);
            if (!type.isInstance(current)) {
                throw new UnsupportedOperationException("Unsupported test attribute type: " + type.getName());
            }
            return type.cast(current);
        }

        /// Opens a one-byte body containing the current entry number.
        @Override
        protected ReadableByteChannel openCurrentChannel() {
            TrackingReadableByteChannel channel =
                    new TrackingReadableByteChannel(
                            new byte[]{(byte) (index + 1)},
                            failFirstBodyClose && index == 0
                    );
            bodyChannel = channel;
            return channel;
        }

        /// Marks this reader closed.
        @Override
        protected void closeReader() throws java.io.IOException {
            readerCloseAttempts++;
            if (firstReaderCloseFailure != null && readerCloseAttempts == 1) {
                throwInjectedFailure(firstReaderCloseFailure);
            }
            closed = true;
        }
    }

    /// Exposes whether a test body delegate has been closed by its owning reader.
    @NotNullByDefault
    private static final class TrackingReadableByteChannel implements ReadableByteChannel {
        /// In-memory readable delegate.
        private final ReadableByteChannel delegate;

        /// Whether the first close attempt must fail without closing the delegate.
        private final boolean failFirstClose;

        /// Number of close attempts.
        private int closeAttempts;

        /// Creates an open channel over the supplied bytes.
        private TrackingReadableByteChannel(byte[] bytes) {
            this(bytes, false);
        }

        /// Creates an open channel with optional first-close failure.
        private TrackingReadableByteChannel(byte[] bytes, boolean failFirstClose) {
            delegate = Channels.newChannel(new ByteArrayInputStream(Objects.requireNonNull(bytes, "bytes")));
            this.failFirstClose = failFirstClose;
        }

        /// Reads bytes from the in-memory delegate.
        @Override
        public int read(ByteBuffer target) throws java.io.IOException {
            return delegate.read(Objects.requireNonNull(target, "target"));
        }

        /// Returns whether the in-memory delegate remains open.
        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        /// Closes the in-memory delegate.
        @Override
        public void close() throws java.io.IOException {
            closeAttempts++;
            if (failFirstClose && closeAttempts == 1) {
                throw new java.io.IOException("body close failed");
            }
            delegate.close();
        }

        /// Returns the number of close attempts.
        private int closeAttempts() {
            return closeAttempts;
        }
    }

    /// Records entries committed by the writer state machine.
    @NotNullByDefault
    private static final class TestWriter extends ArkivoStreamingWriter {
        /// Committed logical paths.
        private final List<String> committedPaths = new ArrayList<>();

        /// The current logical path.
        private @Nullable String currentPath;

        /// Whether this writer has closed.
        private boolean closed;

        /// Most recently opened format-specific body channel, or null before opening one.
        private @Nullable TrackingWritableByteChannel bodyChannel;

        /// Most recently supplied symbolic-link target, or null before a symbolic link begins.
        private @Nullable String lastSymbolicLinkTarget;

        /// Whether the first metadata-only entry completion must fail.
        private final boolean failFirstEntryFinish;

        /// Whether the first body close attempt must fail.
        private final boolean failFirstBodyClose;

        /// Failure thrown by the first format-writer close attempt, or null when cleanup succeeds.
        private final @Nullable Throwable firstWriterCloseFailure;

        /// Number of metadata-only entry completion attempts.
        private int entryFinishAttempts;

        /// Number of format-writer close attempts.
        private int writerCloseAttempts;

        /// Creates a writer whose completion and cleanup operations succeed.
        private TestWriter() {
            this(false, false, null);
        }

        /// Creates a writer with independently configurable first-attempt failures.
        private TestWriter(
                boolean failFirstEntryFinish,
                boolean failFirstBodyClose,
                boolean failFirstWriterClose
        ) {
            this(
                    failFirstEntryFinish,
                    failFirstBodyClose,
                    failFirstWriterClose ? new java.io.IOException("writer close failed") : null
            );
        }

        /// Creates a writer whose first format-writer close throws the supplied unchecked failure.
        private TestWriter(Throwable firstWriterCloseFailure) {
            this(false, false, Objects.requireNonNull(firstWriterCloseFailure, "firstWriterCloseFailure"));
        }

        /// Creates a writer with independently configurable entry, body, and format-writer failures.
        private TestWriter(
                boolean failFirstEntryFinish,
                boolean failFirstBodyClose,
                @Nullable Throwable firstWriterCloseFailure
        ) {
            this.failFirstEntryFinish = failFirstEntryFinish;
            this.failFirstBodyClose = failFirstBodyClose;
            this.firstWriterCloseFailure = firstWriterCloseFailure;
        }

        /// Begins one custom test entry through the protected extension hook.
        private Entry beginCustom(String path) throws java.io.IOException {
            return beginCustomEntry(path, this::beginFileEntry);
        }

        /// Begins a regular test file.
        @Override
        protected void beginFileEntry(String path) {
            currentPath = path;
        }

        /// Begins a test directory.
        @Override
        protected void beginDirectoryEntry(String path) {
            currentPath = path;
        }

        /// Begins a test symbolic link.
        @Override
        protected void beginSymbolicLinkEntry(String path, String target) {
            currentPath = path;
            lastSymbolicLinkTarget = target;
        }

        /// Returns no configurable test attribute view.
        @Override
        protected <V extends FileAttributeView> @Nullable V currentAttributeView(Class<V> type) {
            return null;
        }

        /// Commits the current path without a body.
        @Override
        protected void finishCurrentEntry() throws java.io.IOException {
            entryFinishAttempts++;
            if (failFirstEntryFinish && entryFinishAttempts == 1) {
                throw new java.io.IOException("entry finish failed");
            }
            committedPaths.add(requireCurrentPath());
            currentPath = null;
        }

        /// Commits the current path and opens a discarded body channel.
        @Override
        protected WritableByteChannel openCurrentChannel() {
            committedPaths.add(requireCurrentPath());
            currentPath = null;
            TrackingWritableByteChannel channel = new TrackingWritableByteChannel(failFirstBodyClose);
            bodyChannel = channel;
            return channel;
        }

        /// Marks this writer closed.
        @Override
        protected void closeWriter() throws java.io.IOException {
            writerCloseAttempts++;
            if (firstWriterCloseFailure != null && writerCloseAttempts == 1) {
                throwInjectedFailure(firstWriterCloseFailure);
            }
            closed = true;
        }

        /// Returns the current path.
        private String requireCurrentPath() {
            String path = currentPath;
            if (path == null) {
                throw new IllegalStateException("No test entry is pending");
            }
            return path;
        }
    }

    /// Exposes whether a test body delegate has been closed by its owning writer.
    @NotNullByDefault
    private static final class TrackingWritableByteChannel implements WritableByteChannel {
        /// In-memory writable delegate.
        private final WritableByteChannel delegate = Channels.newChannel(new ByteArrayOutputStream());

        /// Whether the first close attempt must fail without closing the delegate.
        private final boolean failFirstClose;

        /// Number of close attempts.
        private int closeAttempts;

        /// Creates an open tracking body channel with optional first-close failure.
        private TrackingWritableByteChannel(boolean failFirstClose) {
            this.failFirstClose = failFirstClose;
        }

        /// Writes bytes to the in-memory delegate.
        @Override
        public int write(ByteBuffer source) throws java.io.IOException {
            return delegate.write(Objects.requireNonNull(source, "source"));
        }

        /// Returns whether the in-memory delegate remains open.
        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        /// Closes the in-memory delegate.
        @Override
        public void close() throws java.io.IOException {
            closeAttempts++;
            if (failFirstClose && closeAttempts == 1) {
                throw new java.io.IOException("body close failed");
            }
            delegate.close();
        }

        /// Returns the number of close attempts.
        private int closeAttempts() {
            return closeAttempts;
        }
    }

    /// Supplies immutable basic attributes for one test entry.
    ///
    /// @param path the logical archive path
    @NotNullByDefault
    private record TestAttributes(String path) implements ArchiveEntryAttributes {
        /// Returns the Unix epoch modification time.
        @Override
        public FileTime lastModifiedTime() {
            return FileTime.fromMillis(0L);
        }

        /// Returns the Unix epoch access time.
        @Override
        public FileTime lastAccessTime() {
            return FileTime.fromMillis(0L);
        }

        /// Returns the Unix epoch creation time.
        @Override
        public FileTime creationTime() {
            return FileTime.fromMillis(0L);
        }

        /// Returns whether this entry is a regular file.
        @Override
        public boolean isRegularFile() {
            return true;
        }

        /// Returns whether this entry is a directory.
        @Override
        public boolean isDirectory() {
            return false;
        }

        /// Returns whether this entry is a symbolic link.
        @Override
        public boolean isSymbolicLink() {
            return false;
        }

        /// Returns whether this entry has another file type.
        @Override
        public boolean isOther() {
            return false;
        }

        /// Returns the one-byte body size.
        @Override
        public long size() {
            return 1L;
        }

        /// Returns no stable file key.
        @Override
        public @Nullable Object fileKey() {
            return null;
        }
    }
}
