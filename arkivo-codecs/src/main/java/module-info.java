// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Aggregates Arkivo compression codec modules.
module org.glavo.arkivo.codecs {
    requires transitive org.glavo.arkivo.base;
    requires transitive org.glavo.arkivo.codecs.bzip2;
    requires transitive org.glavo.arkivo.codecs.deflate;
    requires transitive org.glavo.arkivo.codecs.gzip;
    requires transitive org.glavo.arkivo.codecs.lzma;
    requires transitive org.glavo.arkivo.codecs.xz;
    requires transitive org.glavo.arkivo.codecs.zlib;
    requires transitive org.glavo.arkivo.codecs.zstd;
}
