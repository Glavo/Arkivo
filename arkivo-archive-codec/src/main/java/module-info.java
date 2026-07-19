// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Integrates Arkivo's archive probing pipeline with installed official compression formats.
module org.glavo.arkivo.archive.codec {
    requires org.glavo.arkivo.archive;
    requires org.glavo.arkivo.codec;
    requires static org.jetbrains.annotations;

    opens org.glavo.arkivo.archive.codec.internal to org.glavo.arkivo.archive;
}
