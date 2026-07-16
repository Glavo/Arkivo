// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import org.jetbrains.annotations.NotNullByDefault;

/// Reports a Zstandard dictionary training failure.
@NotNullByDefault
public final class ZstdDictionaryTrainingException extends IllegalStateException {
    /// The serialization version for this exception type.
    private static final long serialVersionUID = 0L;

    /// Error code indicating that the requested dictionary capacity is too small.
    static final long DESTINATION_TOO_SMALL = 1L;

    /// Error code indicating that too little sample data was supplied.
    static final long INSUFFICIENT_SAMPLES = 2L;

    /// Error code indicating that a formatted dictionary could not be constructed.
    static final long DICTIONARY_CREATION_FAILED = 3L;

    /// Stable Zstandard dictionary training error code.
    private final long errorCode;

    /// Creates an exception for a stable error code and reason.
    ZstdDictionaryTrainingException(long errorCode, String reason) {
        super("Zstandard dictionary training failed: " + reason);
        this.errorCode = errorCode;
    }

    /// Creates an exception for a stable error code, reason, and implementation cause.
    ZstdDictionaryTrainingException(long errorCode, String reason, Throwable cause) {
        super("Zstandard dictionary training failed: " + reason, cause);
        this.errorCode = errorCode;
    }

    /// Returns the stable dictionary training error code.
    public long errorCode() {
        return errorCode;
    }
}
