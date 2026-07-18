// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4;

import org.jetbrains.annotations.NotNullByDefault;

/// Enumerates the maximum decoded block sizes defined by the LZ4 frame format.
@NotNullByDefault
public enum LZ4BlockSize {
    /// Uses blocks containing at most 64 KiB of decoded data.
    KIB_64(64 * 1024, 4),

    /// Uses blocks containing at most 256 KiB of decoded data.
    KIB_256(256 * 1024, 5),

    /// Uses blocks containing at most 1 MiB of decoded data.
    MIB_1(1024 * 1024, 6),

    /// Uses blocks containing at most 4 MiB of decoded data.
    MIB_4(4 * 1024 * 1024, 7);

    /// Maximum decoded bytes in one block.
    private final int byteSize;

    /// Three-bit block-size code stored in the frame descriptor.
    private final int descriptorCode;

    /// Creates one standard LZ4 frame block-size value.
    LZ4BlockSize(int byteSize, int descriptorCode) {
        this.byteSize = byteSize;
        this.descriptorCode = descriptorCode;
    }

    /// Returns the maximum decoded bytes in one block.
    public int byteSize() {
        return byteSize;
    }

    /// Returns the three-bit value stored in an LZ4 frame descriptor.
    public int descriptorCode() {
        return descriptorCode;
    }

    /// Resolves one validated descriptor code.
    public static LZ4BlockSize fromDescriptorCode(int descriptorCode) {
        return switch (descriptorCode) {
            case 4 -> KIB_64;
            case 5 -> KIB_256;
            case 6 -> MIB_1;
            case 7 -> MIB_4;
            default -> throw new IllegalArgumentException(
                    "Unsupported LZ4 frame block-size code: " + descriptorCode
            );
        };
    }
}
