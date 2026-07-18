// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.cpio;

import org.glavo.arkivo.archive.ArchiveMetadataCharsetDetector;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Objects;

/// Selects a charset for CPIO metadata using the surrounding entry header when it is already available.
///
/// Returning `null` asks the reader to use UTF-8 as the fallback for the ambiguous metadata value.
@FunctionalInterface
@NotNullByDefault
public interface CPIOMetadataCharsetDetector extends ArchiveMetadataCharsetDetector {
    /// The sentinel used when the entry inode is unavailable.
    long UNKNOWN_INODE = -1L;

    /// The sentinel used when the entry mode is unavailable.
    int UNKNOWN_MODE = -1;

    /// The sentinel used when the entry body size is unavailable.
    long UNKNOWN_ENTRY_SIZE = -1L;

    /// Detects the charset of one CPIO metadata value, or returns `null` when it is unknown.
    ///
    /// The context and its byte-buffer view are valid only for this invocation and must not be retained.
    ///
    /// @param context the raw metadata bytes and available surrounding header fields
    /// @return the selected charset, or `null` to use the CPIO UTF-8 fallback
    /// @throws IOException if the detector rejects the metadata or cannot complete detection
    @Nullable Charset detect(Context context) throws IOException;

    /// Detects bytes without explicit CPIO context by supplying an unknown context.
    @Override
    default @Nullable Charset detect(@UnmodifiableView ByteBuffer bytes) throws IOException {
        return detect(Context.unknown(bytes));
    }

    /// Identifies the logical CPIO metadata field being decoded.
    @NotNullByDefault
    enum MetadataKind {
        /// The metadata kind is unavailable.
        UNKNOWN,

        /// An archive entry path.
        ENTRY_NAME
    }

    /// Provides raw bytes and available CPIO header metadata to a charset detector.
    ///
    /// The byte buffer is an independent read-only view valid only for the duration of the detector call and must not
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

        /// Creates a context for a basic detector invocation without available CPIO metadata.
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
