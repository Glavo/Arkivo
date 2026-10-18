// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.glavo.arkivo.archive.ArchiveReadLimits;
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

    /// Creates independent counters for the supplied immutable limits.
    private ArkivoReadLimitTracker(ArchiveReadLimits limits) {
        this.maximumEntryCount = limits.maximumEntryCount();
        this.maximumEntrySize = limits.maximumEntrySize();
        this.maximumTotalEntrySize = limits.maximumTotalEntrySize();
        this.maximumMetadataSize = limits.maximumMetadataSize();
    }

    /// Creates a fresh tracker from immutable archive read limits.
    ///
    /// @param limits the immutable limits to enforce
    /// @return a new tracker with independent counters
    public static ArkivoReadLimitTracker fromLimits(ArchiveReadLimits limits) {
        Objects.requireNonNull(limits, "limits");
        return new ArkivoReadLimitTracker(limits);
    }

    /// Accounts for archive metadata before it is buffered or expanded.
    ///
    /// @param size the non-negative metadata byte count to add
    /// @param entryPath the associated archive-local path, or {@code null} for archive-wide metadata
    /// @throws ArkivoReadLimitException if this addition exceeds the metadata limit or an earlier limit already failed
    /// @throws IllegalArgumentException if {@code size} is negative
    public void acceptMetadata(long size, @Nullable String entryPath) throws ArkivoReadLimitException {
        requireWithinLimits();
        if (size < 0L) {
            throw new IllegalArgumentException("Archive metadata size must be non-negative");
        }
        long actual = saturatedAdd(metadataSize, size);
        if (maximumMetadataSize != UNLIMITED && size > maximumMetadataSize - metadataSize) {
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
    ///
    /// @param path the archive-local entry path
    /// @param size the known non-negative logical size, or {@code -1} when unknown
    /// @throws ArkivoReadLimitException if entry count or known size exceeds a limit, or an earlier limit already failed
    /// @throws IllegalArgumentException if {@code size} is less than {@code -1}
    public void acceptEntry(String path, long size) throws ArkivoReadLimitException {
        Objects.requireNonNull(path, "path");
        requireWithinLimits();
        if (size < UNLIMITED) {
            throw new IllegalArgumentException("Archive entry size must be unknown or non-negative");
        }

        long actualCount = saturatedIncrement(entryCount);
        if (maximumEntryCount != UNLIMITED && entryCount >= maximumEntryCount) {
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
    ///
    /// Reads request at most the smaller remaining entry or total allowance plus one byte to distinguish EOF from
    /// excess input. A read that exceeds a limit may place that probe byte in the caller's array before throwing.
    /// This bounds the requested decoded bytes, not any internal buffering performed by the underlying decoder.
    ///
    /// @param path the archive-local entry path used in limit failures
    /// @param input the decoded stream whose ownership remains with the returned wrapper
    /// @return a stream that accounts for reads and skips against entry-size limits
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
        if (maximumTotalEntrySize != UNLIMITED && size > maximumTotalEntrySize - totalEntrySize) {
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
    ///
    /// @throws ArkivoReadLimitException if any earlier accounting operation exceeded a configured limit
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

        /// Storage allocated when decoded bytes are first skipped through the accounting path.
        private byte @Nullable [] skipBuffer;

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
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            long allowance = Long.MAX_VALUE;
            if (maximumEntrySize != UNLIMITED) {
                allowance = maximumEntrySize - observedSize;
            }
            if (maximumTotalEntrySize != UNLIMITED) {
                allowance = Math.min(allowance, maximumTotalEntrySize - totalEntrySize);
            }
            // Add the probe byte only when the allowance is smaller than the int-sized request.
            int requested = allowance < length ? (int) (allowance + 1L) : length;
            int read = super.read(bytes, offset, requested);
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
            byte @Nullable [] buffer = skipBuffer;
            if (buffer == null) {
                buffer = new byte[SKIP_BUFFER_SIZE];
                skipBuffer = buffer;
            }
            long skipped = 0L;
            while (skipped < count) {
                int requested = (int) Math.min(count - skipped, buffer.length);
                int read = read(buffer, 0, requested);
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
            if (maximumEntrySize != UNLIMITED && count > maximumEntrySize - observedSize) {
                throw reject(
                        ArkivoReadLimitKind.ENTRY_SIZE,
                        maximumEntrySize,
                        nextSize,
                        path
                );
            }
            long nextTotal = saturatedAdd(totalEntrySize, count);
            if (maximumTotalEntrySize != UNLIMITED && count > maximumTotalEntrySize - totalEntrySize) {
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
