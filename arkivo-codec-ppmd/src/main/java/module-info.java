// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides PPMd compression-model support for Arkivo.
@SuppressWarnings("module")
module org.glavo.arkivo.codec.ppmd {
    requires transitive org.glavo.arkivo.codec;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.codec.ppmd;
    exports org.glavo.arkivo.codec.ppmd.internal to org.glavo.arkivo.archive.rar;

    provides org.glavo.arkivo.codec.CompressionFormat with
            org.glavo.arkivo.codec.ppmd.PPMdFormat;
}
