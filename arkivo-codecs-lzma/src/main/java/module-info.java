// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides LZMA compression support for Arkivo.
module org.glavo.arkivo.codecs.lzma {
    requires org.glavo.arkivo.base;
    requires org.tukaani.xz;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.lzma;

    provides org.glavo.arkivo.compress.CompressionCodec with org.glavo.arkivo.lzma.LzmaCodec;
}
