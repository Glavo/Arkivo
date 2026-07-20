// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides immutable checksum algorithms, incremental accumulators, and checksum values.
///
/// Algorithms are safe for concurrent use and create independent mutable accumulators. Accumulators consume caller
/// buffers without retaining them and expose exact-width primitive results for checksums of up to 64 bits.
module org.glavo.arkivo.checksum {
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.checksum;
}
