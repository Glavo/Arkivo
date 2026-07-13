// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Aggregates Arkivo compression codec modules.
module org.glavo.arkivo.codec.all {
    requires transitive org.glavo.arkivo.codec;
    requires transitive org.glavo.arkivo.codec.bzip2;
    requires transitive org.glavo.arkivo.codec.deflate;
    requires transitive org.glavo.arkivo.codec.deflate64;
    requires transitive org.glavo.arkivo.codec.bcj;
    requires transitive org.glavo.arkivo.codec.delta;
    requires transitive org.glavo.arkivo.codec.gzip;
    requires transitive org.glavo.arkivo.codec.lzma;
    requires transitive org.glavo.arkivo.codec.ppmd;
    requires transitive org.glavo.arkivo.codec.xz;
    requires transitive org.glavo.arkivo.codec.zlib;
    requires transitive org.glavo.arkivo.codec.zstd;
}
