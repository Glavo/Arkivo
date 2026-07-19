// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Defines archive discovery, random-access file systems, forward-only readers and writers, and multi-volume I/O.
///
/// [org.glavo.arkivo.archive.ArkivoFormats] discovers the fixed set of official format implementations present in the
/// runtime image. Applications may instead select an explicit format capability.
@SuppressWarnings("module")
module org.glavo.arkivo.archive {
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.archive;
    exports org.glavo.arkivo.archive.internal to
            org.glavo.arkivo.all,
            org.glavo.arkivo.archive.codec,
            org.glavo.arkivo.archive.ar,
            org.glavo.arkivo.archive.cpio,
            org.glavo.arkivo.archive.rar,
            org.glavo.arkivo.archive.sevenzip,
            org.glavo.arkivo.archive.tar,
            org.glavo.arkivo.archive.zip;
}
