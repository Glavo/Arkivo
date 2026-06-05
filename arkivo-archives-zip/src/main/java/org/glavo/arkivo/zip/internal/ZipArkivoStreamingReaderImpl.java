// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.glavo.arkivo.ArkivoFileSystemEntryStream;
import org.glavo.arkivo.zip.ZipArkivoFileSystemProvider;
import org.glavo.arkivo.zip.ZipArkivoEntryAttributes;
import org.glavo.arkivo.zip.ZipArkivoStreamingReader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/// Implements the public forward-only ZIP streaming reader API.
@NotNullByDefault
public final class ZipArkivoStreamingReaderImpl extends ZipArkivoStreamingReader {
    /// The internal streaming ZIP file system used by the current parser implementation.
    private final StreamingZipArkivoReadFileSystemImpl fileSystem;

    /// The optional state lock.
    private final @Nullable ReentrantLock lock;

    /// The internal entry stream, or `null` until iteration starts.
    private @Nullable ArkivoFileSystemEntryStream entries;

    /// The current ZIP entry attributes, or `null` when no entry is active.
    private @Nullable ZipArkivoEntryAttributes currentAttributes;

    /// Whether this reader is open.
    private boolean open = true;

    /// Creates a ZIP streaming reader.
    public ZipArkivoStreamingReaderImpl(ReadableByteChannel source, ZipArkivoFileSystemConfig config) {
        Objects.requireNonNull(config, "config");
        this.fileSystem = new StreamingZipArkivoReadFileSystemImpl(
                ZipArkivoFileSystemProvider.instance(),
                Objects.requireNonNull(source, "source"),
                config
        );
        this.lock = ZipLocks.create(config.threadSafety());
    }

    /// Creates a ZIP streaming reader.
    public ZipArkivoStreamingReaderImpl(InputStream source, ZipArkivoFileSystemConfig config) {
        Objects.requireNonNull(config, "config");
        this.fileSystem = new StreamingZipArkivoReadFileSystemImpl(
                ZipArkivoFileSystemProvider.instance(),
                Objects.requireNonNull(source, "source"),
                config
        );
        this.lock = ZipLocks.create(config.threadSafety());
    }

    /// Advances to the next ZIP entry and returns whether an entry is available.
    @Override
    public boolean next() throws IOException {
        lock();
        try {
            if (openedEntryStream().next() == null) {
                currentAttributes = null;
                return false;
            }
            ZipArkivoEntryAttributes attributes = fileSystem.currentEntryAttributes();
            if (attributes == null) {
                throw new IOException("ZIP streaming reader did not expose the current entry");
            }
            currentAttributes = attributes;
            return true;
        } finally {
            unlock();
        }
    }

    /// Reads the current archive entry attributes as the requested attribute type.
    @Override
    public <A extends BasicFileAttributes> A readAttributes(Class<A> type) throws IOException {
        lock();
        try {
            Objects.requireNonNull(type, "type");
            ZipArkivoEntryAttributes attributes = currentAttributes;
            if (attributes == null) {
                throw new IllegalStateException("ZIP streaming reader is not positioned at an entry");
            }
            if (type == BasicFileAttributes.class || type == ZipArkivoEntryAttributes.class) {
                return type.cast(attributes);
            }
            if (type == PosixFileAttributes.class) {
                return type.cast(new StreamingPosixEntryAttributes(attributes));
            }
            throw new UnsupportedOperationException("Unsupported ZIP streaming attributes type: " + type.getName());
        } finally {
            unlock();
        }
    }

    /// Opens a readable channel for the current file entry.
    @Override
    public ReadableByteChannel openChannel() throws IOException {
        lock();
        try {
            return entryStream().openChannel();
        } finally {
            unlock();
        }
    }

    /// Closes this streaming reader.
    @Override
    public void close() throws IOException {
        lock();
        try {
            if (!open) {
                return;
            }
            open = false;
            currentAttributes = null;
            ArkivoFileSystemEntryStream entryStream = entries;
            try {
                if (entryStream != null) {
                    entryStream.close();
                }
            } finally {
                fileSystem.close();
            }
        } finally {
            unlock();
        }
    }

    /// Returns the opened entry stream.
    private ArkivoFileSystemEntryStream entryStream() throws IOException {
        if (!open) {
            throw new IOException("ZIP streaming reader is closed");
        }
        ArkivoFileSystemEntryStream entryStream = entries;
        if (entryStream == null) {
            throw new IOException("ZIP streaming reader has not advanced to an entry");
        }
        return entryStream;
    }

    /// Returns the internal entry stream, opening it if needed.
    private ArkivoFileSystemEntryStream openedEntryStream() throws IOException {
        if (!open) {
            throw new IOException("ZIP streaming reader is closed");
        }
        ArkivoFileSystemEntryStream entryStream = entries;
        if (entryStream == null) {
            entryStream = fileSystem.openEntryStream();
            entries = entryStream;
        }
        return entryStream;
    }

    /// Acquires the state lock when it is present.
    private void lock() {
        ZipLocks.lock(lock);
    }

    /// Releases the state lock when it is present.
    private void unlock() {
        ZipLocks.unlock(lock);
    }

    /// Exposes synthesized POSIX attributes for the current streaming ZIP entry.
    private static final class StreamingPosixEntryAttributes implements PosixFileAttributes {
        /// The ZIP attributes used as the basic attribute source.
        private final ZipArkivoEntryAttributes attributes;

        /// Creates synthesized POSIX attributes.
        private StreamingPosixEntryAttributes(ZipArkivoEntryAttributes attributes) {
            this.attributes = Objects.requireNonNull(attributes, "attributes");
        }

        /// Returns the last modification time.
        @Override
        public FileTime lastModifiedTime() {
            return attributes.lastModifiedTime();
        }

        /// Returns the last access time.
        @Override
        public FileTime lastAccessTime() {
            return attributes.lastAccessTime();
        }

        /// Returns the creation time.
        @Override
        public FileTime creationTime() {
            return attributes.creationTime();
        }

        /// Returns whether this entry is a regular file.
        @Override
        public boolean isRegularFile() {
            return attributes.isRegularFile();
        }

        /// Returns whether this entry is a directory.
        @Override
        public boolean isDirectory() {
            return attributes.isDirectory();
        }

        /// Returns whether this entry is a symbolic link.
        @Override
        public boolean isSymbolicLink() {
            return attributes.isSymbolicLink();
        }

        /// Returns whether this entry is another file type.
        @Override
        public boolean isOther() {
            return attributes.isOther();
        }

        /// Returns the uncompressed entry size.
        @Override
        public long size() {
            return attributes.size();
        }

        /// Returns the file key.
        @Override
        public @Nullable Object fileKey() {
            return attributes.fileKey();
        }

        /// Returns the synthesized owner.
        @Override
        public UserPrincipal owner() {
            return ZipPosixSupport.DEFAULT_OWNER;
        }

        /// Returns the synthesized group.
        @Override
        public GroupPrincipal group() {
            return ZipPosixSupport.DEFAULT_GROUP;
        }

        /// Returns synthesized POSIX permissions.
        @Override
        public @Unmodifiable Set<PosixFilePermission> permissions() {
            return ZipPosixSupport.defaultPermissions(attributes.isDirectory());
        }
    }
}
