// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArchiveMetadataCharsetDetector;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Objects;

/// Selects a charset for legacy ZIP entry metadata using ZIP-specific header context.
///
/// ZIP readers invoke this detector only after a valid Info-ZIP Unicode extra field and the UTF-8 general-purpose
/// flag have been considered. The option that configures ZIP decoding accepts the basic
/// `ArchiveMetadataCharsetDetector`; a reader creates a `Context` only when that detector also implements this
/// interface.
///
/// Returning `null` reports that the charset could not be determined; the reader then falls back to CP437 as required
/// by the original ZIP format rules. Implementations may be reused by multiple archive readers and therefore should
/// be thread-safe.
@FunctionalInterface
@NotNullByDefault
public interface ZipLegacyCharsetDetector extends ArchiveMetadataCharsetDetector {
    /// The sentinel used when a ZIP header value is unavailable to a detector.
    int UNKNOWN_HEADER_VALUE = -1;

    /// Detects the charset of one raw legacy ZIP entry name or comment, or returns `null` when it is unknown.
    @Nullable Charset detect(Context context) throws IOException;

    /// Detects bytes without explicit ZIP context by supplying an unknown context.
    @Override
    default @Nullable Charset detect(@UnmodifiableView ByteBuffer bytes) throws IOException {
        return detect(Context.unknown(bytes));
    }

    /// Identifies the logical ZIP metadata field being decoded.
    @NotNullByDefault
    enum MetadataKind {
        /// The metadata kind is unavailable.
        UNKNOWN,

        /// An entry path stored in a file header.
        ENTRY_NAME,

        /// An entry comment stored in a central directory header.
        ENTRY_COMMENT
    }

    /// Identifies the ZIP header that supplied a metadata value.
    @NotNullByDefault
    enum HeaderSource {
        /// The header source is unavailable.
        UNKNOWN,

        /// A local file header used by a forward-only reader.
        LOCAL_FILE_HEADER,

        /// A central directory file header used by a seekable reader.
        CENTRAL_DIRECTORY
    }

    /// Provides raw bytes and available ZIP header metadata to a legacy charset detector.
    ///
    /// Both buffers are independent read-only views. Their contents are valid only for the duration of the detector
    /// call and must not be retained.
    ///
    /// @param bytes the complete raw name or comment bytes
    /// @param metadataKind the logical field being decoded
    /// @param headerSource the header that supplied the field
    /// @param generalPurposeFlags the unsigned general-purpose flags, or `UNKNOWN_HEADER_VALUE`
    /// @param versionNeededToExtract the unsigned version-needed value, or `UNKNOWN_HEADER_VALUE`
    /// @param versionMadeBy the unsigned version-made-by value, or `UNKNOWN_HEADER_VALUE`
    /// @param extraData the complete raw extra-field area from the same header
    @NotNullByDefault
    record Context(
            @UnmodifiableView ByteBuffer bytes,
            MetadataKind metadataKind,
            HeaderSource headerSource,
            int generalPurposeFlags,
            int versionNeededToExtract,
            int versionMadeBy,
            @UnmodifiableView ByteBuffer extraData
    ) {
        /// Creates a ZIP legacy metadata context.
        public Context {
            bytes = readOnly(bytes, "bytes");
            metadataKind = Objects.requireNonNull(metadataKind, "metadataKind");
            headerSource = Objects.requireNonNull(headerSource, "headerSource");
            generalPurposeFlags = requireUnsignedShortOrUnknown(
                    generalPurposeFlags,
                    "generalPurposeFlags"
            );
            versionNeededToExtract = requireUnsignedShortOrUnknown(
                    versionNeededToExtract,
                    "versionNeededToExtract"
            );
            versionMadeBy = requireUnsignedShortOrUnknown(versionMadeBy, "versionMadeBy");
            extraData = readOnly(extraData, "extraData");
        }

        /// Returns the ZIP creator-system identifier, or `UNKNOWN_HEADER_VALUE` when version-made-by is unavailable.
        public int creatorSystem() {
            return versionMadeBy == UNKNOWN_HEADER_VALUE
                    ? UNKNOWN_HEADER_VALUE
                    : versionMadeBy >>> Byte.SIZE;
        }

        /// Returns the ZIP creator version, or `UNKNOWN_HEADER_VALUE` when version-made-by is unavailable.
        public int creatorVersion() {
            return versionMadeBy == UNKNOWN_HEADER_VALUE
                    ? UNKNOWN_HEADER_VALUE
                    : versionMadeBy & 0xff;
        }

        /// Creates a context for a basic detector invocation without available ZIP header metadata.
        private static Context unknown(ByteBuffer bytes) {
            return new Context(
                    bytes,
                    MetadataKind.UNKNOWN,
                    HeaderSource.UNKNOWN,
                    UNKNOWN_HEADER_VALUE,
                    UNKNOWN_HEADER_VALUE,
                    UNKNOWN_HEADER_VALUE,
                    ByteBuffer.allocate(0).asReadOnlyBuffer()
            );
        }

        /// Returns an independent read-only view of a required buffer.
        private static @UnmodifiableView ByteBuffer readOnly(ByteBuffer buffer, String name) {
            return Objects.requireNonNull(buffer, name).asReadOnlyBuffer();
        }

        /// Validates an unsigned ZIP header value or its unavailable sentinel.
        private static int requireUnsignedShortOrUnknown(int value, String name) {
            if (value < UNKNOWN_HEADER_VALUE || value > 0xffff) {
                throw new IllegalArgumentException(name + " must be UNKNOWN_HEADER_VALUE or an unsigned short");
            }
            return value;
        }
    }
}
