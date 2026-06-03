// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.compress;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/// Discovers compression codecs provided by Arkivo codec modules.
@NotNullByDefault
public final class CompressionCodecs {
    /// Creates no instances.
    private CompressionCodecs() {
    }

    /// Returns all compression codecs visible to the current class loader.
    public static @Unmodifiable List<CompressionCodec> installed() {
        ArrayList<CompressionCodec> codecs = new ArrayList<>();
        for (CompressionCodec codec : ServiceLoader.load(CompressionCodec.class)) {
            codecs.add(codec);
        }
        return List.copyOf(codecs);
    }

    /// Returns the first compression codec with the given stable name, ignoring ASCII case.
    public static @Nullable CompressionCodec find(String name) {
        for (CompressionCodec codec : ServiceLoader.load(CompressionCodec.class)) {
            if (codec.name().equalsIgnoreCase(name)) {
                return codec;
            }
        }
        return null;
    }
}
