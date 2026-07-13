// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides raw deflate compression support for Arkivo.
module org.glavo.arkivo.codec.deflate {
    requires transitive org.glavo.arkivo.codec;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.codec.deflate;

    provides org.glavo.arkivo.codec.CompressionCodec with org.glavo.arkivo.codec.deflate.DeflateCodec;
}
