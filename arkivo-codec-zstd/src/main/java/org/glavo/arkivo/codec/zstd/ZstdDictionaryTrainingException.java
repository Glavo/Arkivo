// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import com.github.luben.zstd.Zstd;
import org.jetbrains.annotations.NotNullByDefault;

/// Reports a native Zstandard dictionary training failure.
@NotNullByDefault
public final class ZstdDictionaryTrainingException extends IllegalStateException {
    /// The native Zstandard error code.
    private final long errorCode;

    /// Creates an exception for a native error code without an implementation cause.
    ZstdDictionaryTrainingException(long errorCode) {
        super("Zstandard dictionary training failed: " + Zstd.getErrorName(errorCode));
        this.errorCode = errorCode;
    }

    /// Creates an exception for a native error code and implementation cause.
    ZstdDictionaryTrainingException(long errorCode, Throwable cause) {
        super("Zstandard dictionary training failed: " + Zstd.getErrorName(errorCode), cause);
        this.errorCode = errorCode;
    }

    /// Returns the native Zstandard error code.
    public long errorCode() {
        return errorCode;
    }
}
