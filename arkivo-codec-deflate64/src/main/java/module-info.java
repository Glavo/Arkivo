// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides Deflate64 compression and decompression support for Arkivo.
@SuppressWarnings("module")
module org.glavo.arkivo.codec.deflate64 {
    requires transitive org.glavo.arkivo.codec;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.codec.deflate64;

    provides org.glavo.arkivo.codec.CompressionCodec with
            org.glavo.arkivo.codec.deflate64.Deflate64Codec;
}
