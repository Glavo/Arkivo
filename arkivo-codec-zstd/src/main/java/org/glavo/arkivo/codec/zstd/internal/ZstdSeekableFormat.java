// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.jetbrains.annotations.NotNullByDefault;

/// Defines the fixed fields and implementation bounds of the Zstandard seekable format.
@NotNullByDefault
final class ZstdSeekableFormat {
    /// The skippable-frame magic assigned to a Zstandard seek table.
    static final int SEEK_TABLE_SKIPPABLE_MAGIC = 0x184d_2a5e;

    /// The magic stored in the final four bytes of a seekable encoding.
    static final int SEEKABLE_MAGIC = 0x8f92_eab1;

    /// The seek-table footer size.
    static final int FOOTER_SIZE = 9;

    /// The skippable-frame header size.
    static final int SKIPPABLE_HEADER_SIZE = 8;

    /// The size of an entry without a checksum.
    static final int ENTRY_SIZE = 8;

    /// The size of an entry carrying a checksum.
    static final int CHECKSUM_ENTRY_SIZE = 12;

    /// The descriptor bit indicating per-frame checksums in the seek table.
    static final int CHECKSUM_FLAG = 0x80;

    /// Descriptor bits that must be zero in a conforming seek table.
    static final int RESERVED_DESCRIPTOR_MASK = 0x7c;

    /// The maximum frame count supported by the reference seekable implementation.
    static final int MAXIMUM_FRAME_COUNT = 0x0800_0000;

    /// The maximum uncompressed frame size supported by the reference seekable implementation.
    static final int MAXIMUM_FRAME_SIZE = 0x4000_0000;

    /// The maximum unsigned 32-bit value.
    static final long UNSIGNED_INT_MAX = 0xffff_ffffL;

    /// Prevents utility-class construction.
    private ZstdSeekableFormat() {
    }
}
