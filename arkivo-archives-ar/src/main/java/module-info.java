// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides AR archive streaming APIs for Arkivo.
module org.glavo.arkivo.archives.ar {
    requires org.glavo.arkivo.base;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.ar;

    provides org.glavo.arkivo.ArkivoFormat with org.glavo.arkivo.ar.ArArkivoFormat;
}
