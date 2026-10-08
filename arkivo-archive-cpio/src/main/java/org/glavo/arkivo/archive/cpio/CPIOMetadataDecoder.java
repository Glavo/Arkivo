// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.cpio;

import org.glavo.arkivo.archive.ArchiveMetadataDecoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/// Decodes CPIO metadata using the surrounding entry header when it is already available.
///
/// The default CPIO decoder uses UTF-8. Custom decoders supply the final text without an implicit charset fallback.
@FunctionalInterface
@NotNullByDefault
public interface CPIOMetadataDecoder extends ArchiveMetadataDecoder {
    /// The sentinel used when the entry inode is unavailable.
    long UNKNOWN_INODE = -1L;

    /// The sentinel used when the entry mode is unavailable.
    int UNKNOWN_MODE = -1;

    /// The sentinel used when the entry body size is unavailable.
    long UNKNOWN_ENTRY_SIZE = -1L;

    /// Decodes one complete CPIO metadata value.
    ///
    /// The context and its byte-buffer view are valid only for this invocation and must not be retained.
    ///
    /// @param context the raw metadata bytes and available surrounding header fields
    /// @return the decoded text, never `null`
    /// @throws IOException if the value cannot be decoded according to this decoder's policy
    String decode(Context context) throws IOException;

    /// Decodes bytes without explicit CPIO context.
    ///
    /// @implSpec Supplies an unknown context with independent read-only buffer state and rejects a null result.
    @Override
    default String decode(@UnmodifiableView ByteBuffer bytes) throws IOException {
        return Objects.requireNonNull(decode(Context.unknown(bytes)), "decoded metadata");
    }

    /// Identifies the logical CPIO metadata field being decoded.
    @NotNullByDefault
    enum MetadataKind {
        /// The metadata kind is unavailable.
        UNKNOWN,

        /// An archive entry path.
        ENTRY_NAME
    }

    /// Provides raw bytes and available CPIO header metadata to a decoder.
    ///
    /// The byte buffer is an independent read-only view valid only for the duration of the decoder call and must not
    /// be retained.
    ///
    /// @param bytes the complete raw metadata bytes
    /// @param metadataKind the logical field being decoded
    /// @param dialect the surrounding header dialect, or `null` when unavailable
    /// @param binaryByteOrder the old binary word byte order, or `null` for ASCII or unknown input
    /// @param inode the entry inode, or `UNKNOWN_INODE`
    /// @param mode the entry POSIX mode, or `UNKNOWN_MODE`
    /// @param entrySize the entry body size, or `UNKNOWN_ENTRY_SIZE`
    @NotNullByDefault
    record Context(
            @UnmodifiableView ByteBuffer bytes,
            MetadataKind metadataKind,
            @Nullable CPIODialect dialect,
            @Nullable CPIOBinaryByteOrder binaryByteOrder,
            long inode,
            int mode,
            long entrySize
    ) {
        /// Creates a CPIO metadata context.
        public Context {
            bytes = Objects.requireNonNull(bytes, "bytes").asReadOnlyBuffer();
            metadataKind = Objects.requireNonNull(metadataKind, "metadataKind");
            if (dialect == CPIODialect.OLD_BINARY != (binaryByteOrder != null)) {
                throw new IllegalArgumentException("binaryByteOrder must be present exactly for OLD_BINARY");
            }
            if (inode < UNKNOWN_INODE) {
                throw new IllegalArgumentException("inode must be UNKNOWN_INODE or non-negative");
            }
            if (mode < UNKNOWN_MODE) {
                throw new IllegalArgumentException("mode must be UNKNOWN_MODE or non-negative");
            }
            if (entrySize < UNKNOWN_ENTRY_SIZE) {
                throw new IllegalArgumentException("entrySize must be UNKNOWN_ENTRY_SIZE or non-negative");
            }
        }

        /// Creates a context for a basic decoder invocation without available CPIO metadata.
        private static Context unknown(ByteBuffer bytes) {
            return new Context(
                    bytes,
                    MetadataKind.UNKNOWN,
                    null,
                    null,
                    UNKNOWN_INODE,
                    UNKNOWN_MODE,
                    UNKNOWN_ENTRY_SIZE
            );
        }
    }
}
