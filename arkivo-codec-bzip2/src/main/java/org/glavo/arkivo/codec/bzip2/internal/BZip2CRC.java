// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bzip2.internal;

import org.jetbrains.annotations.NotNullByDefault;
/// Combines completed BZip2 block CRC values into the stream-level checksum.
@NotNullByDefault
final class BZip2CRC {
    /// Prevents utility-class construction.
    private BZip2CRC() {
    }

    /// Adds a completed block CRC to the stream-level combined CRC.
    static int combine(int combinedCrc, int blockCrc) {
        return Integer.rotateLeft(combinedCrc, 1) ^ blockCrc;
    }
}
