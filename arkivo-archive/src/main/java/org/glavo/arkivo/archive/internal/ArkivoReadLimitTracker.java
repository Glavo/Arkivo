// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.glavo.arkivo.archive.ArchiveOptions;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoReadLimitException;
import org.glavo.arkivo.archive.ArkivoReadLimitKind;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/// Tracks archive metadata, entry counts, and logical sizes against common read options.
@NotNullByDefault
public final class ArkivoReadLimitTracker {
    /// The sentinel used internally when a limit is not configured.
    private static final long UNLIMITED = -1L;

    /// The reusable buffer size used when callers skip decoded bytes.
    private static final int SKIP_BUFFER_SIZE = 8192;

    /// The configured maximum logical entry count, or `UNLIMITED`.
    private final long maximumEntryCount;

    /// The configured maximum logical size of one entry, or `UNLIMITED`.
    private final long maximumEntrySize;

    /// The configured maximum sum of logical entry sizes, or `UNLIMITED`.
    private final long maximumTotalEntrySize;

    /// The configured maximum cumulative metadata size, or `UNLIMITED`.
    private final long maximumMetadataSize;

    /// The number of accepted logical entries.
    private long entryCount;

    /// The sum of accepted known or observed logical entry sizes.
    private long totalEntrySize;

    /// The cumulative number of accepted archive metadata bytes.
    private long metadataSize;

    /// The first rejected limit, or `null` while the archive remains within every configured limit.
    private @Nullable ArkivoReadLimitException failure;

    /// Creates a tracker from primitive limits.
    private ArkivoReadLimitTracker(
            long maximumEntryCount,
            long maximumEntrySize,
            long maximumTotalEntrySize,
            long maximumMetadataSize
    ) {
        this.maximumEntryCount = maximumEntryCount;
        this.maximumEntrySize = maximumEntrySize;
        this.maximumTotalEntrySize = maximumTotalEntrySize;
        this.maximumMetadataSize = maximumMetadataSize;
    }

    /// Creates a fresh tracker from common archive options.
    public static ArkivoReadLimitTracker fromOptions(ArchiveOptions options) {
        Objects.requireNonNull(options, "options");
        return new ArkivoReadLimitTracker(
                options.getOrDefault(ArkivoFileSystem.MAX_ENTRY_COUNT, UNLIMITED),
                options.getOrDefault(ArkivoFileSystem.MAX_ENTRY_SIZE, UNLIMITED),
                options.getOrDefault(ArkivoFileSystem.MAX_TOTAL_ENTRY_SIZE, UNLIMITED),
                options.getOrDefault(ArkivoFileSystem.MAX_METADATA_SIZE, UNLIMITED)
        );
    }

    /// Creates a fresh tracker from primitive limits using `-1` for an unconfigured limit.
    public static ArkivoReadLimitTracker fromLimits(
            long maximumEntryCount,
            long maximumEntrySize,
            long maximumTotalEntrySize
    ) {
        return fromLimits(maximumEntryCount, maximumEntrySize, maximumTotalEntrySize, UNLIMITED);
    }

    /// Creates a fresh tracker from primitive limits using `-1` for an unconfigured limit.
    public static ArkivoReadLimitTracker fromLimits(
            long maximumEntryCount,
            long maximumEntrySize,
            long maximumTotalEntrySize,
            long maximumMetadataSize
    ) {
        requireLimit(maximumEntryCount, "maximumEntryCount");
        requireLimit(maximumEntrySize, "maximumEntrySize");
        requireLimit(maximumTotalEntrySize, "maximumTotalEntrySize");
        requireLimit(maximumMetadataSize, "maximumMetadataSize");
        return new ArkivoReadLimitTracker(
                maximumEntryCount,
                maximumEntrySize,
                maximumTotalEntrySize,
                maximumMetadataSize
        );
    }

    /// Accounts for archive metadata before it is buffered or expanded.
    public void acceptMetadata(long size, @Nullable String entryPath) throws ArkivoReadLimitException {
        requireWithinLimits();
        if (size < 0L) {
            throw new IllegalArgumentException("Archive metadata size must be non-negative");
        }
        long actual = saturatedAdd(metadataSize, size);
        if (maximumMetadataSize != UNLIMITED && actual > maximumMetadataSize) {
            throw reject(
                    ArkivoReadLimitKind.METADATA_SIZE,
                    maximumMetadataSize,
                    actual,
                    entryPath
            );
        }
        metadataSize = actual;
    }

    /// Accepts one logical entry and reserves its known size.
    ///
    /// A negative size means that the format has not discovered the logical size yet. In that case callers must wrap
    /// the decoded body with `trackUnknownEntrySize` so observed bytes contribute to size limits.
    public void acceptEntry(String path, long size) throws ArkivoReadLimitException {
        Objects.requireNonNull(path, "path");
        requireWithinLimits();
        if (size < UNLIMITED) {
            throw new IllegalArgumentException("Archive entry size must be unknown or non-negative");
        }

        long actualCount = saturatedIncrement(entryCount);
        if (maximumEntryCount != UNLIMITED && actualCount > maximumEntryCount) {
            throw reject(
                    ArkivoReadLimitKind.ENTRY_COUNT,
                    maximumEntryCount,
                    actualCount,
                    null
            );
        }
        entryCount = actualCount;
        if (size >= 0L) {
            acceptSize(path, size);
        }
    }

