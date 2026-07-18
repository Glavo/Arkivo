// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides Unix compress (`.Z`) support for Arkivo.
@SuppressWarnings("module")
module org.glavo.arkivo.codec.compress {
    requires transitive org.glavo.arkivo.codec;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.codec.compress;

    provides org.glavo.arkivo.codec.CompressionFormat with
            org.glavo.arkivo.codec.compress.UnixCompressFormat;
}
