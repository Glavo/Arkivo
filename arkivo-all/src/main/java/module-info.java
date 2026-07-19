// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Aggregates all official Arkivo archive and compression modules.
///
/// This module also supplies the optional bridge that probes and decodes an outer compression stream before archive
/// format detection. Requiring this module resolves every official format implementation for Arkivo's built-in
/// catalogs.
module org.glavo.arkivo.all {
    requires static org.jetbrains.annotations;

    requires transitive org.glavo.arkivo.archive;
    requires transitive org.glavo.arkivo.archive.all;
    requires transitive org.glavo.arkivo.codec.all;

    opens org.glavo.arkivo.all.internal to org.glavo.arkivo.archive;
}
