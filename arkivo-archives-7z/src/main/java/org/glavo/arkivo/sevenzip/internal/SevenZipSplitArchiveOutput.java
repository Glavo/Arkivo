// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.glavo.arkivo.ArkivoVolumeOutput;
import org.glavo.arkivo.ArkivoVolumeTarget;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Set;

/// Assembles a seekable 7z archive in temporary storage before publishing split output volumes.
@NotNullByDefault
final class SevenZipSplitArchiveOutput {
    /// The temporary archive path prefix.
    private static final String TEMPORARY_FILE_PREFIX = "arkivo-7z-assembly-";

    /// The temporary archive path suffix.
    private static final String TEMPORARY_FILE_SUFFIX = ".7z.tmp";

    /// The copy buffer size used while publishing volumes.
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    /// The maximum consecutive zero-byte channel operations tolerated during publication.
    private static final int MAX_ZERO_PROGRESS_ATTEMPTS = 1024;

    /// The writer that assembles the complete archive.
    private final SevenZOutputFile writer;

    /// The temporary complete archive path.
    private final Path temporaryArchivePath;

    /// The transactional destination for split volumes.
    private final ArkivoVolumeTarget target;

    /// The maximum number of bytes written to one volume.
    private final long splitSize;

    /// The volume output opened during publication, or `null` before publication or after successful cleanup.
    private @Nullable ArkivoVolumeOutput volumeOutput;

    /// Whether publication or rollback has started.
    private boolean finishStarted;

    /// Whether the volume output committed successfully.
    private boolean committed;

    /// Whether all output and temporary-file cleanup has completed.
    private boolean cleanupComplete;

