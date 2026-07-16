// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Defines Arkivo's transport-independent compression and byte-transform APIs.
module org.glavo.arkivo.codec {
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.codec;
    exports org.glavo.arkivo.codec.spi to
            org.glavo.arkivo.codec.bzip2,
            org.glavo.arkivo.codec.deflate,
            org.glavo.arkivo.codec.lzma,
            org.glavo.arkivo.codec.ppmd,
            org.glavo.arkivo.codec.xz,
            org.glavo.arkivo.codec.zstd;
    exports org.glavo.arkivo.codec.transform;

    uses org.glavo.arkivo.codec.CompressionFormat;
}
