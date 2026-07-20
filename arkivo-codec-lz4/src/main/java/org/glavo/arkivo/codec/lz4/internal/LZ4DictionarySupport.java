// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4.internal;

import org.glavo.arkivo.checksum.Checksums;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Provides internal dictionary metadata operations shared with the public immutable value type.
@NotNullByDefault
public final class LZ4DictionarySupport {
    /// Creates no instances.
    private LZ4DictionarySupport() {
    }

    /// Returns a zero-seed xxHash-32 identifier for the effective dictionary content.
    ///
    /// @param bytes the effective dictionary suffix
    /// @return the hash as an unsigned 32-bit value
    public static long contentIdentifier(byte[] bytes) {
        return Checksums.XXH32.computeLong(Objects.requireNonNull(bytes, "bytes"));
    }
}
