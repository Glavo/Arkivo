// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides lzip member compression.
///
/// The exported [org.glavo.arkivo.codec.lzip] package contains immutable codec and format values.
@SuppressWarnings("module")
module org.glavo.arkivo.codec.lzip {
    requires transitive org.glavo.arkivo.codec;
    requires org.glavo.arkivo.base;
    requires org.glavo.arkivo.codec.lzma;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.codec.lzip;
}
