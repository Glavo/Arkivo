// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.glavo.arkivo.zip.ZipArkivoFileSystemProvider;
import org.glavo.arkivo.zip.ZipArkivoStreamingWriter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/// Implements the public forward-only ZIP streaming writer API.
@NotNullByDefault
public final class ZipArkivoStreamingWriterImpl extends ZipArkivoStreamingWriter {
    /// The internal streaming ZIP file system used by the current writer implementation.
    private final StreamingZipArkivoFileSystemImpl fileSystem;

    /// The optional state lock.
    private final @Nullable ReentrantLock lock;

    /// Creates a ZIP streaming writer.
    private ZipArkivoStreamingWriterImpl(StreamingZipArkivoFileSystemImpl fileSystem, ZipArkivoFileSystemConfig config) {
        this.fileSystem = Objects.requireNonNull(fileSystem, "fileSystem");
        this.lock = ZipLocks.create(Objects.requireNonNull(config, "config").threadSafety());
    }

    /// Creates a streaming ZIP writer that writes to an archive path.
    public static ZipArkivoStreamingWriterImpl create(Path path, ZipArkivoFileSystemConfig config) throws IOException {
        return new ZipArkivoStreamingWriterImpl(new StreamingZipArkivoFileSystemImpl(
                ZipArkivoFileSystemProvider.instance(),
                path,
                config
        ), config);
    }

    /// Opens a streaming ZIP writer over a writable channel.
    public static ZipArkivoStreamingWriterImpl open(WritableByteChannel output, ZipArkivoFileSystemConfig config) {
        return open(Channels.newOutputStream(Objects.requireNonNull(output, "output")), config);
    }

    /// Opens a streaming ZIP writer over an output stream.
    public static ZipArkivoStreamingWriterImpl open(OutputStream output, ZipArkivoFileSystemConfig config) {
        return new ZipArkivoStreamingWriterImpl(new StreamingZipArkivoFileSystemImpl(
                ZipArkivoFileSystemProvider.instance(),
                Objects.requireNonNull(output, "output"),
                config
        ), config);
    }

    /// Creates a directory entry.
    @Override
    public void createDirectory(Path path) throws IOException {
        lock();
        try {
            fileSystem.createDirectory(fileSystem.getPath("/" + entryPathText(path)));
        } finally {
            unlock();
        }
    }

    /// Opens an output stream for the next regular file entry.
    @Override
    public OutputStream openOutputStream(Path path) throws IOException {
        lock();
        try {
            return fileSystem.newOutputStream(fileSystem.getPath("/" + entryPathText(path)));
        } finally {
            unlock();
        }
    }

    /// Closes this streaming writer and finishes the ZIP stream.
    @Override
    public void close() throws IOException {
        lock();
        try {
            fileSystem.close();
        } finally {
            unlock();
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

    /// Converts a logical entry path to ZIP path text.
    private static String entryPathText(Path path) {
        Objects.requireNonNull(path, "path");
        if (path.isAbsolute()) {
            throw new IllegalArgumentException("ZIP streaming entry paths must be relative");
        }

        Path normalizedPath = path.normalize();
        StringBuilder builder = new StringBuilder();
        for (Path name : normalizedPath) {
            String text = name.toString();
            if (text.equals(".") || text.isEmpty()) {
                continue;
            }
            if (text.equals("..")) {
                throw new IllegalArgumentException("ZIP streaming entry paths must not contain ..");
            }
            if (!builder.isEmpty()) {
                builder.append('/');
            }
            builder.append(text.replace('\\', '/'));
        }
        if (builder.isEmpty()) {
            throw new IllegalArgumentException("ZIP streaming entry path must not be empty");
        }
        return builder.toString();
    }
}
