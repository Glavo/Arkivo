// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.internal;

import org.glavo.arkivo.ArkivoEditStorage;
import org.glavo.arkivo.ArkivoStoredContent;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

/// Provides built-in archive editor storage strategies.
@NotNullByDefault
public final class ArkivoEditStorageSupport {
    /// The shared memory storage strategy.
    private static final ArkivoEditStorage MEMORY = new MemoryStorage();

    /// Creates built-in archive editor storage strategies.
    private ArkivoEditStorageSupport() {
    }

    /// Returns an edit storage that keeps staged content in memory.
    public static ArkivoEditStorage memory() {
        return MEMORY;
    }

    /// Returns an edit storage that keeps staged content in temporary files under the given directory.
    public static ArkivoEditStorage temporaryFiles(Path directory) {
        return new TemporaryFileStorage(directory);
    }

    /// Returns an edit storage that keeps small staged content in memory and larger content in temporary files.
    public static ArkivoEditStorage hybrid(long memoryThreshold, Path directory) {
        if (memoryThreshold < 0) {
            throw new IllegalArgumentException("memoryThreshold must not be negative");
        }
        return new HybridStorage(memoryThreshold, directory);
    }

    /// Returns whether the given open options request read access.
    private static boolean readable(Set<? extends OpenOption> options) {
        return options.isEmpty() || options.contains(StandardOpenOption.READ);
    }

    /// Returns whether the given open options request write access.
    private static boolean writable(Set<? extends OpenOption> options) {
        return options.contains(StandardOpenOption.WRITE)
                || options.contains(StandardOpenOption.CREATE)
                || options.contains(StandardOpenOption.CREATE_NEW)
                || options.contains(StandardOpenOption.TRUNCATE_EXISTING);
    }

    /// Implements memory-backed edit storage.
    @NotNullByDefault
    private static final class MemoryStorage implements ArkivoEditStorage {
        /// Creates memory-backed edit storage.
        private MemoryStorage() {
        }

        /// Creates in-memory content storage.
        @Override
        public ArkivoStoredContent createContent(String path, long expectedSize) {
            Objects.requireNonNull(path, "path");
            if (expectedSize < ArkivoEditStorage.UNKNOWN_SIZE) {
                throw new IllegalArgumentException("expectedSize must be UNKNOWN_SIZE or non-negative");
            }
            return new MemoryStoredContent(expectedSize);
        }

        /// Closes this storage.
        @Override
        public void close() {
        }
    }

    /// Implements temporary-file edit storage.
    @NotNullByDefault
    private static final class TemporaryFileStorage implements ArkivoEditStorage {
        /// The directory that receives temporary content files.
        private final Path directory;

        /// Creates temporary-file edit storage.
        private TemporaryFileStorage(Path directory) {
            this.directory = Objects.requireNonNull(directory, "directory");
        }

        /// Creates temporary-file content storage.
        @Override
        public ArkivoStoredContent createContent(String path, long expectedSize) throws IOException {
            Objects.requireNonNull(path, "path");
            if (expectedSize < ArkivoEditStorage.UNKNOWN_SIZE) {
                throw new IllegalArgumentException("expectedSize must be UNKNOWN_SIZE or non-negative");
            }
            Files.createDirectories(directory);
            return new TemporaryFileStoredContent(Files.createTempFile(directory, "arkivo-entry-", ".tmp"));
        }

        /// Closes this storage.
        @Override
        public void close() {
        }
    }

    /// Implements hybrid memory and temporary-file edit storage.
    @NotNullByDefault
    private static final class HybridStorage implements ArkivoEditStorage {
        /// The maximum known size stored in memory.
        private final long memoryThreshold;

        /// The fallback temporary-file storage.
        private final TemporaryFileStorage temporaryFiles;

        /// Creates hybrid edit storage.
        private HybridStorage(long memoryThreshold, Path directory) {
            this.memoryThreshold = memoryThreshold;
            this.temporaryFiles = new TemporaryFileStorage(directory);
        }

        /// Creates content storage based on the expected size.
        @Override
        public ArkivoStoredContent createContent(String path, long expectedSize) throws IOException {
            if (expectedSize >= 0 && expectedSize <= memoryThreshold) {
                return MEMORY.createContent(path, expectedSize);
            }
            return temporaryFiles.createContent(path, expectedSize);
        }

        /// Closes this storage.
        @Override
        public void close() {
        }
    }

    /// Implements memory-backed stored content.
    @NotNullByDefault
    private static final class MemoryStoredContent implements ArkivoStoredContent {
        /// The staged bytes.
        private byte[] data;

        /// The staged byte count.
        private int size;

        /// Whether this content object is open.
        private boolean open = true;

        /// Creates memory-backed stored content.
        private MemoryStoredContent(long expectedSize) {
            int initialCapacity = expectedSize >= 0 && expectedSize <= Integer.MAX_VALUE ? (int) expectedSize : 0;
            this.data = new byte[initialCapacity];
        }

