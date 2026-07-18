// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides CPIO archive streaming APIs for Arkivo.
module org.glavo.arkivo.archive.cpio {
    requires transitive org.glavo.arkivo.archive;
    requires org.glavo.arkivo.base;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.archive.cpio;

    provides org.glavo.arkivo.archive.ArkivoFormat with org.glavo.arkivo.archive.cpio.CPIOArkivoFormat;
}
