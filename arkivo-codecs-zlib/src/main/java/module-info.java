// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides zlib compression support for Arkivo.
module org.glavo.arkivo.codecs.zlib {
    requires org.glavo.arkivo.base;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.compress.zlib;

    provides org.glavo.arkivo.compress.CompressionCodec with org.glavo.arkivo.compress.zlib.ZlibCodec;
}
