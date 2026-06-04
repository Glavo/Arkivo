// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides raw deflate compression support for Arkivo.
module org.glavo.arkivo.codecs.deflate {
    requires org.glavo.arkivo.base;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.deflate;

    provides org.glavo.arkivo.compress.CompressionCodec with org.glavo.arkivo.deflate.DeflateCodec;
}
