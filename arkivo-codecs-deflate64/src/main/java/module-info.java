// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides Deflate64 decompression support for Arkivo.
@SuppressWarnings("module")
module org.glavo.arkivo.codecs.deflate64 {
    requires org.glavo.arkivo.base;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.compress.deflate64;
    exports org.glavo.arkivo.compress.deflate64.internal to
            org.glavo.arkivo.archives.sevenzip,
            org.glavo.arkivo.archives.zip;

    provides org.glavo.arkivo.compress.CompressionCodec with
            org.glavo.arkivo.compress.deflate64.Deflate64Codec;
}
