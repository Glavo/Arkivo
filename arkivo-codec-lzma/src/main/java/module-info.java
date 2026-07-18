// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides LZMA-alone, raw LZMA, and raw LZMA2 compression and format discovery.
///
/// The exported [org.glavo.arkivo.codec.lzma] package separates the LZMA-alone container from headerless LZMA payloads
/// whose model properties and boundaries are supplied by an embedding format.
@SuppressWarnings("module")
module org.glavo.arkivo.codec.lzma {
    requires transitive org.glavo.arkivo.codec;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.codec.lzma;
    exports org.glavo.arkivo.codec.lzma.internal to
            org.glavo.arkivo.codec.xz;

    provides org.glavo.arkivo.codec.CompressionFormat with
            org.glavo.arkivo.codec.lzma.LZMAFormat,
            org.glavo.arkivo.codec.lzma.RawLZMAFormat,
            org.glavo.arkivo.codec.lzma.LZMA2Format;
}
