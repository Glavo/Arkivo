// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides seeded XXH32 and XXH64 checksum computations.
///
/// Static factories create independent accumulators whose configured seeds are retained across resets.
module org.glavo.arkivo.checksum.xxhash {
    requires transitive org.glavo.arkivo.checksum;
    requires org.glavo.arkivo.base;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.checksum.xxhash;
}
