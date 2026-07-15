// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides TAR archive streaming APIs for Arkivo.
module org.glavo.arkivo.archive.tar {
    requires transitive org.glavo.arkivo.archive;
    requires transitive org.glavo.arkivo.codec;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.archive.tar;

    provides org.glavo.arkivo.archive.ArkivoFormat with org.glavo.arkivo.archive.tar.TarArkivoFormat;
    provides java.nio.file.spi.FileSystemProvider with org.glavo.arkivo.archive.tar.TarArkivoFileSystemProvider;
}
