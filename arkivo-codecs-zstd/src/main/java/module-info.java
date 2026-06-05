// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides Zstandard compression support for Arkivo.
module org.glavo.arkivo.codecs.zstd {
    requires org.glavo.arkivo.base;
    requires com.github.luben.zstd_jni;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.compress.zstd;

    provides org.glavo.arkivo.compress.CompressionCodec with org.glavo.arkivo.compress.zstd.ZstdCodec;
}
