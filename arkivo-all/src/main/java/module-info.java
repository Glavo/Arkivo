// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Aggregates all Arkivo format modules.
module org.glavo.arkivo.all {
    requires static org.jetbrains.annotations;

    requires transitive org.glavo.arkivo.archive;
    requires transitive org.glavo.arkivo.archive.all;
    requires transitive org.glavo.arkivo.codec.all;

    provides org.glavo.arkivo.archive.spi.ArkivoStreamingSourceProvider
            with org.glavo.arkivo.all.internal.CompressionStreamingSourceProvider;
}
