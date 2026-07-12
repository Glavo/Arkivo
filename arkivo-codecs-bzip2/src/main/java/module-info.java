// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides BZip2 compression support for Arkivo.
module org.glavo.arkivo.codecs.bzip2 {
    requires org.glavo.arkivo.base;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.compress.bzip2;

    provides org.glavo.arkivo.compress.CompressionCodec with org.glavo.arkivo.compress.bzip2.BZip2Codec;
}
