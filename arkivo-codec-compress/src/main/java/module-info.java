// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides Unix compress (`.Z`) stream compression.
///
/// The exported [org.glavo.arkivo.codec.compress] package contains immutable codec and format values.
@SuppressWarnings("module")
module org.glavo.arkivo.codec.compress {
    requires transitive org.glavo.arkivo.codec;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.codec.compress;
}
