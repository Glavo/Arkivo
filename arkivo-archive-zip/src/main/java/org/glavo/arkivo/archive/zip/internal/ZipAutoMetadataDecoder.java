// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.ArchiveMetadataDecoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/// Decodes an undeclared ZIP entry name as strict UTF-8, falling back to CP437 for malformed input.
/// Explicit Unicode declarations are handled by the reader before this strategy is invoked.
@NotNullByDefault
public final class ZipAutoMetadataDecoder implements ArchiveMetadataDecoder {
    /// Stateless default for direct and forward-only decoding.
    public static final ZipAutoMetadataDecoder DEFAULT = new ZipAutoMetadataDecoder();

    /// ZIP fallback when UTF-8 decoding fails or archive-wide inference cannot select a charset.
    static final Charset CP437 = Charset.forName("IBM437");

    /// Creates the shared single-field decoder without mutable decoding state.
    private ZipAutoMetadataDecoder() {
    }

    @Override
    public String decode(byte @Unmodifiable [] bytes) throws IOException {
        @Nullable String ascii = decodeAscii(bytes);
        return ascii != null ? ascii : decode(ByteBuffer.wrap(bytes));
    }

    @Override
    public String decode(@UnmodifiableView ByteBuffer bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(bytes.duplicate()).toString();
        } catch (CharacterCodingException exception) {
            // Retry the complete field, including bytes consumed before the UTF-8 error.
            return CP437.newDecoder().decode(bytes.duplicate()).toString();
        }
    }

    /// Returns the ASCII text without buffer or decoder allocation, or null if any byte is non-ASCII.
    static @Nullable String decodeAscii(byte[] bytes) {
        for (byte value : bytes) {
            if (value < 0) return null;
        }
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }
}
