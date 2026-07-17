// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;

/// Describes one discoverable compression format independently of a configured codec.
///
/// Implementations are immutable identities safe for concurrent use and may be supplied through service discovery.
/// Encoding and decoding policy belongs to immutable `CompressionCodec` values derived from the format's default
/// codec.
@NotNullByDefault
public interface CompressionFormat {
    /// Returns the stable format name.
    String name();

    /// Returns alternative stable names accepted for this format.
    default @Unmodifiable List<String> aliases() {
        return List.of();
    }

    /// Returns common file extensions for streams using this format, without leading dots.
    default @Unmodifiable List<String> fileExtensions() {
        return List.of(name());
    }

    /// Returns the preferred number of leading bytes requested by generic format detection.
    ///
    /// A format may recognize a prefix containing fewer bytes. The returned value must not be negative.
    default int probeSize() {
        return 0;
    }

    /// Returns whether the given byte prefix matches this format's stream signature.
    ///
    /// The supplied buffer is not modified. The returned value is false when the format has no reliable fixed
    /// signature or the prefix is too short.
    default boolean matches(ByteBuffer prefix) {
        Objects.requireNonNull(prefix, "prefix");
        return false;
    }

    /// Returns the immutable default codec configuration for this format.
    CompressionCodec defaultCodec();
}
