// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides RAR archive streaming APIs for Arkivo.
module org.glavo.arkivo.archives.rar {
    requires org.glavo.arkivo.base;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.rar;

    provides org.glavo.arkivo.ArkivoFormat with org.glavo.arkivo.rar.RarArkivoFormat;
}
