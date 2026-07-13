// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import com.github.luben.zstd.Zstd;
import org.glavo.arkivo.codec.CompressionDictionary;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.ByteBuffer;

/// Provides shared Zstandard dictionary metadata operations.
@NotNullByDefault
public final class ZstdDictionarySupport {
    /// Returns the dictionary identifier without changing the source buffer.
    public static long dictionaryId(ByteBuffer dictionary) {
        ByteBuffer source = dictionary.slice();
        long id;
        if (source.isDirect()) {
            id = Zstd.getDictIdFromDictDirect(source);
        } else {
            byte[] bytes = new byte[source.remaining()];
            source.get(bytes);
            id = Zstd.getDictIdFromDict(bytes);
        }
        return id == 0L ? CompressionDictionary.UNKNOWN_ID : id;
    }

    /// Creates no instances.
    private ZstdDictionarySupport() {
    }
}
