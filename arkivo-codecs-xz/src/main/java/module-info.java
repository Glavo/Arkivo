// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides XZ compression support for Arkivo.
@SuppressWarnings("module")
module org.glavo.arkivo.codecs.xz {
    requires org.glavo.arkivo.base;
    requires org.glavo.arkivo.codecs.filters;
    requires org.glavo.arkivo.codecs.lzma;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.compress.xz;
    exports org.glavo.arkivo.compress.xz.internal to org.glavo.arkivo.archives.zip;

    provides org.glavo.arkivo.compress.CompressionCodec with org.glavo.arkivo.compress.xz.XZCodec;
}
