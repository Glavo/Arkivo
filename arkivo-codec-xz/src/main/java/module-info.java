// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides XZ stream compression and preprocessing filters.
///
/// The exported [org.glavo.arkivo.codec.xz] package describes complete XZ streams, block checks, and the size-preserving
/// BCJ and Delta filters that may precede the terminal LZMA2 filter.
@SuppressWarnings("module")
module org.glavo.arkivo.codec.xz {
    requires transitive org.glavo.arkivo.codec;
    requires org.glavo.arkivo.base;
    requires org.glavo.arkivo.checksum;
    requires transitive org.glavo.arkivo.codec.lzma;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.codec.xz;
    exports org.glavo.arkivo.codec.xz.internal.filter to
            org.glavo.arkivo.archive.sevenzip;
}
