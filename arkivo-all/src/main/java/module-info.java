// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Aggregates all official Arkivo archive, checksum, and compression modules.
///
/// Requiring this module resolves every official archive, compression, and archive-codec integration implementation
/// used by Arkivo's built-in catalogs and layered probing pipeline.
module org.glavo.arkivo.all {
    requires static org.jetbrains.annotations;

    requires transitive org.glavo.arkivo.archive;
    requires transitive org.glavo.arkivo.archive.all;
    requires transitive org.glavo.arkivo.checksum;
    requires transitive org.glavo.arkivo.checksum.xxhash;
    requires transitive org.glavo.arkivo.codec.all;
    requires org.glavo.arkivo.archive.codec;
}
