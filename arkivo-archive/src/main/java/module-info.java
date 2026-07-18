// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Defines the shared Arkivo archive API and service-provider contracts.
@SuppressWarnings("module")
module org.glavo.arkivo.archive {
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.archive;
    exports org.glavo.arkivo.archive.spi;
    exports org.glavo.arkivo.archive.internal to
            org.glavo.arkivo.archive.ar,
            org.glavo.arkivo.archive.cpio,
            org.glavo.arkivo.archive.rar,
            org.glavo.arkivo.archive.sevenzip,
            org.glavo.arkivo.archive.tar,
            org.glavo.arkivo.archive.zip;

    uses org.glavo.arkivo.archive.ArkivoFormat;
    uses org.glavo.arkivo.archive.spi.ArkivoStreamingSourceProvider;
}
