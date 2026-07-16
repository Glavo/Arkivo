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
        assertThrows(IllegalStateException.class, first::attributes);
        assertNull(reader.nextEntry());
        assertThrows(IllegalStateException.class, second::attributes);

        reader.close();
        assertTrue(reader.closed);
    }

    /// Verifies failed duplicate begins preserve the pending handle and commits invalidate it.
    @Test
    void writerHandlesTrackThePendingEntry() throws Exception {
        TestWriter writer = new TestWriter();

        ArkivoStreamingWriter.Entry first = writer.beginFile("first.bin");
        assertThrows(IllegalStateException.class, () -> writer.beginDirectory("nested"));
        first.commit();
        assertEquals(List.of("first.bin"), writer.committedPaths);
        assertThrows(IllegalStateException.class, first::commit);

        ArkivoStreamingWriter.Entry custom = writer.beginCustom("custom.bin");
        assertEquals("custom.bin", custom.path());
        custom.openChannel().close();
        assertEquals(List.of("first.bin", "custom.bin"), writer.committedPaths);

        writer.close();
        assertTrue(writer.closed);
        assertThrows(ClosedChannelException.class, () -> writer.beginFile("closed.bin"));
        assertThrows(ClosedChannelException.class, () -> writer.attributeView(FileAttributeView.class));
        assertThrows(ClosedChannelException.class, () -> custom.attributeView(FileAttributeView.class));
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
            return Channels.newChannel(new ByteArrayOutputStream());
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
