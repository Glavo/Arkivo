// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides the pure Java Deflate format family for Arkivo.
module org.glavo.arkivo.codec.deflate {
    requires transitive org.glavo.arkivo.codec;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.codec.deflate;

    provides org.glavo.arkivo.codec.spi.CompressionCodecProvider with
            org.glavo.arkivo.codec.deflate.internal.DeflateCodecProvider,
            org.glavo.arkivo.codec.deflate.internal.Deflate64CodecProvider,
            org.glavo.arkivo.codec.deflate.internal.GzipCodecProvider,
            org.glavo.arkivo.codec.deflate.internal.ZlibCodecProvider;
}
