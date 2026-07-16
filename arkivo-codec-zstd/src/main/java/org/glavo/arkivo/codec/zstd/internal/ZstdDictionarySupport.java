// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.glavo.arkivo.codec.zstd.ZstdDictionary;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.ByteBuffer;

/// Provides shared Zstandard dictionary metadata operations.
@NotNullByDefault
public final class ZstdDictionarySupport {
    /// Returns the dictionary identifier without changing the source buffer.
    public static long dictionaryId(ByteBuffer dictionary) {
        ByteBuffer source = dictionary.slice();
        if (source.remaining() < 8
                || readUnsignedInt(source, 0) != ZstdDictionary.FORMATTED_DICTIONARY_MAGIC) {
            return ZstdDictionary.NO_DICTIONARY_ID;
        }
        long id = readUnsignedInt(source, 4);
        return id == 0L ? ZstdDictionary.NO_DICTIONARY_ID : id;
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
