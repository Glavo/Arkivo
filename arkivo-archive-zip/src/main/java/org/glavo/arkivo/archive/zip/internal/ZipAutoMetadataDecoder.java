// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.ArchiveMetadataDecoder;
import org.glavo.arkivo.archive.zip.ZipLegacyMetadataDecoder;
import org.glavo.arkivo.encoding.detector.internal.EncodingDetector;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/// Infers and decodes one undeclared ZIP entry name independently of previous calls.
/// Explicit Unicode declarations are handled by the reader before this strategy is invoked.
@NotNullByDefault
public final class ZipAutoMetadataDecoder implements ArchiveMetadataDecoder {
    /// Stateless default for direct and forward-only decoding.
    public static final ZipAutoMetadataDecoder DEFAULT = new ZipAutoMetadataDecoder();

    /// ZIP fallback for absent, contradictory, or indistinguishable evidence.
    static final Charset CP437 = Charset.forName("IBM437");

    /// Creates the shared single-field decoder without mutable decoding state.
    private ZipAutoMetadataDecoder() {
    }

    @Override
    public String decode(byte @Unmodifiable [] bytes) throws IOException {
        return decode(bytes, ZipLegacyMetadataDecoder.UNKNOWN_HEADER_VALUE,
                ZipLegacyMetadataDecoder.UNKNOWN_HEADER_VALUE, ZipLegacyMetadataDecoder.HeaderSource.UNKNOWN);
    }

    @Override
    public String decode(@UnmodifiableView ByteBuffer bytes) throws IOException {
        if (EncodingDetector.isAscii(bytes)) return StandardCharsets.US_ASCII.decode(bytes.duplicate()).toString();
        return decode(bytes, ZipLegacyMetadataDecoder.UNKNOWN_HEADER_VALUE,
                ZipLegacyMetadataDecoder.UNKNOWN_HEADER_VALUE, ZipLegacyMetadataDecoder.HeaderSource.UNKNOWN);
    }

    /// Decodes reader-owned bytes without retaining them or constructing a public context.
    String decode(byte[] bytes, int madeBy, long attributes, ZipLegacyMetadataDecoder.HeaderSource source)
            throws IOException {
        @Nullable String ascii = decodeAscii(bytes);
        return ascii != null ? ascii : decode(ByteBuffer.wrap(bytes), madeBy, attributes, source);
    }

    /// Returns the ASCII text without buffer or decoder allocation, or null if any byte is non-ASCII.
    static @Nullable String decodeAscii(byte[] bytes) {
        for (byte value : bytes) {
            if (value < 0) return null;
        }
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    /// Applies independent name inference with creator hints and CP437 fallback.
    private String decode(ByteBuffer bytes, int madeBy, long attributes, ZipLegacyMetadataDecoder.HeaderSource source)
            throws IOException {
        @Nullable String decoded = new EncodingDetector().decode(bytes, EncodingDetector.Kind.NAME,
                ZipLegacyCodePages.preference(madeBy, attributes, source));
        return decoded != null ? decoded : CP437.newDecoder().decode(bytes.duplicate()).toString();
    }
}
