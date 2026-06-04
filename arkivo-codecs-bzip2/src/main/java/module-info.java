// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides BZip2 compression support for Arkivo.
module org.glavo.arkivo.codecs.bzip {
    requires org.glavo.arkivo.base;
    requires org.apache.commons.compress;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.bzip2;

    provides org.glavo.arkivo.compress.CompressionCodec with org.glavo.arkivo.bzip2.Bzip2Codec;
}
