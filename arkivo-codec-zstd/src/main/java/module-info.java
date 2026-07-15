// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides Zstandard compression support for Arkivo.
module org.glavo.arkivo.codec.zstd {
    requires transitive org.glavo.arkivo.codec;
    requires org.glavo.arkivo.base;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.codec.zstd;

    provides org.glavo.arkivo.codec.CompressionCodec with org.glavo.arkivo.codec.zstd.ZstdCodec;
}