        /// Opens a memory-backed channel.
        @Override
        public SeekableByteChannel openChannel(Set<? extends OpenOption> options) throws IOException {
            Objects.requireNonNull(options, "options");
            ensureOpen();
            if (options.contains(StandardOpenOption.TRUNCATE_EXISTING)) {
                size = 0;
            }
            return new MemoryChannel(this, readable(options), writable(options));
        }

        /// Returns the staged content size.
        @Override
        public long size() throws IOException {
            ensureOpen();
            return size;
        }

        /// Closes this content.
        @Override
        public void close() {
            open = false;
            data = new byte[0];
            size = 0;
        }

        /// Requires this content object to be open.
        private void ensureOpen() throws IOException {
            if (!open) {
                throw new IOException("Stored content is closed");
            }
        }

        /// Ensures that the backing array can store the given byte count.
        private void ensureCapacity(int capacity) {
            if (capacity > data.length) {
                int newCapacity = Math.max(capacity, Math.max(16, data.length * 2));
                data = Arrays.copyOf(data, newCapacity);
            }
        }
    }

    /// Implements a seekable byte channel over memory-backed stored content.
    @NotNullByDefault
    private static final class MemoryChannel implements SeekableByteChannel {
        /// The stored content that owns the bytes.
        private final MemoryStoredContent content;

        /// Whether reads are allowed.
        private final boolean readable;

        /// Whether writes are allowed.
        private final boolean writable;

        /// The current channel position.
        private int position;

        /// Whether this channel is open.
        private boolean open = true;

        /// Creates a memory channel.
        private MemoryChannel(MemoryStoredContent content, boolean readable, boolean writable) {
            this.content = Objects.requireNonNull(content, "content");
            this.readable = readable;
            this.writable = writable;
        }

        /// Reads bytes from the current position.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            Objects.requireNonNull(destination, "destination");
            ensureOpen();
            if (!readable) {
                throw new NonReadableChannelException();
            }
            int remaining = content.size - position;
            if (remaining <= 0) {
                return -1;
            }
            int count = Math.min(destination.remaining(), remaining);
            destination.put(content.data, position, count);
            position += count;
            return count;
        }

        /// Writes bytes at the current position.
        @Override
        public int write(ByteBuffer source) throws IOException {
            Objects.requireNonNull(source, "source");
            ensureOpen();
            if (!writable) {
                throw new NonWritableChannelException();
            }
            int count = source.remaining();
            int end = Math.addExact(position, count);
            content.ensureCapacity(end);
            source.get(content.data, position, count);
            position = end;
            content.size = Math.max(content.size, end);
            return count;
        }

        /// Returns the current channel position.
        @Override
        public long position() throws IOException {
            ensureOpen();
            return position;
        }

        /// Sets the current channel position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            ensureOpen();
            if (newPosition < 0 || newPosition > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("newPosition must fit in a non-negative int");
            }
            position = (int) newPosition;
            return this;
        }

        /// Returns the current content size.
        @Override
        public long size() throws IOException {
            ensureOpen();
            return content.size;
        }

        /// Truncates the content.
        @Override
        public SeekableByteChannel truncate(long newSize) throws IOException {
            ensureOpen();
            if (newSize < 0 || newSize > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("newSize must fit in a non-negative int");
            }
            content.size = Math.min(content.size, (int) newSize);
            position = Math.min(position, content.size);
            return this;
        }

        /// Returns whether this channel is open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this channel.
        @Override
        public void close() {
            open = false;
        }

        /// Requires this channel to be open.
        private void ensureOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }

    /// Implements temporary-file stored content.
    @NotNullByDefault
    private static final class TemporaryFileStoredContent implements ArkivoStoredContent {
        /// The temporary file path.
        private final Path path;

        /// Whether this content object is open.
        private boolean open = true;

        /// Whether the temporary file has been deleted.
        private boolean deleted;

        /// Creates temporary-file stored content.
        private TemporaryFileStoredContent(Path path) {
            this.path = Objects.requireNonNull(path, "path");
        }

        /// Opens a channel over the temporary file.
        @Override
        public SeekableByteChannel openChannel(Set<? extends OpenOption> options) throws IOException {
            Objects.requireNonNull(options, "options");
            ensureOpen();
            return Files.newByteChannel(path, options);
        }

        /// Returns the temporary file size.
        @Override
        public long size() throws IOException {
            ensureOpen();
            return Files.size(path);
        }

        /// Closes this content and deletes its temporary file.
        @Override
        public void close() throws IOException {
            if (deleted) {
                return;
            }
            open = false;
            Files.deleteIfExists(path);
            deleted = true;
        }

        /// Requires this content object to be open.
        private void ensureOpen() throws IOException {
            if (!open) {
                throw new IOException("Stored content is closed");
            }
        }
    }
}
