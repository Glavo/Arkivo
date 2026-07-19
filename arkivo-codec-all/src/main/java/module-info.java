// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Aggregates all official Arkivo compression codec modules.
///
/// Requiring this module resolves each codec implementation for the built-in
/// [org.glavo.arkivo.codec.CompressionFormat] catalog while exposing the shared codec API transitively.
module org.glavo.arkivo.codec.all {
    requires transitive org.glavo.arkivo.codec;
    requires transitive org.glavo.arkivo.codec.bzip2;
    requires transitive org.glavo.arkivo.codec.compress;
    requires transitive org.glavo.arkivo.codec.deflate;
    requires transitive org.glavo.arkivo.codec.lz4;
    requires transitive org.glavo.arkivo.codec.lzip;
    requires transitive org.glavo.arkivo.codec.lzma;
    requires transitive org.glavo.arkivo.codec.ppmd;
    requires transitive org.glavo.arkivo.codec.xz;
    requires transitive org.glavo.arkivo.codec.zstd;
}
