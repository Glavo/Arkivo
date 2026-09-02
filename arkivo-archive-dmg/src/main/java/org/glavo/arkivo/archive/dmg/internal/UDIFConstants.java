// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg.internal;

import org.jetbrains.annotations.NotNullByDefault;

/// Defines the stable UDIF signatures and run identifiers used by the parser.
@NotNullByDefault
final class UDIFConstants {
    /// The decoded sector size used by UDIF block maps.
    static final int SECTOR_SIZE = 512;

    /// The flattened UDIF trailer size.
    static final int TRAILER_SIZE = 512;

    /// The big-endian `koly` trailer signature.
    static final int KOLY_SIGNATURE = 0x6b6f6c79;

    /// The big-endian `mish` block-table signature.
    static final int MISH_SIGNATURE = 0x6d697368;

    /// A sparse zero-filled run.
    static final int BLOCK_ZEROES = 0x00000000;

    /// A verbatim stored run.
    static final int BLOCK_RAW = 0x00000001;

    /// An omitted zero-filled run.
    static final int BLOCK_IGNORE = 0x00000002;

    /// An Apple Data Compression run.
    static final int BLOCK_ADC = 0x80000004;

    /// A zlib-wrapped Deflate run.
    static final int BLOCK_ZLIB = 0x80000005;

    /// A BZip2 run.
    static final int BLOCK_BZIP2 = 0x80000006;

    /// An LZFSE run.
    static final int BLOCK_LZFSE = 0x80000007;

    /// An XZ stream identified by the historical UDIF LZMA run code.
    static final int BLOCK_LZMA = 0x80000008;

    /// A non-data block-table comment.
    static final int BLOCK_COMMENT = 0x7ffffffe;

    /// The block-table terminator.
    static final int BLOCK_TERMINATOR = 0xffffffff;

    /// Creates no instances.
    private UDIFConstants() {
    }
}
