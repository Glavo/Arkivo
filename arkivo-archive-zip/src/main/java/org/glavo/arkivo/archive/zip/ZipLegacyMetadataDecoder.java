// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArchiveMetadataDecoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/// Decodes legacy ZIP entry metadata using ZIP-specific header context.
///
/// ZIP readers invoke this decoder only when no usable Info-ZIP Unicode extra field or UTF-8 general-purpose flag
/// supplies the encoding. Structural extra-field validation remains the reader's responsibility.
///
/// The default ZIP decoder uses CP437. A custom decoder supplies the final text; there is no implicit CP437 fallback
/// if it throws an exception or returns `null`.
@FunctionalInterface
@NotNullByDefault
public interface ZipLegacyMetadataDecoder extends ArchiveMetadataDecoder {
    /// The sentinel used when a ZIP header value is unavailable to a decoder.
    int UNKNOWN_HEADER_VALUE = -1;

    /// Decodes one complete legacy ZIP entry name or comment.
    ///
    /// The context buffers are independent read-only views valid only for this call. An implementation may advance their
    /// positions while inspecting them but must not retain either buffer.
    ///
    /// @param context the raw metadata and available ZIP header context
    /// @return the decoded text, never `null`
    /// @throws NullPointerException if `context` is `null`
    /// @throws IOException if the value cannot be decoded according to this decoder's policy
    String decode(Context context) throws IOException;

    /// Decodes bytes without explicit ZIP context.
    ///
    /// @implSpec Supplies an unknown context with independent read-only buffer state and rejects a null result.
    @Override
    default String decode(@UnmodifiableView ByteBuffer bytes) throws IOException {
        return Objects.requireNonNull(decode(Context.unknown(bytes)), "decoded metadata");
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

    /// Provides raw bytes and available ZIP header metadata to a legacy decoder.
    ///
    /// Both buffers are independent read-only views. Their contents are valid only for the duration of the decoder
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
            Objects.requireNonNull(metadataKind, "metadataKind");
            Objects.requireNonNull(headerSource, "headerSource");
            requireUnsignedShortOrUnknown(
                    generalPurposeFlags,
                    "generalPurposeFlags"
            );
            requireUnsignedShortOrUnknown(
                    versionNeededToExtract,
                    "versionNeededToExtract"
            );
            requireUnsignedShortOrUnknown(versionMadeBy, "versionMadeBy");
            extraData = readOnly(extraData, "extraData");
        }

        /// Returns the ZIP creator-system identifier, or `UNKNOWN_HEADER_VALUE` when version-made-by is unavailable.
        ///
        /// @return the unsigned 8-bit creator-system identifier, or [ZipLegacyMetadataDecoder#UNKNOWN_HEADER_VALUE]
        public int creatorSystem() {
            return versionMadeBy == UNKNOWN_HEADER_VALUE
                    ? UNKNOWN_HEADER_VALUE
                    : versionMadeBy >>> Byte.SIZE;
        }

        /// Returns the ZIP creator version, or `UNKNOWN_HEADER_VALUE` when version-made-by is unavailable.
        ///
        /// @return the unsigned 8-bit creator version, or [ZipLegacyMetadataDecoder#UNKNOWN_HEADER_VALUE]
        public int creatorVersion() {
            return versionMadeBy == UNKNOWN_HEADER_VALUE
                    ? UNKNOWN_HEADER_VALUE
                    : versionMadeBy & 0xff;
        }

        /// Creates a context for a basic decoder invocation without available ZIP header metadata.
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
        private static void requireUnsignedShortOrUnknown(int value, String name) {
            if (value < UNKNOWN_HEADER_VALUE || value > 0xffff) {
                throw new IllegalArgumentException(name + " must be UNKNOWN_HEADER_VALUE or an unsigned short");
            }
        }
    }
}
