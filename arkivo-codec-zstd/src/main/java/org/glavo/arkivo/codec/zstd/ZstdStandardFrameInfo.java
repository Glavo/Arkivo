// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import org.glavo.arkivo.codec.CompressionCodec;
import org.jetbrains.annotations.NotNullByDefault;

/// Describes one standard or explicitly selected magicless Zstandard frame header.
///
/// Content size and dictionary identifier are optional header metadata. Window size is the decoder history requirement,
/// not a statement of decoded output size. The record is immutable and does not retain the source header buffer.
///
/// @param headerSize complete frame-header size in bytes
/// @param contentSize declared decompressed content size, CompressionCodec.UNKNOWN_SIZE when absent, or
/// CONTENT_SIZE_OVERFLOW when the unsigned field exceeds Long.MAX_VALUE
/// @param windowSize required decoding window, saturated to Long.MAX_VALUE
/// @param dictionaryId dictionary identifier, or `ZstdDictionary.NO_DICTIONARY_ID` when absent
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
    ///
    /// @throws IllegalArgumentException if a field is outside its encoded domain or the header omits both content size
    ///                                  and a positive window size
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
        if (dictionaryId < ZstdDictionary.NO_DICTIONARY_ID || dictionaryId > 0xffff_ffffL) {
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
