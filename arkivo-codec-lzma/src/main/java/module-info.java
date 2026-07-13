// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides LZMA compression support for Arkivo.
@SuppressWarnings("module")
module org.glavo.arkivo.codec.lzma {
    requires transitive org.glavo.arkivo.codec;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.codec.lzma;
    exports org.glavo.arkivo.codec.lzma.internal to
            org.glavo.arkivo.archive.sevenzip,
            org.glavo.arkivo.archive.zip,
            org.glavo.arkivo.codec.xz;

    provides org.glavo.arkivo.codec.CompressionCodec with org.glavo.arkivo.codec.lzma.LZMACodec;
}
