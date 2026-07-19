// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Defines transport-independent compression engines, blocking channel and stream adapters, indexed random-access
/// compression, decompression safety limits, and stateful byte transforms.
///
/// [org.glavo.arkivo.codec.CompressionFormats] discovers the fixed set of official format implementations present in
/// the runtime image. Applications may instead use an explicitly selected
/// [org.glavo.arkivo.codec.CompressionFormat], or an immutable [org.glavo.arkivo.codec.CompressionCodec].
@SuppressWarnings("module")
module org.glavo.arkivo.codec {
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.codec;
    exports org.glavo.arkivo.codec.spi to
            org.glavo.arkivo.codec.bzip2,
            org.glavo.arkivo.codec.compress,
            org.glavo.arkivo.codec.deflate,
            org.glavo.arkivo.codec.lz4,
            org.glavo.arkivo.codec.lzip,
            org.glavo.arkivo.codec.lzma,
            org.glavo.arkivo.codec.ppmd,
            org.glavo.arkivo.codec.xz,
            org.glavo.arkivo.codec.zstd;
    exports org.glavo.arkivo.codec.transform;
}
