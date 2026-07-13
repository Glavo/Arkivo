// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides zlib compression support for Arkivo.
module org.glavo.arkivo.codec.zlib {
    requires transitive org.glavo.arkivo.codec;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.codec.zlib;

    provides org.glavo.arkivo.codec.CompressionCodec with org.glavo.arkivo.codec.zlib.ZlibCodec;
}
