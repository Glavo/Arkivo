// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar;

import org.glavo.arkivo.archive.ArchiveMetadataDecoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/// Decodes AR member names using the name representation that supplied the bytes.
///
/// The default AR decoder uses UTF-8. Custom decoders supply the final text without an implicit charset fallback.
@FunctionalInterface
@NotNullByDefault
public interface ArMetadataDecoder extends ArchiveMetadataDecoder {
    /// The sentinel used when the containing member size is unavailable.
    long UNKNOWN_MEMBER_SIZE = -1L;

    /// Decodes one complete AR metadata value.
    ///
    /// The context and its byte-buffer view are valid only for this invocation and must not be retained.
    ///
    /// @param context the raw name bytes and available member-name representation metadata
    /// @return the decoded text, never `null`
    /// @throws IOException if the value cannot be decoded according to this decoder's policy
    String decode(Context context) throws IOException;

    /// Decodes bytes without explicit AR context.
    ///
    /// @implSpec Supplies an unknown context with independent read-only buffer state and rejects a null result.
    @Override
    default String decode(@UnmodifiableView ByteBuffer bytes) throws IOException {
        return Objects.requireNonNull(decode(Context.unknown(bytes)), "decoded metadata");
    }

    /// Identifies the logical AR metadata field being decoded.
    @NotNullByDefault
    enum MetadataKind {
        /// The metadata kind is unavailable.
        UNKNOWN,

        /// An archive member path.
        ENTRY_NAME
    }

    /// Identifies the AR name representation that supplied a value.
    @NotNullByDefault
    enum Source {
        /// The source is unavailable.
        UNKNOWN,

        /// A short name stored directly in the fixed-width header identifier.
        HEADER_IDENTIFIER,

        /// A BSD extended name stored at the beginning of a member body.
        BSD_LONG_NAME,

        /// A name selected from the GNU filename table.
        GNU_NAME_TABLE
    }

    /// Provides raw bytes and available AR member-name metadata to a decoder.
    ///
    /// The byte buffer is an independent read-only view valid only for the duration of the decoder call and must not
    /// be retained.
    ///
    /// @param bytes the complete raw member-name bytes
    /// @param metadataKind the logical field being decoded
    /// @param source the AR name representation that supplied the bytes
    /// @param headerIdentifier the structural ASCII header identifier for an extended name, or `null`
    /// @param memberSize the complete stored member size, or `UNKNOWN_MEMBER_SIZE`
    @NotNullByDefault
    record Context(
            @UnmodifiableView ByteBuffer bytes,
            MetadataKind metadataKind,
            Source source,
            @Nullable String headerIdentifier,
            long memberSize
    ) {
        /// Creates an AR metadata context.
        public Context {
            bytes = Objects.requireNonNull(bytes, "bytes").asReadOnlyBuffer();
            metadataKind = Objects.requireNonNull(metadataKind, "metadataKind");
            source = Objects.requireNonNull(source, "source");
            if (memberSize < UNKNOWN_MEMBER_SIZE) {
                throw new IllegalArgumentException("memberSize must be UNKNOWN_MEMBER_SIZE or non-negative");
            }
        }

        /// Creates a context for a basic decoder invocation without available AR metadata.
        private static Context unknown(ByteBuffer bytes) {
            return new Context(
                    bytes,
                    MetadataKind.UNKNOWN,
                    Source.UNKNOWN,
                    null,
                    UNKNOWN_MEMBER_SIZE
            );
        }
    }
}
