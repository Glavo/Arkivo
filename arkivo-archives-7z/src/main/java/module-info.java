// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides 7z archive APIs for Arkivo.
module org.glavo.arkivo.archives.sevenzip {
    requires org.glavo.arkivo.base;
    requires org.tukaani.xz;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.sevenzip;

    provides org.glavo.arkivo.ArkivoFormat with org.glavo.arkivo.sevenzip.SevenZipArkivoFormat;
}
