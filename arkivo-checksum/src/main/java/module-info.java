// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides incremental checksum accumulators and immutable checksum values.
///
/// Accumulators consume caller buffers without retaining them and expose exact-width
/// primitive results for checksums of up to 64 bits.
module org.glavo.arkivo.checksum {
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.checksum;
}
