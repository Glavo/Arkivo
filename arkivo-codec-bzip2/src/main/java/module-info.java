// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides BZip2 compression support for Arkivo.
@SuppressWarnings("module")
module org.glavo.arkivo.codec.bzip2 {
    requires org.glavo.arkivo.codec;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.codec.bzip2;
    exports org.glavo.arkivo.codec.bzip2.internal to
            org.glavo.arkivo.archive.sevenzip,
            org.glavo.arkivo.archive.zip;

    provides org.glavo.arkivo.codec.CompressionCodec with org.glavo.arkivo.codec.bzip2.BZip2Codec;
}
