// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar;

import org.glavo.arkivo.archive.ArchiveMetadataDecoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/// Decodes legacy RAR4 metadata that does not carry an encoded Unicode value.
///
/// RAR5 metadata has an authoritative UTF-8 encoding and bypasses this decoder.
/// The default RAR4 legacy decoder uses UTF-8. Custom decoders supply the final text without an implicit charset fallback.
@FunctionalInterface
@NotNullByDefault
public interface RarLegacyMetadataDecoder extends ArchiveMetadataDecoder {
    /// The sentinel used when an integer RAR4 header value is unavailable.
    int UNKNOWN_HEADER_VALUE = -1;

    /// The sentinel used when RAR4 file attributes are unavailable.
    long UNKNOWN_FILE_ATTRIBUTES = -1L;

    /// Decodes one complete RAR4 legacy metadata value.
    ///
    /// The supplied context and its byte-buffer view are valid only for this invocation and must not be retained.
    /// Changing the buffer position does not affect archive parsing.
    ///
    /// @param context the raw value and available file-header context
    /// @return the decoded text, never `null`
    /// @throws IOException if the value cannot be decoded according to this decoder's policy
    String decode(Context context) throws IOException;

    /// Decodes bytes without explicit RAR4 context.
    ///
    /// @implSpec Supplies an unknown context with independent read-only buffer state and rejects a null result.
    @Override
    default String decode(@UnmodifiableView ByteBuffer bytes) throws IOException {
        return Objects.requireNonNull(decode(Context.unknown(bytes)), "decoded metadata");
    }

    /// Identifies the logical RAR4 metadata field being decoded.
    @NotNullByDefault
    enum MetadataKind {
        /// The metadata kind is unavailable.
        UNKNOWN,

        /// An archive entry path.
        ENTRY_NAME
    }

    /// Provides raw bytes and available RAR4 file-header metadata to a decoder.
    ///
    /// The byte buffer is an independent read-only view valid only for the duration of the decoder call and must not
    /// be retained.
    ///
    /// @param bytes the complete raw legacy metadata bytes
    /// @param metadataKind the logical field being decoded
    /// @param hostOperatingSystem the unsigned raw RAR4 host OS, or `UNKNOWN_HEADER_VALUE`
    /// @param extractionVersion the unsigned extraction version, or `UNKNOWN_HEADER_VALUE`
    /// @param headerFlags the unsigned file-header flags, or `UNKNOWN_HEADER_VALUE`
    /// @param fileAttributes the unsigned file attributes, or `UNKNOWN_FILE_ATTRIBUTES`
    @NotNullByDefault
    record Context(
            @UnmodifiableView ByteBuffer bytes,
            MetadataKind metadataKind,
            int hostOperatingSystem,
            int extractionVersion,
            int headerFlags,
            long fileAttributes
    ) {
        /// Creates a RAR4 metadata context.
        public Context {
            bytes = Objects.requireNonNull(bytes, "bytes").asReadOnlyBuffer();
            metadataKind = Objects.requireNonNull(metadataKind, "metadataKind");
            hostOperatingSystem = requireUnsignedByteOrUnknown(hostOperatingSystem, "hostOperatingSystem");
            extractionVersion = requireUnsignedByteOrUnknown(extractionVersion, "extractionVersion");
            if (headerFlags < UNKNOWN_HEADER_VALUE || headerFlags > 0xffff) {
                throw new IllegalArgumentException("headerFlags must be UNKNOWN_HEADER_VALUE or an unsigned short");
            }
            if (fileAttributes < UNKNOWN_FILE_ATTRIBUTES || fileAttributes > 0xffff_ffffL) {
                throw new IllegalArgumentException(
                        "fileAttributes must be UNKNOWN_FILE_ATTRIBUTES or an unsigned int"
                );
            }
        }

        /// Creates a context for a basic decoder invocation without available RAR4 metadata.
        private static Context unknown(ByteBuffer bytes) {
            return new Context(
                    bytes,
                    MetadataKind.UNKNOWN,
                    UNKNOWN_HEADER_VALUE,
                    UNKNOWN_HEADER_VALUE,
                    UNKNOWN_HEADER_VALUE,
                    UNKNOWN_FILE_ATTRIBUTES
            );
        }

        /// Validates an unsigned byte value or its unavailable sentinel.
        private static int requireUnsignedByteOrUnknown(int value, String name) {
            if (value < UNKNOWN_HEADER_VALUE || value > 0xff) {
                throw new IllegalArgumentException(name + " must be UNKNOWN_HEADER_VALUE or an unsigned byte");
            }
            return value;
        }
    }
}
