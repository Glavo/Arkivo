// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;

/// Describes an archive format supported by Arkivo.
@NotNullByDefault
public interface ArkivoFormat {
    /// Returns the stable format name.
    String name();

    /// Returns alternative stable names accepted for this format.
    default @Unmodifiable List<String> aliases() {
        return List.of();
    }

    /// Returns common file extensions for archives of this format, without leading dots.
    default @Unmodifiable List<String> fileExtensions() {
        return List.of(name());
    }

    /// Returns the preferred number of leading bytes requested by generic format detection.
    ///
    /// A format may recognize a prefix containing fewer bytes. The returned value must not be negative.
    default int probeSize() {
        return 0;
    }

    /// Returns whether the remaining bytes of the given prefix identify this archive format.
    ///
    /// This operation must not change the prefix position, limit, mark, or byte order.
    default boolean matches(ByteBuffer prefix) {
        Objects.requireNonNull(prefix, "prefix");
        return false;
    }
}
