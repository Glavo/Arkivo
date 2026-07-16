// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides BZip2 compression support for Arkivo.
@SuppressWarnings("module")
module org.glavo.arkivo.codec.bzip2 {
    requires transitive org.glavo.arkivo.codec;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.codec.bzip2;

    provides org.glavo.arkivo.codec.CompressionFormat with
            org.glavo.arkivo.codec.bzip2.BZip2Format;
}
