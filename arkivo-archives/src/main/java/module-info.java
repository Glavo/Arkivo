// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Aggregates Arkivo archive format modules.
module org.glavo.arkivo.archives {
    requires transitive org.glavo.arkivo.base;
    requires transitive org.glavo.arkivo.archives.sevenzip;
    requires transitive org.glavo.arkivo.archives.tar;
    requires transitive org.glavo.arkivo.archives.zip;
}
