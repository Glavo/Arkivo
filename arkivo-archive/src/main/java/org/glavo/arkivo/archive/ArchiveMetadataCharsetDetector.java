// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Objects;

/// Selects a charset for archive metadata whose encoding is not authoritatively identified by its format.
///
/// The basic contract deliberately depends only on the undecoded bytes. Format-specific subinterfaces can expose
/// richer metadata when it is available without requiring every archive reader to collect it. Implementations may be
/// shared by concurrent readers and therefore should be thread-safe.
///
/// A supplied array or buffer is valid only for the duration of the call and must not be modified or retained.
/// Returning `null` reports that the charset could not be determined and asks the archive format to use its fallback.
@FunctionalInterface
@NotNullByDefault
public interface ArchiveMetadataCharsetDetector {
    /// Detects the charset of one complete metadata value, or returns `null` when it is unknown.
    @Nullable Charset detect(@UnmodifiableView ByteBuffer bytes) throws IOException;

    /// Detects the charset of one complete metadata value represented by an array.
    ///
    /// The default implementation exposes the array through an independent read-only buffer and delegates to
    /// `detect(ByteBuffer)`.
    default @Nullable Charset detect(byte @Unmodifiable [] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        return detect(ByteBuffer.wrap(bytes).asReadOnlyBuffer());
    }

    /// Returns a detector that always selects the given charset without inspecting metadata bytes.
    static ArchiveMetadataCharsetDetector fixed(Charset charset) {
        return new FixedArchiveMetadataCharsetDetector(charset);
    }
}
