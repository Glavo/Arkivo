// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/// Discovers archive formats provided by Arkivo format modules.
@NotNullByDefault
public final class ArkivoFormats {
    /// Creates no instances.
    private ArkivoFormats() {
    }

    /// Returns all archive formats visible to the current class loader.
    public static @Unmodifiable List<ArkivoFormat> installed() {
        ArrayList<ArkivoFormat> formats = new ArrayList<>();
        for (ArkivoFormat format : ServiceLoader.load(ArkivoFormat.class)) {
            formats.add(format);
        }
        return List.copyOf(formats);
    }

    /// Returns the first archive format with the given stable name, ignoring ASCII case.
    public static @Nullable ArkivoFormat find(String name) {
        for (ArkivoFormat format : ServiceLoader.load(ArkivoFormat.class)) {
            if (format.name().equalsIgnoreCase(name)) {
                return format;
            }
        }
        return null;
    }
}
