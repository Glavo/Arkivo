// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;

/// Defines operation-scoped resource limits for reading one archive.
///
/// A value equal to [#UNLIMITED_SIZE] disables the corresponding limit. Limits are propagated through outer
/// compression layers and nested archive readers so that decoded-output limits do not leave decoder allocation
/// unbounded.
///
/// @param maximumEntryCount            the maximum number of logical entries, or [#UNLIMITED_SIZE]
/// @param maximumEntrySize             the maximum logical size of one entry, or [#UNLIMITED_SIZE]
/// @param maximumTotalEntrySize        the maximum sum of logical entry sizes, or [#UNLIMITED_SIZE]
/// @param maximumMetadataSize          the maximum cumulative metadata size, or [#UNLIMITED_SIZE]
/// @param maximumCompressionWindowSize the maximum compression history-window size, or [#UNLIMITED_SIZE]
/// @param maximumDecoderMemorySize     the maximum decoder working-memory size, or [#UNLIMITED_SIZE]
/// @param maximumDecodedArchiveSize    the maximum decoded byte size of an outer-compressed archive, or
///                                     [#UNLIMITED_SIZE]
/// @param maximumOuterCompressionLayers the maximum number of decoded outer compression layers, or
///                                      [#UNLIMITED_SIZE]
@NotNullByDefault
public record ArchiveReadLimits(
        long maximumEntryCount,
        long maximumEntrySize,
        long maximumTotalEntrySize,
        long maximumMetadataSize,
        long maximumCompressionWindowSize,
        long maximumDecoderMemorySize,
        long maximumDecodedArchiveSize,
        long maximumOuterCompressionLayers
) {
    /// The sentinel used for an unenforced non-negative limit.
    public static final long UNLIMITED_SIZE = -1L;

    /// The default maximum number of nested outer compression layers.
    public static final long DEFAULT_MAXIMUM_OUTER_COMPRESSION_LAYERS = 4L;

    /// An unrestricted limit set for trusted inputs and compatibility-oriented callers.
    public static final ArchiveReadLimits UNLIMITED = new ArchiveReadLimits(
            UNLIMITED_SIZE,
            UNLIMITED_SIZE,
            UNLIMITED_SIZE,
            UNLIMITED_SIZE,
            UNLIMITED_SIZE,
            UNLIMITED_SIZE,
            UNLIMITED_SIZE,
            UNLIMITED_SIZE
    );

    /// The default limits for high-level archive operations.
    ///
    /// Entry sizes and decoder allocation remain unrestricted unless callers configure them, while nested outer
    /// compression is bounded to prevent an unbounded transformation chain.
    public static final ArchiveReadLimits DEFAULT = new ArchiveReadLimits(
            UNLIMITED_SIZE,
            UNLIMITED_SIZE,
            UNLIMITED_SIZE,
            UNLIMITED_SIZE,
            UNLIMITED_SIZE,
            UNLIMITED_SIZE,
            UNLIMITED_SIZE,
            DEFAULT_MAXIMUM_OUTER_COMPRESSION_LAYERS
    );

    /// Validates every configured limit.
    public ArchiveReadLimits {
        requireLimit(maximumEntryCount, "maximumEntryCount");
        requireLimit(maximumEntrySize, "maximumEntrySize");
        requireLimit(maximumTotalEntrySize, "maximumTotalEntrySize");
        requireLimit(maximumMetadataSize, "maximumMetadataSize");
        requireLimit(maximumCompressionWindowSize, "maximumCompressionWindowSize");
        requireLimit(maximumDecoderMemorySize, "maximumDecoderMemorySize");
        requireLimit(maximumDecodedArchiveSize, "maximumDecodedArchiveSize");
        requireLimit(maximumOuterCompressionLayers, "maximumOuterCompressionLayers");
    }

    /// Returns a builder initialized with unrestricted limits.
    ///
    /// @return a new unrestricted builder
    public static Builder builder() {
        return new Builder();
    }

    /// Returns the effective history-window limit after applying the decoder-memory ceiling.
    ///
    /// History-based decoders whose dominant allocation is their window may use this value directly. Decoders with
    /// additional model memory must enforce [#maximumDecoderMemorySize()] separately.
    ///
    /// @return the smaller enforced window or memory limit, or {@link #UNLIMITED_SIZE} if both are unrestricted
    public long effectiveCompressionWindowSize() {
        if (maximumCompressionWindowSize == UNLIMITED_SIZE) {
            return maximumDecoderMemorySize;
        }
        if (maximumDecoderMemorySize == UNLIMITED_SIZE) {
            return maximumCompressionWindowSize;
        }
        return Math.min(maximumCompressionWindowSize, maximumDecoderMemorySize);
    }

    /// Requires a limit to be unrestricted or non-negative.
    private static void requireLimit(long value, String name) {
        if (value < UNLIMITED_SIZE) {
            throw new IllegalArgumentException(name + " must be non-negative or UNLIMITED_SIZE");
        }
    }

    /// Builds immutable archive read limits without boxed optional numeric values.
    @NotNullByDefault
    public static final class Builder {
        /// The maximum logical entry count.
        private long maximumEntryCount = UNLIMITED_SIZE;

        /// The maximum logical size of one entry.
        private long maximumEntrySize = UNLIMITED_SIZE;

        /// The maximum sum of logical entry sizes.
        private long maximumTotalEntrySize = UNLIMITED_SIZE;

        /// The maximum cumulative metadata size.
        private long maximumMetadataSize = UNLIMITED_SIZE;

        /// The maximum compression history-window size.
        private long maximumCompressionWindowSize = UNLIMITED_SIZE;

        /// The maximum decoder working-memory size.
        private long maximumDecoderMemorySize = UNLIMITED_SIZE;

        /// The maximum decoded byte size of an outer-compressed archive.
        private long maximumDecodedArchiveSize = UNLIMITED_SIZE;

        /// The maximum number of decoded outer compression layers.
        private long maximumOuterCompressionLayers = UNLIMITED_SIZE;

        /// Creates an unrestricted builder.
        private Builder() {
        }

        /// Sets the maximum logical entry count.
        ///
        /// @param value the non-negative limit, or {@link #UNLIMITED_SIZE}
        /// @return this builder
        /// @throws IllegalArgumentException if {@code value} is less than {@link #UNLIMITED_SIZE}
        public Builder maximumEntryCount(long value) {
            requireLimit(value, "maximumEntryCount");
            maximumEntryCount = value;
            return this;
        }

        /// Sets the maximum logical size of one entry.
        ///
        /// @param value the non-negative byte limit, or {@link #UNLIMITED_SIZE}
        /// @return this builder
        /// @throws IllegalArgumentException if {@code value} is less than {@link #UNLIMITED_SIZE}
        public Builder maximumEntrySize(long value) {
            requireLimit(value, "maximumEntrySize");
            maximumEntrySize = value;
            return this;
        }

        /// Sets the maximum sum of logical entry sizes.
        ///
        /// @param value the non-negative byte limit, or {@link #UNLIMITED_SIZE}
        /// @return this builder
        /// @throws IllegalArgumentException if {@code value} is less than {@link #UNLIMITED_SIZE}
        public Builder maximumTotalEntrySize(long value) {
            requireLimit(value, "maximumTotalEntrySize");
            maximumTotalEntrySize = value;
            return this;
        }

        /// Sets the maximum cumulative metadata size.
        ///
        /// @param value the non-negative byte limit, or {@link #UNLIMITED_SIZE}
        /// @return this builder
        /// @throws IllegalArgumentException if {@code value} is less than {@link #UNLIMITED_SIZE}
        public Builder maximumMetadataSize(long value) {
            requireLimit(value, "maximumMetadataSize");
            maximumMetadataSize = value;
            return this;
        }

        /// Sets the maximum compression history-window size.
        ///
        /// @param value the non-negative byte limit, or {@link #UNLIMITED_SIZE}
        /// @return this builder
        /// @throws IllegalArgumentException if {@code value} is less than {@link #UNLIMITED_SIZE}
        public Builder maximumCompressionWindowSize(long value) {
            requireLimit(value, "maximumCompressionWindowSize");
            maximumCompressionWindowSize = value;
            return this;
        }

        /// Sets the maximum decoder working-memory size.
        ///
        /// @param value the non-negative byte limit, or {@link #UNLIMITED_SIZE}
        /// @return this builder
        /// @throws IllegalArgumentException if {@code value} is less than {@link #UNLIMITED_SIZE}
        public Builder maximumDecoderMemorySize(long value) {
            requireLimit(value, "maximumDecoderMemorySize");
            maximumDecoderMemorySize = value;
            return this;
        }

        /// Sets the maximum decoded byte size of an outer-compressed archive.
        ///
        /// @param value the non-negative byte limit, or [#UNLIMITED_SIZE]
        /// @return this builder
        /// @throws IllegalArgumentException if `value` is less than [#UNLIMITED_SIZE]
        public Builder maximumDecodedArchiveSize(long value) {
            requireLimit(value, "maximumDecodedArchiveSize");
            maximumDecodedArchiveSize = value;
            return this;
        }

        /// Sets the maximum number of decoded outer compression layers.
        ///
        /// @param value the non-negative layer limit, or [#UNLIMITED_SIZE]
        /// @return this builder
        /// @throws IllegalArgumentException if `value` is less than [#UNLIMITED_SIZE]
        public Builder maximumOuterCompressionLayers(long value) {
            requireLimit(value, "maximumOuterCompressionLayers");
            maximumOuterCompressionLayers = value;
            return this;
        }

        /// Returns the immutable configured limits.
        ///
        /// @return a new immutable limit set containing the current builder values
        public ArchiveReadLimits build() {
            return new ArchiveReadLimits(
                    maximumEntryCount,
                    maximumEntrySize,
                    maximumTotalEntrySize,
                    maximumMetadataSize,
                    maximumCompressionWindowSize,
                    maximumDecoderMemorySize,
                    maximumDecodedArchiveSize,
                    maximumOuterCompressionLayers
            );
        }
    }
}
