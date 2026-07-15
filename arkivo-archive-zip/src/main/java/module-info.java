// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides ZIP archive APIs for Arkivo.
module org.glavo.arkivo.archive.zip {
    requires transitive org.glavo.arkivo.archive;
    requires org.glavo.arkivo.base;
    requires org.glavo.arkivo.codec;
    requires org.glavo.arkivo.codec.deflate;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.archive.zip;

    provides org.glavo.arkivo.archive.ArkivoFormat with org.glavo.arkivo.archive.zip.ZipArkivoFormat;
    provides java.nio.file.spi.FileSystemProvider with org.glavo.arkivo.archive.zip.ZipArkivoFileSystemProvider;
}
