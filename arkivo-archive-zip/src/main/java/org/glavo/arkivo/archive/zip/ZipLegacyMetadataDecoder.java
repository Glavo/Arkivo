// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArchiveMetadataDecoder;
import org.glavo.arkivo.archive.zip.internal.ZipLegacyCodePages;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Objects;

/// Decodes legacy ZIP entry names using ZIP-specific header context.
///
/// ZIP readers invoke this decoder only when no usable Info-ZIP Unicode extra field or UTF-8 general-purpose flag
/// supplies the encoding. Structural extra-field validation remains the reader's responsibility.
///
/// The [default ZIP policy][ZipArchiveOptions#DEFAULT_LEGACY_METADATA_DECODER] infers an encoding for indexed reading
/// and tries strict UTF-8 followed by CP437 for streaming reading. A custom decoder supplies the final text;
/// there is no implicit CP437 fallback
/// if it throws an exception or returns `null`.
@FunctionalInterface
@NotNullByDefault
public interface ZipLegacyMetadataDecoder extends ArchiveMetadataDecoder {
    /// The sentinel used when a ZIP header value is unavailable to a decoder.
    int UNKNOWN_HEADER_VALUE = -1;

    /// Returns a decoder that selects explicitly supplied legacy code pages using ZIP creator metadata.
    ///
    /// The OEM charset is used for FAT (creator system `0`) and HPFS (`6`) entries, and for NTFS (`10`)
    /// entries with creator version `50`. The historical NTFS identifier `11` is also treated as OEM
    /// when its creator version is `50`. Other available creator-system identifiers select the ANSI charset.
    ///
    /// FAT entries with creator version `25`, `26`, or `40` instead select ANSI when the name comes from
    /// a local header, or when the external file attributes have nonzero high 16 bits. These rules
    /// accommodate legacy PKZIP and WinZip conventions; they do not identify a language or region.
    /// The ANSI charset can also represent the Unix producer's character encoding.
    ///
    /// Without creator metadata, including ordinary forward-only ZIP reading and basic byte-buffer
    /// invocations, the OEM charset is used. The version-needed-to-extract field is not used to infer
    /// the creator. Indexed and forward-only reading can therefore decode the same bytes differently.
    ///
    /// Conversion is strict: malformed or unmappable input causes [java.nio.charset.CharacterCodingException],
    /// without trying the other charset. Neither buffer is modified or retained. The returned decoder
    /// supports concurrent invocations and does not consult the system locale. ZIP readers still process
    /// explicit Unicode metadata before invoking it. This policy replaces automatic legacy-encoding detection.
    /// This policy does not relax archive validation: indexed readers still reject different raw names
    /// in the local header and central directory, even if different code pages could yield the same text.
    ///
    /// @param oemCharset the charset for DOS/OEM metadata and unavailable creator information
    /// @param ansiCharset the charset for ANSI/ISO metadata selected by the compatibility rules
    /// @return a decoder using the supplied code pages
    /// @throws NullPointerException if either charset is `null`
    static ZipLegacyMetadataDecoder forCodePages(Charset oemCharset, Charset ansiCharset) {
        ArchiveMetadataDecoder oem = ArchiveMetadataDecoder.forCharset(oemCharset);
        ArchiveMetadataDecoder ansi = ArchiveMetadataDecoder.forCharset(ansiCharset);
        return context -> (ZipLegacyCodePages.usesOem(context) ? oem : ansi).decode(context.bytes());
    }

    /// Decodes one complete legacy ZIP entry name.
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
    /// @param bytes the complete raw entry name bytes
    /// @param headerSource the header that supplied the field
    /// @param generalPurposeFlags the unsigned general-purpose flags, or `UNKNOWN_HEADER_VALUE`
    /// @param versionNeededToExtract the unsigned version-needed value, or `UNKNOWN_HEADER_VALUE`
    /// @param versionMadeBy the unsigned version-made-by value, or `UNKNOWN_HEADER_VALUE`
    /// @param externalAttributes the unsigned 32-bit central-directory external attributes, or `UNKNOWN_HEADER_VALUE`
    /// @param extraData the complete raw extra-field area from the same header
    @NotNullByDefault
    record Context(
            @UnmodifiableView ByteBuffer bytes,
            HeaderSource headerSource,
            int generalPurposeFlags,
            int versionNeededToExtract,
            int versionMadeBy,
            long externalAttributes,
            @UnmodifiableView ByteBuffer extraData
    ) {
        /// Creates a ZIP legacy metadata context.
        public Context {
            bytes = readOnly(bytes, "bytes");
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
            if (externalAttributes < UNKNOWN_HEADER_VALUE || externalAttributes > 0xffff_ffffL) {
                throw new IllegalArgumentException("externalAttributes must be UNKNOWN_HEADER_VALUE or an unsigned int");
            }
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

        /// Returns the creator's supported ZIP specification version, or `UNKNOWN_HEADER_VALUE` when unavailable.
        ///
        /// This is the low byte of version-made-by, not a reliable product version. The major version is
        /// the value divided by ten and the minor version is its remainder modulo ten.
        ///
        /// @return the unsigned 8-bit specification version, or [ZipLegacyMetadataDecoder#UNKNOWN_HEADER_VALUE]
        public int creatorVersion() {
            return versionMadeBy == UNKNOWN_HEADER_VALUE
                    ? UNKNOWN_HEADER_VALUE
                    : versionMadeBy & 0xff;
        }

        /// Creates a context for a basic decoder invocation without available ZIP header metadata.
        private static Context unknown(ByteBuffer bytes) {
            return new Context(
                    bytes,
                    HeaderSource.UNKNOWN,
                    UNKNOWN_HEADER_VALUE,
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
