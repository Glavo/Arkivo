// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides BZip2 stream compression and format discovery.
///
/// The exported [org.glavo.arkivo.codec.bzip2] package contains immutable codec configurations and registers
/// [org.glavo.arkivo.codec.bzip2.BZip2Format] as a compression-format service provider.
@SuppressWarnings("module")
module org.glavo.arkivo.codec.bzip2 {
    requires transitive org.glavo.arkivo.codec;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.codec.bzip2;

    provides org.glavo.arkivo.codec.CompressionFormat with
            org.glavo.arkivo.codec.bzip2.BZip2Format;
}
