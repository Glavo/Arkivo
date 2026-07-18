// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides internal low-level primitives shared by Arkivo modules.
module org.glavo.arkivo.base {
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.internal to
            org.glavo.arkivo.archive.rar,
            org.glavo.arkivo.archive.sevenzip,
            org.glavo.arkivo.archive.zip,
            org.glavo.arkivo.codec.xz,
            org.glavo.arkivo.codec.zstd;
}