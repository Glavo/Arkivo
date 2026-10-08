// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.Objects;

/// Decodes archive text whose encoding is not authoritatively identified by its format.
///
/// Format-specific subinterfaces supply additional metadata when it is available. Readers use their format's default
/// decoder unless another decoder is configured. A configured decoder determines the complete conversion policy,
/// including any fallback or replacement; readers do not retry a failed conversion with another charset.
///
/// Format-defined Unicode fields bypass this decoder. Decoding does not exempt the resulting text from the format's
/// name validation, path normalization, or duplicate-entry checks.
///
/// @implSpec Implementations must support concurrent invocations when shared by concurrent readers. Supplied input
/// is valid only for the duration of the call and must not be modified or retained. A successful invocation must return
/// a non-null string representing the complete supplied value; an empty value may decode to an empty string.
@FunctionalInterface
@NotNullByDefault
public interface ArchiveMetadataDecoder {
    /// Decodes the remaining bytes as one complete metadata value.
    ///
    /// An implementation may change the buffer's position, limit, and mark. Callers that need to preserve these
    /// properties must supply an independent view. The buffer's contents must not be changed or retained.
    ///
    /// @param bytes a read-only view of the complete encoded metadata value
    /// @return the decoded text, never `null`
    /// @throws IOException if the value cannot be decoded according to this decoder's policy
    String decode(@UnmodifiableView ByteBuffer bytes) throws IOException;

    /// Decodes one complete metadata value represented by an array.
    ///
    /// @implSpec The default implementation wraps the array in a read-only buffer with an independent position and
    /// limit, then delegates to [#decode(ByteBuffer)]. The buffer shares the array's contents. A null result is rejected
    /// with `NullPointerException`.
    ///
    /// @param bytes the complete encoded metadata value
    /// @return the decoded text, never `null`
    /// @throws NullPointerException if `bytes` is `null` or the implementation returns `null`
    /// @throws IOException if the value cannot be decoded according to this decoder's policy
    default String decode(byte @Unmodifiable [] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        return Objects.requireNonNull(decode(ByteBuffer.wrap(bytes).asReadOnlyBuffer()), "decoded metadata");
    }

    /// Returns a decoder that strictly decodes metadata with the given charset.
    ///
    /// The returned decoder reports malformed and unmappable input with
    /// [java.nio.charset.CharacterCodingException]. It neither substitutes characters nor tries another charset.
    /// Decoding leaves the supplied buffer's position, limit, and mark unchanged, including on failure.
    /// Each invocation creates its own charset decoder; no mutable decoding state is shared between invocations.
    ///
    /// @param charset the charset used to decode metadata
    /// @return a strict metadata decoder
    /// @throws NullPointerException if `charset` is `null`
    static ArchiveMetadataDecoder forCharset(Charset charset) {
        Charset checkedCharset = Objects.requireNonNull(charset, "charset");
        return bytes -> checkedCharset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(bytes.asReadOnlyBuffer()).toString();
    }
}
