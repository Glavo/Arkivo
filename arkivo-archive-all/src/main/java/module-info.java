// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Aggregates all official Arkivo archive format modules.
///
/// Requiring this module makes each archive implementation visible to `ArkivoFormat` service discovery. Compression
/// codecs used for transparently compressed archive streams are supplied separately by codec modules.
module org.glavo.arkivo.archive.all {
    requires transitive org.glavo.arkivo.archive;
    requires transitive org.glavo.arkivo.archive.sevenzip;
    requires transitive org.glavo.arkivo.archive.ar;
    requires transitive org.glavo.arkivo.archive.cpio;
    requires transitive org.glavo.arkivo.archive.rar;
    requires transitive org.glavo.arkivo.archive.tar;
    requires transitive org.glavo.arkivo.archive.zip;
}
