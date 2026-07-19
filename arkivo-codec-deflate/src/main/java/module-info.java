// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides the pure Java Deflate format family.
///
/// The exported [org.glavo.arkivo.codec.deflate] package covers raw Deflate, raw Deflate64, zlib streams, and gzip
/// members.
module org.glavo.arkivo.codec.deflate {
    requires transitive org.glavo.arkivo.codec;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.codec.deflate;
}
