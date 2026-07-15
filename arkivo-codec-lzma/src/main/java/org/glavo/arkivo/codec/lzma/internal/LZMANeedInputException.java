// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;

/// Signals an incomplete caller-owned input fragment at an LZMA symbol boundary.
@NotNullByDefault
final class LZMANeedInputException extends IOException {
    /// Serialization identifier.
    private static final long serialVersionUID = 0L;

    /// Shared stackless control-flow signal.
    static final LZMANeedInputException INSTANCE = new LZMANeedInputException();

    /// Creates the shared stackless signal.
    private LZMANeedInputException() {
        super("LZMA buffer input is incomplete");
    }

    /// Avoids rebuilding a stack trace for expected incremental input boundaries.
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
