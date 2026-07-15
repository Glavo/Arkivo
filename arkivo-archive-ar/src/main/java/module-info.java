// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides AR archive streaming APIs for Arkivo.
module org.glavo.arkivo.archive.ar {
    requires transitive org.glavo.arkivo.archive;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.archive.ar;

    provides org.glavo.arkivo.archive.ArkivoFormat with org.glavo.arkivo.archive.ar.ArArkivoFormat;
    provides java.nio.file.spi.FileSystemProvider with org.glavo.arkivo.archive.ar.ArArkivoFileSystemProvider;
}
