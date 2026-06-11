// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.glavo.arkivo.zip.ZipArkivoFileSystemProvider;
import org.glavo.arkivo.zip.ZipArkivoEntryAttributes;
import org.glavo.arkivo.zip.ZipArkivoStreamingReader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/// Implements the public forward-only ZIP streaming reader API.
@NotNullByDefault
public final class ZipArkivoStreamingReaderImpl extends ZipArkivoStreamingReader {
    /// The internal streaming ZIP file system used by the current parser implementation.
    private final StreamingZipArkivoReadFileSystemImpl fileSystem;

    /// The optional state lock.
    private final @Nullable ReentrantLock lock;

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
            ensureOpen();
            if (!fileSystem.nextStreamingEntry()) {
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
            ensureOpen();
            Objects.requireNonNull(type, "type");
            ZipArkivoEntryAttributes attributes = currentAttributes;
            if (attributes == null) {
                throw new IllegalStateException("ZIP streaming reader is not positioned at an entry");
            }
            if (type == BasicFileAttributes.class || type == ZipArkivoEntryAttributes.class) {
                return type.cast(attributes);
            }
            if (type == PosixFileAttributes.class) {
                return type.cast(attributes);
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
            ensureOpen();
            if (currentAttributes == null) {
                throw new IOException("ZIP streaming reader has not advanced to an entry");
            }
            return fileSystem.openCurrentEntryChannel();
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
            Throwable failure = null;
            try {
                fileSystem.closeCurrentStreamingEntry();
            } catch (IOException | RuntimeException | Error exception) {
                failure = exception;
            }
            try {
                fileSystem.close();
            } catch (IOException | RuntimeException | Error exception) {
                if (failure != null) {
                    failure.addSuppressed(exception);
                } else {
                    failure = exception;
                }
            }
            if (failure instanceof IOException exception) {
                throw exception;
            }
            if (failure instanceof RuntimeException exception) {
                throw exception;
            }
            if (failure instanceof Error exception) {
                throw exception;
            }
        } finally {
            unlock();
        }
    }

    /// Requires this streaming reader to be open.
    private void ensureOpen() throws IOException {
        if (!open) {
            throw new IOException("ZIP streaming reader is closed");
        }
    }

    /// Acquires the state lock when it is present.
    private void lock() {
        ZipLocks.lock(lock);
    }

    /// Releases the state lock when it is present.
    private void unlock() {
        ZipLocks.unlock(lock);
    }

}
