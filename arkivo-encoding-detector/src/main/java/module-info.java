// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides internal archive metadata encoding analysis without archive-format dependencies.
module org.glavo.arkivo.encoding.detector {
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.encoding.detector.internal to org.glavo.arkivo.archive.zip;
}
