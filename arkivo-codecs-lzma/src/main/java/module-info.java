// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides LZMA compression support for Arkivo.
@SuppressWarnings("module")
module org.glavo.arkivo.codecs.lzma {
    requires org.glavo.arkivo.base;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.compress.lzma;
    exports org.glavo.arkivo.compress.lzma.internal to
            org.glavo.arkivo.archives.sevenzip,
            org.glavo.arkivo.archives.zip,
            org.glavo.arkivo.codecs.xz;

    provides org.glavo.arkivo.compress.CompressionCodec with org.glavo.arkivo.compress.lzma.LZMACodec;
}
