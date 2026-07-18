// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides XZ compression support for Arkivo.
module org.glavo.arkivo.codec.xz {
    requires transitive org.glavo.arkivo.codec;
    requires org.glavo.arkivo.base;
    requires transitive org.glavo.arkivo.codec.lzma;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.codec.xz;
    exports org.glavo.arkivo.codec.xz.internal.filter to
            org.glavo.arkivo.archive.sevenzip;

    provides org.glavo.arkivo.codec.CompressionFormat with
            org.glavo.arkivo.codec.xz.XZFormat;
}
