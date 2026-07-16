// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides 7z archive APIs for Arkivo.
module org.glavo.arkivo.archive.sevenzip {
    requires transitive org.glavo.arkivo.archive;
    requires org.glavo.arkivo.base;
    requires org.glavo.arkivo.codec;
    requires org.glavo.arkivo.codec.bcj;
    requires org.glavo.arkivo.codec.delta;
    requires static org.glavo.arkivo.codec.lzma;
    requires static org.glavo.arkivo.codec.ppmd;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.archive.sevenzip;

    provides org.glavo.arkivo.archive.ArkivoFormat with org.glavo.arkivo.archive.sevenzip.SevenZipArkivoFormat;
    provides java.nio.file.spi.FileSystemProvider with
            org.glavo.arkivo.archive.sevenzip.internal.SevenZipArkivoFileSystemProvider;
}
