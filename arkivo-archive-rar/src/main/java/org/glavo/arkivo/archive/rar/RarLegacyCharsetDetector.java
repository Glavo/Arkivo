// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar;

import org.glavo.arkivo.archive.ArchiveMetadataCharsetDetector;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Objects;

/// Selects a charset for legacy RAR4 metadata that does not carry an encoded Unicode value.
///
/// RAR5 metadata has an authoritative UTF-8 encoding and bypasses this detector.
/// Returning `null` asks the reader to use UTF-8 as the fallback for the ambiguous RAR4 value.
@FunctionalInterface
@NotNullByDefault
public interface RarLegacyCharsetDetector extends ArchiveMetadataCharsetDetector {
    /// The sentinel used when an integer RAR4 header value is unavailable.
    int UNKNOWN_HEADER_VALUE = -1;

    /// The sentinel used when RAR4 file attributes are unavailable.
    long UNKNOWN_FILE_ATTRIBUTES = -1L;

    /// Detects the charset of one RAR4 legacy metadata value, or returns `null` when it is unknown.
    @Nullable Charset detect(Context context) throws IOException;

    /// Detects bytes without explicit RAR4 context by supplying an unknown context.
    @Override
    default @Nullable Charset detect(@UnmodifiableView ByteBuffer bytes) throws IOException {
        return detect(Context.unknown(bytes));
    }

    /// Identifies the logical RAR4 metadata field being decoded.
    @NotNullByDefault
    enum MetadataKind {
        /// The metadata kind is unavailable.
        UNKNOWN,

        /// An archive entry path.
        ENTRY_NAME
    }

    /// Provides raw bytes and available RAR4 file-header metadata to a charset detector.
    ///
    /// The byte buffer is an independent read-only view valid only for the duration of the detector call and must not
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

        /// Creates a context for a basic detector invocation without available RAR4 metadata.
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
