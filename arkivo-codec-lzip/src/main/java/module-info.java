// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides lzip member compression and format discovery.
///
/// The exported [org.glavo.arkivo.codec.lzip] package contains immutable codec configurations and registers
/// [org.glavo.arkivo.codec.lzip.LzipFormat] as a compression-format service provider.
@SuppressWarnings("module")
module org.glavo.arkivo.codec.lzip {
    requires transitive org.glavo.arkivo.codec;
    requires org.glavo.arkivo.base;
    requires org.glavo.arkivo.codec.lzma;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.codec.lzip;

    provides org.glavo.arkivo.codec.CompressionFormat with
            org.glavo.arkivo.codec.lzip.LzipFormat;
}
