// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides RAR archive streaming APIs for Arkivo.
module org.glavo.arkivo.archive.rar {
    requires transitive org.glavo.arkivo.archive;
    requires org.glavo.arkivo.base;
    requires org.glavo.arkivo.codec.ppmd;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.archive.rar;

    provides org.glavo.arkivo.archive.ArkivoFormat with org.glavo.arkivo.archive.rar.RarArkivoFormat;
    provides java.nio.file.spi.FileSystemProvider with org.glavo.arkivo.archive.rar.internal.RarArkivoFileSystemProvider;
}
