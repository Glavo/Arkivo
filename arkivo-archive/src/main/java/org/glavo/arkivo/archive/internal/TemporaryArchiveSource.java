// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/// Owns a temporary seekable copy of a transformed archive stream.
@NotNullByDefault
public final class TemporaryArchiveSource implements ArkivoSeekableChannelSource {
    /// The bounded transfer buffer size.
    private static final int TRANSFER_BUFFER_SIZE = 64 * 1024;

    /// The owned temporary archive path.
    private final Path path;

    /// Whether this source has deleted its temporary path.
    private boolean closed;

    /// Creates a source that owns the given existing temporary path.
    private TemporaryArchiveSource(Path path) {
        this.path = path;
    }

    /// Materializes an owned transformed stream into a temporary seekable source.
    ///
    /// This method always closes `source`. A returned source owns its temporary file until [#close()].
    ///
    /// @param source the transformed channel whose ownership transfers after argument validation
    /// @param maximumSize the non-negative decoded archive size limit, or a negative value to disable limiting
    /// @return a repeatable seekable source over the complete transformed bytes
    /// @throws IOException if transfer, endpoint cleanup, or temporary-file allocation fails
    public static TemporaryArchiveSource materialize(
            ReadableByteChannel source,
            long maximumSize
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Path path;
        try {
            path = Files.createTempFile("arkivo-archive-", ".tmp");
        } catch (IOException | RuntimeException | Error exception) {
            closeAfterFailure(source, exception);
            throw exception;
        }
        boolean completed = false;
        try {
            ReadableByteChannel limited = ArchiveSizeLimitChannel.wrap(source, maximumSize);
            try (limited;
                 SeekableByteChannel output = Files.newByteChannel(
                         path,
                         StandardOpenOption.WRITE,
                         StandardOpenOption.TRUNCATE_EXISTING
                 )) {
                ByteBuffer buffer = ByteBuffer.allocate(TRANSFER_BUFFER_SIZE);
                while (true) {
                    int read = limited.read(buffer);
                    if (read < 0) {
                        break;
                    }
                    if (read == 0) {
                        continue;
                    }
                    buffer.flip();
                    while (buffer.hasRemaining()) {
                        if (output.write(buffer) == 0) {
                            throw new IOException("Temporary archive channel made no write progress");
                        }
                    }
                    buffer.clear();
                }
            }
            completed = true;
            return new TemporaryArchiveSource(path);
        } finally {
            if (!completed) {
                Files.deleteIfExists(path);
            }
        }
    }

    /// Opens an independent read-only channel over the materialized archive.
    ///
    /// @return a new caller-owned channel positioned at archive offset zero
    /// @throws IOException if the source is closed or the temporary file cannot be opened
    @Override
    public synchronized SeekableByteChannel openChannel() throws IOException {
        if (closed) {
            throw new IOException("Temporary archive source is closed");
        }
        return Files.newByteChannel(path, StandardOpenOption.READ);
    }

    /// Deletes the owned temporary archive after all consumer channels have closed.
    ///
    /// A failed deletion leaves the source open so cleanup can be retried.
    ///
    /// @throws IOException if the temporary archive cannot be deleted
    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            Files.deleteIfExists(path);
            closed = true;
        }
    }

    /// Closes an input after temporary-file allocation fails without hiding the primary failure.
    private static void closeAfterFailure(ReadableByteChannel source, Throwable failure) {
        try {
            source.close();
        } catch (IOException | RuntimeException | Error cleanupFailure) {
            if (failure != cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }
}
