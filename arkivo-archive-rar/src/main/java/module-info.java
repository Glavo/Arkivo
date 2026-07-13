// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides RAR archive streaming APIs for Arkivo.
module org.glavo.arkivo.archive.rar {
    requires transitive org.glavo.arkivo.archive;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.archive.rar;

    provides org.glavo.arkivo.archive.ArkivoFormat with org.glavo.arkivo.archive.rar.RarArkivoFormat;
}