    /// Wraps one decoded body whose logical size was unknown when its entry metadata was accepted.
    public InputStream trackUnknownEntrySize(String path, InputStream input) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(input, "input");
        return new UnknownSizeInputStream(path, input);
    }

    /// Accepts a known or newly observed logical entry size.
    private void acceptSize(String path, long size) throws ArkivoReadLimitException {
        if (maximumEntrySize != UNLIMITED && size > maximumEntrySize) {
            throw reject(
                    ArkivoReadLimitKind.ENTRY_SIZE,
                    maximumEntrySize,
                    size,
                    path
            );
        }
        long actualTotal = saturatedAdd(totalEntrySize, size);
        if (maximumTotalEntrySize != UNLIMITED && actualTotal > maximumTotalEntrySize) {
            throw reject(
                    ArkivoReadLimitKind.TOTAL_ENTRY_SIZE,
                    maximumTotalEntrySize,
                    actualTotal,
                    path
            );
        }
        totalEntrySize = actualTotal;
    }

    /// Re-throws the first limit failure so a rejected archive cannot resume parsing.
    public void requireWithinLimits() throws ArkivoReadLimitException {
        if (failure != null) {
            throw new ArkivoReadLimitException(
                    failure.kind(),
                    failure.maximum(),
                    failure.actual(),
                    failure.entryPath()
            );
        }
    }

    /// Records and returns the first structured limit failure.
    private ArkivoReadLimitException reject(
            ArkivoReadLimitKind kind,
            long maximum,
            long actual,
            @Nullable String entryPath
    ) {
        ArkivoReadLimitException exception = new ArkivoReadLimitException(kind, maximum, actual, entryPath);
        failure = exception;
        return exception;
    }

    /// Requires a primitive limit to use the unlimited sentinel or a non-negative value.
    private static void requireLimit(long value, String name) {
        if (value < UNLIMITED) {
            throw new IllegalArgumentException(name + " must be -1 or non-negative");
        }
    }

    /// Adds two non-negative counters and saturates on overflow.
    private static long saturatedAdd(long first, long second) {
        return first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
    }

    /// Increments a non-negative counter and saturates on overflow.
    private static long saturatedIncrement(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    /// Tracks decoded bytes for one entry whose size was absent from its initial metadata.
    @NotNullByDefault
    private final class UnknownSizeInputStream extends FilterInputStream {
        /// The archive-local entry path.
        private final String path;

        /// The number of decoded bytes observed for this entry.
        private long observedSize;

        /// The reusable buffer used to account for skipped decoded bytes.
        private final byte[] skipBuffer = new byte[SKIP_BUFFER_SIZE];

        /// Creates an unknown-size entry tracker.
        private UnknownSizeInputStream(String path, InputStream input) {
            super(input);
            this.path = path;
        }

        /// Reads and accounts for one decoded byte.
        @Override
        public int read() throws IOException {
            requireWithinLimits();
            int value = super.read();
            if (value >= 0) {
                account(1L);
            }
            return value;
        }

        /// Reads and accounts for decoded bytes.
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            requireWithinLimits();
            int read = super.read(bytes, offset, length);
            if (read > 0) {
                account(read);
            }
            return read;
        }

        /// Skips decoded bytes through the accounting read path.
        @Override
        public long skip(long count) throws IOException {
            requireWithinLimits();
            if (count <= 0L) {
                return 0L;
            }
            long skipped = 0L;
            while (skipped < count) {
                int requested = (int) Math.min(count - skipped, skipBuffer.length);
                int read = read(skipBuffer, 0, requested);
                if (read < 0) {
                    break;
                }
                skipped += read;
            }
            return skipped;
        }

        /// Accounts for newly decoded bytes against entry and total limits.
        private void account(long count) throws ArkivoReadLimitException {
            requireWithinLimits();
            long nextSize = saturatedAdd(observedSize, count);
            if (maximumEntrySize != UNLIMITED && nextSize > maximumEntrySize) {
                throw reject(
                        ArkivoReadLimitKind.ENTRY_SIZE,
                        maximumEntrySize,
                        nextSize,
                        path
                );
            }
            long nextTotal = saturatedAdd(totalEntrySize, count);
            if (maximumTotalEntrySize != UNLIMITED && nextTotal > maximumTotalEntrySize) {
                throw reject(
                        ArkivoReadLimitKind.TOTAL_ENTRY_SIZE,
                        maximumTotalEntrySize,
                        nextTotal,
                        path
                );
            }
            observedSize = nextSize;
            totalEntrySize = nextTotal;
        }
    }
}
