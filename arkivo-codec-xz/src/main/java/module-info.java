// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides XZ compression support for Arkivo.
@SuppressWarnings("module")
module org.glavo.arkivo.codec.xz {
    requires org.glavo.arkivo.codec;
    requires org.glavo.arkivo.codec.bcj;
    requires org.glavo.arkivo.codec.delta;
    requires org.glavo.arkivo.codec.lzma;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.codec.xz;
    exports org.glavo.arkivo.codec.xz.internal to org.glavo.arkivo.archive.zip;

    provides org.glavo.arkivo.codec.CompressionCodec with org.glavo.arkivo.codec.xz.XZCodec;
}
