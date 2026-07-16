// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar;

import org.glavo.arkivo.archive.ArchiveMetadataCharsetDetector;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Objects;

/// Selects a charset for AR member names using the name representation that supplied the bytes.
///
/// Returning `null` asks the reader to use UTF-8 as the fallback for the ambiguous member name.
@FunctionalInterface
@NotNullByDefault
public interface ArMetadataCharsetDetector extends ArchiveMetadataCharsetDetector {
    /// The sentinel used when the containing member size is unavailable.
    long UNKNOWN_MEMBER_SIZE = -1L;

    /// Detects the charset of one AR metadata value, or returns `null` when it is unknown.
    @Nullable Charset detect(Context context) throws IOException;

    /// Detects bytes without explicit AR context by supplying an unknown context.
    @Override
    default @Nullable Charset detect(@UnmodifiableView ByteBuffer bytes) throws IOException {
        return detect(Context.unknown(bytes));
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

    /// Provides raw bytes and available AR member-name metadata to a charset detector.
    ///
    /// The byte buffer is an independent read-only view valid only for the duration of the detector call and must not
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

        /// Creates a context for a basic detector invocation without available AR metadata.
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
