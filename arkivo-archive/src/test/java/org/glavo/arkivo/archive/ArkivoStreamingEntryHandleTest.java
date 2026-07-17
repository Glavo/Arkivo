// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Objects;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies scoped entry handles preserve the forward-only reader and writer state machines.
@NotNullByDefault
public final class ArkivoStreamingEntryHandleTest {
    /// Verifies advancing and closing a reader invalidate older entry handles.
    @Test
    void readerHandlesTrackTheCurrentEntry() throws Exception {
        TestReader reader = new TestReader();

        ArkivoStreamingReader.Entry first = Objects.requireNonNull(reader.nextEntry());
        assertEquals("first.bin", first.path());
        assertTrue(first.attributes().isRegularFile());
        try (InputStream input = first.openInputStream()) {
            assertEquals(1, input.read());
        }

        ArkivoStreamingReader.Entry second = Objects.requireNonNull(reader.nextEntry());
        assertEquals("second.bin", second.path());
        assertThrows(ClosedChannelException.class, first::attributes);
        assertNull(reader.nextEntry());
        assertThrows(ClosedChannelException.class, second::attributes);

        reader.close();
        assertTrue(reader.closed);
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

        /// Whether this reader has closed.
        private boolean closed;

        /// Advances to the next test entry.
        @Override
        protected boolean advance() {
            index++;
            return index < attributes.size();
        }

        /// Returns current test entry attributes.
        @Override
        protected <A extends BasicFileAttributes> A readCurrentAttributes(Class<A> type) {
            TestAttributes current = attributes.get(index);
            if (!type.isInstance(current)) {
                throw new UnsupportedOperationException("Unsupported test attribute type: " + type.getName());
            }
            return type.cast(current);
        }

        /// Opens a one-byte body containing the current entry number.
        @Override
        protected ReadableByteChannel openCurrentChannel() {
            return Channels.newChannel(new ByteArrayInputStream(new byte[]{(byte) (index + 1)}));
        }

        /// Marks this reader closed.
        @Override
        protected void closeReader() {
            closed = true;
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
        }

        /// Returns no configurable test attribute view.
        @Override
        protected <V extends FileAttributeView> @Nullable V currentAttributeView(Class<V> type) {
            return null;
        }

        /// Commits the current path without a body.
        @Override
        protected void finishCurrentEntry() {
            committedPaths.add(requireCurrentPath());
            currentPath = null;
        }

        /// Commits the current path and opens a discarded body channel.
        @Override
        protected WritableByteChannel openCurrentChannel() {
            committedPaths.add(requireCurrentPath());
            currentPath = null;
            TrackingWritableByteChannel channel = new TrackingWritableByteChannel();
            bodyChannel = channel;
            return channel;
        }

        /// Marks this writer closed.
        @Override
        protected void closeWriter() {
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

        /// Creates an open tracking body channel.
        private TrackingWritableByteChannel() {
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
            delegate.close();
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
