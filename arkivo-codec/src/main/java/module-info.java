// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Defines the general Arkivo byte-codec API and service-provider contracts.
@SuppressWarnings("module")
module org.glavo.arkivo.codec {
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.codec;
    exports org.glavo.arkivo.codec.internal.deflate to
            org.glavo.arkivo.codec.deflate,
            org.glavo.arkivo.codec.deflate64,
            org.glavo.arkivo.codec.gzip,
            org.glavo.arkivo.codec.zlib;
    exports org.glavo.arkivo.codec.spi;
    exports org.glavo.arkivo.codec.transform;

    uses org.glavo.arkivo.codec.CompressionCodec;
}
