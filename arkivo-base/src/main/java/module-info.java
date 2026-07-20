// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides low-level implementation primitives shared by selected Arkivo modules.
///
/// This module has no unqualified exports. Its internal package is available only to the implementation modules named
/// by the qualified export and is not part of Arkivo's application-facing API.
@SuppressWarnings("module")
module org.glavo.arkivo.base {
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.internal to
            org.glavo.arkivo.archive.cpio,
            org.glavo.arkivo.archive.rar,
            org.glavo.arkivo.archive.sevenzip,
            org.glavo.arkivo.archive.zip,
            org.glavo.arkivo.checksum,
            org.glavo.arkivo.codec.lz4,
            org.glavo.arkivo.codec.lzip,
            org.glavo.arkivo.codec.xz,
            org.glavo.arkivo.codec.zstd;
}