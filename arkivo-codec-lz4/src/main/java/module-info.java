// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides LZ4 frame and raw-block compression support for Arkivo.
@SuppressWarnings("module")
module org.glavo.arkivo.codec.lz4 {
    requires transitive org.glavo.arkivo.codec;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.codec.lz4;

    provides org.glavo.arkivo.codec.CompressionFormat with
            org.glavo.arkivo.codec.lz4.LZ4Format,
            org.glavo.arkivo.codec.lz4.LZ4BlockFormat;
}
