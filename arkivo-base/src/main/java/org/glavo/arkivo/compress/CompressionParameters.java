// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.compress;

import org.glavo.arkivo.compress.internal.CompressionParametersImpl;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Describes optional parameters used when opening or running compression operations.
@NotNullByDefault
public sealed interface CompressionParameters permits CompressionParametersImpl {
    /// The sentinel compression level that asks the codec to use its default level.
    int DEFAULT_COMPRESSION_LEVEL = -1;

    /// The sentinel size that indicates the uncompressed size is unknown.
    long UNKNOWN_UNCOMPRESSED_SIZE = -1L;

    /// Returns the default compression parameters.
    static CompressionParameters defaults() {
        return CompressionParametersImpl.DEFAULT;
    }

    /// Creates a compression parameter builder.
    static Builder builder() {
        return new Builder();
    }

    /// Returns the requested compression level, or `DEFAULT_COMPRESSION_LEVEL` when the codec default should be used.
    int compressionLevel();

    /// Returns the expected uncompressed size, or `UNKNOWN_UNCOMPRESSED_SIZE` when unknown.
    long expectedUncompressedSize();

    /// Returns a copy of the dictionary bytes, or `null` when no dictionary is configured.
    byte @Nullable [] dictionary();

    /// Builds immutable compression parameters.
    @NotNullByDefault
    final class Builder {
        /// The requested compression level.
        private int compressionLevel = DEFAULT_COMPRESSION_LEVEL;

        /// The expected uncompressed size.
        private long expectedUncompressedSize = UNKNOWN_UNCOMPRESSED_SIZE;

        /// The dictionary bytes, or `null` when no dictionary is configured.
        private byte @Nullable [] dictionary;

        /// Creates a compression parameter builder.
        public Builder() {
        }

        /// Sets the requested compression level.
        public Builder compressionLevel(int compressionLevel) {
            if (compressionLevel < DEFAULT_COMPRESSION_LEVEL) {
                throw new IllegalArgumentException("compressionLevel is out of range");
            }
            this.compressionLevel = compressionLevel;
            return this;
        }

        /// Sets the expected uncompressed size.
        public Builder expectedUncompressedSize(long expectedUncompressedSize) {
            if (expectedUncompressedSize < UNKNOWN_UNCOMPRESSED_SIZE) {
                throw new IllegalArgumentException("expectedUncompressedSize is out of range");
            }
            this.expectedUncompressedSize = expectedUncompressedSize;
            return this;
        }

        /// Sets dictionary bytes used by codecs that support dictionaries.
        public Builder dictionary(byte[] dictionary) {
            this.dictionary = Objects.requireNonNull(dictionary, "dictionary").clone();
            return this;
        }

        /// Clears the configured dictionary.
        public Builder clearDictionary() {
            dictionary = null;
            return this;
        }

        /// Builds immutable compression parameters.
        public CompressionParameters build() {
            if (compressionLevel == DEFAULT_COMPRESSION_LEVEL
                    && expectedUncompressedSize == UNKNOWN_UNCOMPRESSED_SIZE
                    && dictionary == null) {
                return CompressionParametersImpl.DEFAULT;
            }
            return new CompressionParametersImpl(compressionLevel, expectedUncompressedSize, dictionary);
        }
    }
}
