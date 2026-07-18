// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.compress.internal;

import org.glavo.arkivo.codec.compress.UnixCompressCodec;
import org.jetbrains.annotations.NotNullByDefault;

/// Defines Unix compress header fields and shared LZW table calculations.
@NotNullByDefault
public final class UnixCompressSupport {
    /// The number of bytes in a Unix compress stream header.
    public static final int HEADER_SIZE = 3;

    /// The initial LZW code width used after the header and after a clear code.
    public static final int INITIAL_CODE_WIDTH = 9;

    /// The optional dictionary clear code used by block-mode streams.
    public static final int CLEAR_CODE = 256;

    /// The header flag that permits dictionary clear codes.
    public static final int BLOCK_MODE_MASK = 0x80;

    /// Header bits reserved for future format extensions.
    public static final int RESERVED_FLAGS_MASK = 0x60;

    /// The mask containing the maximum LZW code width.
    public static final int MAXIMUM_CODE_WIDTH_MASK = 0x1f;

    /// The number of bytes used by the three primitive decoder tables per code.
    private static final int DECODER_BYTES_PER_CODE = Integer.BYTES + 2;

    /// Prevents instantiation.
    private UnixCompressSupport() {
    }

    /// Validates and returns a portable Unix compress maximum code width.
    ///
    /// @param maximumCodeWidth the candidate width
    /// @return `maximumCodeWidth` when it is from 9 through 16
    /// @throws IllegalArgumentException if the width is outside the portable range
    public static int requireMaximumCodeWidth(int maximumCodeWidth) {
        if (maximumCodeWidth < UnixCompressCodec.MINIMUM_CODE_WIDTH
                || maximumCodeWidth > UnixCompressCodec.MAXIMUM_CODE_WIDTH) {
            throw new IllegalArgumentException(
                    "Unix compress maximum code width must be between "
                            + UnixCompressCodec.MINIMUM_CODE_WIDTH + " and "
                            + UnixCompressCodec.MAXIMUM_CODE_WIDTH + ": " + maximumCodeWidth
            );
        }
        return maximumCodeWidth;
    }

    /// Returns the code-table capacity represented by a maximum code width.
    ///
    /// @param maximumCodeWidth the validated maximum width
    /// @return the exclusive upper bound for dictionary codes
    /// @throws IllegalArgumentException if the width is outside the portable range
    public static int tableCapacity(int maximumCodeWidth) {
        return 1 << requireMaximumCodeWidth(maximumCodeWidth);
    }

    /// Returns the first unused dictionary code for the selected stream mode.
    ///
    /// @param blockMode whether the clear code is reserved
    /// @return 257 in block mode, otherwise 256
    public static int initialNextCode(boolean blockMode) {
        return blockMode ? CLEAR_CODE + 1 : CLEAR_CODE;
    }

    /// Returns the encoded third header byte for the selected stream parameters.
    ///
    /// @param maximumCodeWidth the maximum width stored in the low header bits
    /// @param blockMode whether to set the block-mode flag
    /// @return the unsigned third header-byte value
    /// @throws IllegalArgumentException if the width is outside the portable range
    public static int headerFlags(int maximumCodeWidth, boolean blockMode) {
        int flags = requireMaximumCodeWidth(maximumCodeWidth);
        return blockMode ? flags | BLOCK_MODE_MASK : flags;
    }

    /// Returns the dominant decoder table allocation required by a stream header.
    ///
    /// @param maximumCodeWidth the maximum width declared by the stream
    /// @return the combined byte size of the prefix, suffix, and expansion tables
    /// @throws IllegalArgumentException if the width is outside the portable range
    public static long decoderMemorySize(int maximumCodeWidth) {
        return (long) tableCapacity(maximumCodeWidth) * DECODER_BYTES_PER_CODE;
    }
}
