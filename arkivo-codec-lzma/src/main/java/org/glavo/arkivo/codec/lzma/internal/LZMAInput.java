// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;

/// Supplies bytes to the LZMA range decoder with optional transactional positioning.
@NotNullByDefault
interface LZMAInput {
    /// Reads one unsigned byte or returns `-1` at physical end of input.
    int read() throws IOException;

    /// Returns whether this input can roll back one speculative LZMA symbol.
    default boolean transactional() {
        return false;
    }

    /// Begins one speculative symbol read.
    default void beginTransaction() {
    }

    /// Commits bytes consumed by the active speculative symbol.
    default void commitTransaction() {
    }

    /// Restores the position preceding the active speculative symbol.
    default void rollbackTransaction() {
    }
}
