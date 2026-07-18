// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar;

import org.glavo.arkivo.archive.ArchiveMetadataCharsetDetector;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Objects;

/// Selects a charset for TAR metadata using TAR-specific field and dialect information.
///
/// TAR readers use this interface for traditional header strings, GNU long-name records, and PAX string values whose
/// effective `hdrcharset` is `BINARY`. PAX values governed by the normal UTF-8 rules bypass the detector.
/// Returning `null` asks the reader to use UTF-8 as the fallback for the ambiguous value.
@FunctionalInterface
@NotNullByDefault
public interface TarMetadataCharsetDetector extends ArchiveMetadataCharsetDetector {
    /// The sentinel used when a TAR type flag is unavailable.
    int UNKNOWN_TYPE_FLAG = -1;

    /// Detects the charset of one TAR metadata value, or returns `null` when it is unknown.
    ///
    /// The context's buffer position identifies the first byte and its limit identifies the exclusive end. A detector
    /// may change that view's position while inspecting it; doing so does not change the reader's source position.
    ///
    /// @param context the raw value and available TAR field context, valid only for this invocation
    /// @return the charset to use, or `null` to request the reader's UTF-8 fallback
    /// @throws IOException if detection requires external data that cannot be read
    @Nullable Charset detect(Context context) throws IOException;

    /// Detects bytes without explicit TAR context by supplying an unknown context.
    @Override
    default @Nullable Charset detect(@UnmodifiableView ByteBuffer bytes) throws IOException {
        return detect(Context.unknown(bytes));
    }

    /// Identifies the logical TAR metadata field being decoded.
    @NotNullByDefault
    enum MetadataKind {
        /// The metadata kind is unavailable.
        UNKNOWN,

        /// An archive entry path.
        ENTRY_NAME,

        /// A hard-link or symbolic-link target.
        LINK_NAME,

        /// A textual user name.
        USER_NAME,

        /// A textual group name.
        GROUP_NAME
    }

    /// Identifies where a TAR metadata value was stored.
    @NotNullByDefault
    enum Source {
        /// The source is unavailable.
        UNKNOWN,

        /// A fixed-width field in a TAR entry header.
        HEADER,

        /// A GNU long-name metadata entry.
        GNU_LONG_NAME,

        /// A GNU long-link metadata entry.
        GNU_LONG_LINK,

        /// A per-entry PAX extended header.
        PAX_EXTENDED_HEADER,

        /// A global PAX extended header.
        PAX_GLOBAL_HEADER
    }

    /// Identifies the TAR header dialect surrounding a fixed-width field.
    @NotNullByDefault
    enum HeaderDialect {
        /// The dialect is unavailable or unrecognized.
        UNKNOWN,

        /// A version 7 header without a ustar magic value.
        V7,

        /// A POSIX ustar header.
        USTAR,

        /// A GNU header using the GNU ustar magic value.
        GNU,

        /// An xstar, xustar, or exustar header using the extended prefix and time fields.
        XSTAR
    }

    /// Provides raw bytes and available TAR metadata to a charset detector.
    ///
    /// The byte buffer is an independent read-only view valid only for the duration of the detector call and must not
    /// be retained.
    ///
    /// @param bytes the complete raw metadata value
    /// @param metadataKind the logical field being decoded
    /// @param source where the metadata value was stored
    /// @param headerDialect the surrounding header dialect when available
    /// @param typeFlag the unsigned TAR type flag, or `UNKNOWN_TYPE_FLAG`
    /// @param paxKey the PAX keyword for a PAX value, or `null` for non-PAX metadata
    @NotNullByDefault
    record Context(
            @UnmodifiableView ByteBuffer bytes,
            MetadataKind metadataKind,
            Source source,
            HeaderDialect headerDialect,
            int typeFlag,
            @Nullable String paxKey
    ) {
        /// Creates a TAR metadata context.
        public Context {
            bytes = Objects.requireNonNull(bytes, "bytes").asReadOnlyBuffer();
            metadataKind = Objects.requireNonNull(metadataKind, "metadataKind");
            source = Objects.requireNonNull(source, "source");
            headerDialect = Objects.requireNonNull(headerDialect, "headerDialect");
            if (typeFlag < UNKNOWN_TYPE_FLAG || typeFlag > 0xff) {
                throw new IllegalArgumentException("typeFlag must be UNKNOWN_TYPE_FLAG or an unsigned byte");
            }
        }

        /// Creates a context for a basic detector invocation without available TAR metadata.
        private static Context unknown(ByteBuffer bytes) {
            return new Context(
                    bytes,
                    MetadataKind.UNKNOWN,
                    Source.UNKNOWN,
                    HeaderDialect.UNKNOWN,
                    UNKNOWN_TYPE_FLAG,
                    null
            );
        }
    }
}
