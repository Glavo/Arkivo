// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDictionary;
import org.jetbrains.annotations.NotNullByDefault;

/// Describes one standard or explicitly selected magicless Zstandard frame header.
///
/// @param headerSize complete frame-header size in bytes
/// @param contentSize declared decompressed content size, CompressionCodec.UNKNOWN_SIZE when absent, or
/// CONTENT_SIZE_OVERFLOW when the unsigned field exceeds Long.MAX_VALUE
/// @param windowSize required decoding window, saturated to Long.MAX_VALUE
/// @param dictionaryId dictionary identifier, or CompressionDictionary.UNKNOWN_ID when absent
/// @param checksum whether a four-byte content checksum follows the final block
@NotNullByDefault
public record ZstdStandardFrameInfo(
        int headerSize,
        long contentSize,
        long windowSize,
        long dictionaryId,
        boolean checksum
) implements ZstdFrameInfo {
    /// Indicates that an unsigned 64-bit frame content size exceeds the Java long range.
    public static final long CONTENT_SIZE_OVERFLOW = -2L;

    /// Validates parsed frame-header fields.
    public ZstdStandardFrameInfo {
        if (headerSize < 2 || headerSize > 18) {
            throw new IllegalArgumentException("headerSize is out of range");
        }
        if (contentSize < CONTENT_SIZE_OVERFLOW) {
            throw new IllegalArgumentException("contentSize is out of range");
        }
        if (windowSize < 0L) {
            throw new IllegalArgumentException("windowSize must not be negative");
        }
        if (dictionaryId < CompressionDictionary.UNKNOWN_ID) {
            throw new IllegalArgumentException("dictionaryId is out of range");
        }
        if (contentSize == CompressionCodec.UNKNOWN_SIZE && windowSize == 0L) {
            throw new IllegalArgumentException("A frame without content size must declare a positive window");
        }
    }

    /// Returns false for a standard frame.
    @Override
    public boolean skippable() {
        return false;
    }
}
