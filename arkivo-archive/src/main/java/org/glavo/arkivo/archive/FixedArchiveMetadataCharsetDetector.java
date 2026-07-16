// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Objects;

/// Selects one fixed charset for ambiguous archive metadata.
///
/// @param charset the selected charset
@NotNullByDefault
record FixedArchiveMetadataCharsetDetector(Charset charset) implements ArchiveMetadataCharsetDetector {
    /// Creates a fixed archive metadata charset detector.
    FixedArchiveMetadataCharsetDetector {
        Objects.requireNonNull(charset, "charset");
    }

    /// Returns the fixed charset without inspecting the supplied buffer.
    @Override
    public Charset detect(@UnmodifiableView ByteBuffer bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return charset;
    }

    /// Returns the fixed charset without wrapping or inspecting the supplied array.
    @Override
    public Charset detect(byte @Unmodifiable [] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return charset;
    }
}
