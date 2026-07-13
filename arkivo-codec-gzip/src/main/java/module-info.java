// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides gzip compression support for Arkivo.
module org.glavo.arkivo.codec.gzip {
    requires transitive org.glavo.arkivo.codec;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.codec.gzip;

    provides org.glavo.arkivo.codec.CompressionCodec with org.glavo.arkivo.codec.gzip.GzipCodec;
}
