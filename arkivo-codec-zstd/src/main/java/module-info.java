// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides Zstandard frame compression, seek-table random access, and dictionary training.
///
/// The exported [org.glavo.arkivo.codec.zstd] package supports standard and explicitly selected magicless frames,
/// dictionary metadata, frame inspection, interoperable seekable encodings, and reusable dictionary training.
module org.glavo.arkivo.codec.zstd {
    requires transitive org.glavo.arkivo.codec;
    requires org.glavo.arkivo.base;
    requires org.glavo.arkivo.checksum;
    requires org.glavo.arkivo.checksum.xxhash;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.codec.zstd;
}
