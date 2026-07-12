// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides Zstandard compression support for Arkivo.
module org.glavo.arkivo.codec.zstd {
    requires org.glavo.arkivo.codec;
    requires com.github.luben.zstd_jni;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.codec.zstd;

    provides org.glavo.arkivo.codec.CompressionCodec with org.glavo.arkivo.codec.zstd.ZstdCodec;
}
