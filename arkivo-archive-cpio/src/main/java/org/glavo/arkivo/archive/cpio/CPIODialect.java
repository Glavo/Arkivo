// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.cpio;

import org.jetbrains.annotations.NotNullByDefault;

/// Identifies one standardized CPIO header representation.
@NotNullByDefault
public enum CPIODialect {
    /// The SVR4 portable ASCII format identified by `070701`, commonly called `newc`.
    NEW_ASCII,

    /// The SVR4 portable ASCII format identified by `070702`, with a data checksum.
    NEW_ASCII_CRC,

    /// The POSIX portable ASCII format identified by `070707`, commonly called `odc`.
    OLD_ASCII,

    /// The historical binary format identified by the 16-bit value `070707`.
    OLD_BINARY
}
