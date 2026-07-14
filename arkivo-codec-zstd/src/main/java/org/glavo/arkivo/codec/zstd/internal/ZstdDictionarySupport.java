// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.glavo.arkivo.codec.CompressionDictionary;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.ByteBuffer;

/// Provides shared Zstandard dictionary metadata operations.
@NotNullByDefault
public final class ZstdDictionarySupport {
    /// The formatted-dictionary magic in little-endian form.
    private static final long DICTIONARY_MAGIC = 0xec30_a437L;

    /// Returns the dictionary identifier without changing the source buffer.
    public static long dictionaryId(ByteBuffer dictionary) {
        ByteBuffer source = dictionary.slice();
        if (source.remaining() < 8 || readUnsignedInt(source, 0) != DICTIONARY_MAGIC) {
            return CompressionDictionary.UNKNOWN_ID;
        }
        long id = readUnsignedInt(source, 4);
        return id == 0L ? CompressionDictionary.UNKNOWN_ID : id;
    }

    /// Reads one unsigned little-endian 32-bit field.
    private static long readUnsignedInt(ByteBuffer source, int offset) {
        return Integer.toUnsignedLong(
                Byte.toUnsignedInt(source.get(offset))
                        | Byte.toUnsignedInt(source.get(offset + 1)) << 8
                        | Byte.toUnsignedInt(source.get(offset + 2)) << 16
                        | source.get(offset + 3) << 24
        );
    }

    /// Creates no instances.
    private ZstdDictionarySupport() {
    }
}
