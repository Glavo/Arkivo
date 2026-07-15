// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Defines the general Arkivo byte-codec API and service-provider contracts.
module org.glavo.arkivo.codec {
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.codec;
    exports org.glavo.arkivo.codec.spi;
    exports org.glavo.arkivo.codec.transform;

    uses org.glavo.arkivo.codec.spi.CompressionCodecProvider;
}
