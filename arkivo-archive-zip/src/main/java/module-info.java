// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides ZIP archive APIs for Arkivo.
module org.glavo.arkivo.archive.zip {
    requires transitive org.glavo.arkivo.archive;
    requires org.glavo.arkivo.codec;
    requires static org.glavo.arkivo.codec.lzma;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.archive.zip;

    provides org.glavo.arkivo.archive.ArkivoFormat with org.glavo.arkivo.archive.zip.ZipArkivoFormat;
}
