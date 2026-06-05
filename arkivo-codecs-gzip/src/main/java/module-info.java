// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides gzip compression support for Arkivo.
module org.glavo.arkivo.codecs.gzip {
    requires org.glavo.arkivo.base;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.compress.gzip;

    provides org.glavo.arkivo.compress.CompressionCodec with org.glavo.arkivo.compress.gzip.GzipCodec;
}
