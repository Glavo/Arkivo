// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/// Reports an Arkivo-specific I/O failure.
@NotNullByDefault
public class ArkivoException extends IOException {
    /// Creates an exception with the given message.
    public ArkivoException(String message) {
        super(message);
    }

    /// Creates an exception with the given message and cause.
    public ArkivoException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