    /// Opens temporary seekable storage for one optionally encrypted split 7z archive.
    static SevenZipSplitArchiveOutput open(
            ArkivoVolumeTarget target,
            long splitSize,
            char @Nullable [] password
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        if (splitSize <= 0) {
            throw new IllegalArgumentException("splitSize must be positive");
        }

        Path temporaryArchivePath = Files.createTempFile(TEMPORARY_FILE_PREFIX, TEMPORARY_FILE_SUFFIX);
        @Nullable SeekableByteChannel channel = null;
        try {
            channel = Files.newByteChannel(
                    temporaryArchivePath,
                    Set.of(StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
            );
            SevenZOutputFile writer = password != null
                    ? new SevenZOutputFile(channel, password)
                    : new SevenZOutputFile(channel);
            channel = null;
            return new SevenZipSplitArchiveOutput(writer, temporaryArchivePath, target, splitSize);
        } catch (IOException | RuntimeException | Error exception) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException | RuntimeException | Error closeException) {
                    exception.addSuppressed(closeException);
                }
            }
            try {
                Files.deleteIfExists(temporaryArchivePath);
            } catch (IOException | RuntimeException | Error cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            throw exception;
        }
    }

    /// Creates a split archive output over an already opened temporary writer.
    private SevenZipSplitArchiveOutput(
            SevenZOutputFile writer,
            Path temporaryArchivePath,
            ArkivoVolumeTarget target,
            long splitSize
    ) {
        this.writer = Objects.requireNonNull(writer, "writer");
        this.temporaryArchivePath = Objects.requireNonNull(temporaryArchivePath, "temporaryArchivePath");
        this.target = Objects.requireNonNull(target, "target");
        this.splitSize = splitSize;
    }

    /// Returns the seekable writer used to assemble the complete archive.
    SevenZOutputFile writer() {
        return writer;
    }

    /// Replaces the completed temporary archive's plain next header with an encrypted encoded header.
    void encryptHeader(SevenZipHeaderEncryption encryption) throws IOException {
        Objects.requireNonNull(encryption, "encryption");
        if (finishStarted) {
            throw new IllegalStateException("7z split output publication has already started");
        }
        encryption.applyTo(temporaryArchivePath);
    }

    /// Publishes the completed temporary archive as split volumes.
    void commit() throws IOException {
        if (cleanupComplete) {
            return;
        }
        if (finishStarted) {
            finishCleanup();
            return;
        }
        finishStarted = true;

        @Nullable Throwable failure = null;
        try {
            volumeOutput = Objects.requireNonNull(target.openOutput(), "volumeOutput");
            long finalVolumeIndex = writeVolumes(Objects.requireNonNull(volumeOutput, "volumeOutput"));
            Objects.requireNonNull(volumeOutput, "volumeOutput").commit(finalVolumeIndex);
            committed = true;
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
        }

        failure = cleanupResources(failure);
        throwFailure(failure);
    }

    /// Abandons unpublished output and removes the temporary archive.
    void rollback() throws IOException {
        if (cleanupComplete) {
            return;
        }
        finishStarted = true;
        @Nullable Throwable failure = cleanupResources(null);
        throwFailure(failure);
    }

    /// Retries cleanup after an earlier publication or rollback failure.
    private void finishCleanup() throws IOException {
        @Nullable Throwable failure = cleanupResources(null);
        throwFailure(failure);
    }

    /// Writes every split volume and returns the final zero-based volume index.
    private long writeVolumes(ArkivoVolumeOutput output) throws IOException {
        try (SeekableByteChannel source = Files.newByteChannel(temporaryArchivePath, StandardOpenOption.READ)) {
            long remaining = source.size();
            long volumeIndex = 0L;
            while (true) {
                long volumeSize = Math.min(splitSize, remaining);
                WritableByteChannel volume = Objects.requireNonNull(
                        output.openVolume(volumeIndex),
                        "volume channel"
                );
                try (volume) {
                    copyExactly(source, volume, volumeSize);
                }
                remaining -= volumeSize;
                if (remaining == 0L) {
                    return volumeIndex;
                }
                try {
                    volumeIndex = Math.addExact(volumeIndex, 1L);
                } catch (ArithmeticException exception) {
                    throw new IOException("7z split archive has too many output volumes", exception);
                }
            }
        }
    }

    /// Copies exactly the requested number of bytes between channels.
    private static void copyExactly(
            SeekableByteChannel source,
            WritableByteChannel destination,
            long byteCount
    ) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(COPY_BUFFER_SIZE, Math.max(1L, byteCount)));
        long remaining = byteCount;
        int zeroReadCount = 0;
        while (remaining > 0L) {
            buffer.clear();
            buffer.limit((int) Math.min(buffer.capacity(), remaining));
            int read = source.read(buffer);
            if (read < 0) {
                throw new IOException("Unexpected end of temporary 7z archive");
            }
            if (read == 0) {
                zeroReadCount++;
                if (zeroReadCount >= MAX_ZERO_PROGRESS_ATTEMPTS) {
                    throw new IOException("Temporary 7z archive read made no progress");
                }
                Thread.onSpinWait();
                continue;
            }
            zeroReadCount = 0;
            buffer.flip();
            int zeroWriteCount = 0;
            while (buffer.hasRemaining()) {
                if (destination.write(buffer) == 0) {
                    zeroWriteCount++;
                    if (zeroWriteCount >= MAX_ZERO_PROGRESS_ATTEMPTS) {
                        throw new IOException("7z volume write made no progress");
                    }
                    Thread.onSpinWait();
                } else {
                    zeroWriteCount = 0;
                }
            }
            remaining -= read;
        }
    }

    /// Cleans the opened volume output and temporary archive while preserving failure order.
    private @Nullable Throwable cleanupResources(@Nullable Throwable failure) {
        boolean cleanupFailed = false;
        @Nullable ArkivoVolumeOutput output = volumeOutput;
        if (output != null) {
            if (!committed) {
                try {
                    output.rollback();
                } catch (IOException | RuntimeException | Error exception) {
                    failure = appendFailure(failure, exception);
                    cleanupFailed = true;
                }
            }
            try {
                output.close();
            } catch (IOException | RuntimeException | Error exception) {
                failure = appendFailure(failure, exception);
                cleanupFailed = true;
            }
            if (!cleanupFailed) {
                volumeOutput = null;
            }
        }
        try {
            Files.deleteIfExists(temporaryArchivePath);
        } catch (IOException | RuntimeException | Error exception) {
            failure = appendFailure(failure, exception);
            cleanupFailed = true;
        }
        if (!cleanupFailed) {
            cleanupComplete = true;
        }
        return failure;
    }

    /// Adds a secondary failure as suppressed when a primary failure already exists.
    private static Throwable appendFailure(@Nullable Throwable failure, Throwable exception) {
        if (failure == null) {
            return exception;
        }
        if (failure != exception) {
            failure.addSuppressed(exception);
        }
        return failure;
    }

    /// Throws an accumulated failure with its original category.
    private static void throwFailure(@Nullable Throwable failure) throws IOException {
        if (failure instanceof IOException exception) {
            throw exception;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error exception) {
            throw exception;
        }
    }
}
