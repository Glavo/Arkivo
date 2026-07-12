// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides 7z archive APIs for Arkivo.
module org.glavo.arkivo.archive.sevenzip {
    requires org.glavo.arkivo.archive;
    requires org.glavo.arkivo.codec.bcj;
    requires org.glavo.arkivo.codec.delta;
    requires static org.glavo.arkivo.codec.bzip2;
    requires static org.glavo.arkivo.codec.deflate64;
    requires static org.glavo.arkivo.codec.lzma;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.archive.sevenzip;

    provides org.glavo.arkivo.archive.ArkivoFormat with org.glavo.arkivo.archive.sevenzip.SevenZipArkivoFormat;
}
