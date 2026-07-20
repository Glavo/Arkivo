// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides configurable XXH32 and XXH64 checksum algorithms.
///
/// Algorithm instances are immutable and safe for concurrent use. Mutable computation state belongs to independent
/// accumulators created by each algorithm.
module org.glavo.arkivo.checksum.xxhash {
    requires transitive org.glavo.arkivo.checksum;
    requires org.glavo.arkivo.base;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.checksum.xxhash;
}
