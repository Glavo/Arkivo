// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides XZ compression support for Arkivo.
module org.glavo.arkivo.codec.xz {
    requires transitive org.glavo.arkivo.codec;
    requires org.glavo.arkivo.codec.bcj;
    requires org.glavo.arkivo.codec.delta;
    requires org.glavo.arkivo.codec.lzma;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.codec.xz;

    provides org.glavo.arkivo.codec.spi.CompressionCodecProvider with
            org.glavo.arkivo.codec.xz.internal.XZCodecProvider;
}
