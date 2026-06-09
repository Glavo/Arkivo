// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides TAR archive streaming APIs for Arkivo.
module org.glavo.arkivo.archives.tar {
    requires org.glavo.arkivo.base;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.tar;

    provides org.glavo.arkivo.ArkivoFormat with org.glavo.arkivo.tar.TarArkivoFormat;
}
