// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides LZ4 frame and raw-block compression.
///
/// The exported [org.glavo.arkivo.codec.lz4] package distinguishes self-describing LZ4 frames from headerless raw
/// blocks.
@SuppressWarnings("module")
module org.glavo.arkivo.codec.lz4 {
    requires transitive org.glavo.arkivo.codec;
    requires org.glavo.arkivo.base;
    requires org.glavo.arkivo.checksum;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.codec.lz4;
}
