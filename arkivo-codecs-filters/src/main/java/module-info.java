// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides executable-code and delta filters shared by compression formats.
@SuppressWarnings("module")
module org.glavo.arkivo.codecs.filters {
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.compress.filter to
            org.glavo.arkivo.archives.sevenzip,
            org.glavo.arkivo.codecs.xz;
}
