// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Aggregates all official Arkivo archive and compression modules.
///
/// This module also installs the streaming-source bridge that probes and decodes an outer compression stream before
/// archive format detection. Requiring this module makes every official format implementation visible to service
/// discovery.
module org.glavo.arkivo.all {
    requires static org.jetbrains.annotations;

    requires transitive org.glavo.arkivo.archive;
    requires transitive org.glavo.arkivo.archive.all;
    requires transitive org.glavo.arkivo.codec.all;

    provides org.glavo.arkivo.archive.spi.ArkivoStreamingSourceProvider
            with org.glavo.arkivo.all.internal.CompressionStreamingSourceProvider;
}
