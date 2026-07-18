// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Defines archive discovery, random-access file systems, forward-only readers and writers, multi-volume I/O, and
/// service-provider contracts.
///
/// Archive formats are discovered as [org.glavo.arkivo.archive.ArkivoFormat] services. Optional outer transformations
/// for forward-only input are discovered as
/// [org.glavo.arkivo.archive.spi.ArkivoStreamingSourceProvider] services. Applications normally start with
/// [org.glavo.arkivo.archive.ArkivoFormats] or an explicitly selected format capability.
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
