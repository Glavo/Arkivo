// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.ArchiveMetadataDecoder;
import org.glavo.arkivo.archive.zip.ZipLegacyMetadataDecoder;
import org.glavo.arkivo.archive.zip.ZipEntryNameErrorAction;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.zip.CRC32;

/// Decodes raw ZIP entry name bytes.
@NotNullByDefault
public final class ZipEntryNameDecoder {
    /// The general purpose bit flag that marks entry names as UTF-8.
    public static final int UTF_8_FLAG = ZipConstants.UTF8_FLAG;

    /// The Info-ZIP Unicode Path Extra Field identifier.
    public static final int UNICODE_PATH_EXTRA_FIELD_ID = 0x7075;

    /// The Info-ZIP Unicode Comment Extra Field identifier.
    public static final int UNICODE_COMMENT_EXTRA_FIELD_ID = 0x6375;

    /// The decoder used when no authoritative Unicode charset is available.
    private final ArchiveMetadataDecoder legacyMetadataDecoder;

    /// The inferred archive charset, or null for UTF-8/CP437 decoding and custom callbacks.
    private final @Nullable Charset archiveCharset;

    /// The configured treatment of invalid characters in built-in name decoding.
    private final ZipEntryNameErrorAction errorAction;

    /// Creates a name decoder with a read-only recovery policy; SKIP errors are handled by the reader.
    ///
    /// @param legacyMetadataDecoder the decoder consulted when no authoritative Unicode metadata is available
    /// @param errorAction the treatment of character-decoding failures
    /// @throws NullPointerException if either argument is `null`
    public ZipEntryNameDecoder(ArchiveMetadataDecoder legacyMetadataDecoder, ZipEntryNameErrorAction errorAction) {
        this.legacyMetadataDecoder = Objects.requireNonNull(
                legacyMetadataDecoder,
                "legacyMetadataDecoder"
        );
        this.archiveCharset = null;
        this.errorAction = Objects.requireNonNull(errorAction, "errorAction");
    }

    /// Creates a frozen built-in strategy using an ASCII-compatible inference candidate.
    private ZipEntryNameDecoder(Charset archiveCharset, ZipEntryNameErrorAction errorAction) {
        this.legacyMetadataDecoder = ZipAutoMetadataDecoder.DEFAULT;
        this.archiveCharset = archiveCharset;
        this.errorAction = errorAction;
    }

    /// Prepares a frozen decoder and temporary legacy text for index construction.
    /// Custom decoders are neither sampled nor called during preparation. The buffer is not modified or retained.
    static Prepared forCentralDirectory(ArchiveMetadataDecoder configured, ByteBuffer centralDirectory)
            throws IOException {
        return forCentralDirectory(configured, centralDirectory, ZipEntryNameErrorAction.REPORT);
    }

    /// Prepares inference without treating recoverable malformed Unicode names as encoding evidence.
    static Prepared forCentralDirectory(ArchiveMetadataDecoder configured, ByteBuffer centralDirectory,
                                        ZipEntryNameErrorAction errorAction) throws IOException {
        var decodedFields = new HashMap<Integer, String>();
        ZipEntryNameDecoder decoder = configured == ZipAutoMetadataDecoder.DEFAULT
                ? new ZipEntryNameDecoder(ZipNameEncodingInference.select(centralDirectory, decodedFields,
                        errorAction != ZipEntryNameErrorAction.REPORT), errorAction)
                : new ZipEntryNameDecoder(configured, errorAction);
        return new Prepared(decoder, decodedFields);
    }

    /// Transfers already decoded fields to one index build without retaining them in the frozen decoder.
    /// This temporary result is not safe for concurrent use.
    /// @param decoder reusable decoder containing only the frozen strategy
    /// @param decodedFields mutable, exclusively owned text indexed by absolute central-directory field offset
    @NotNullByDefault
    record Prepared(ZipEntryNameDecoder decoder, Map<Integer, String> decodedFields) {
        /// Removes and returns a prepared field, or null if ordinary decoding is required.
        @Nullable String take(int offset) {
            return decodedFields.isEmpty() ? null : decodedFields.remove(offset);
        }
    }

    /// Decodes a raw ZIP entry path with available header context.
    ///
    /// @param rawPath the encoded entry name bytes
    /// @param generalPurposeFlags the unsigned 16-bit general purpose flags
    /// @param extraData the encoded extra field records
    /// @param headerSource the header that supplied the metadata
    /// @param versionNeededToExtract the unsigned 16-bit extraction version, or the decoder's unknown sentinel
    /// @param versionMadeBy the unsigned 16-bit creator version, or the decoder's unknown sentinel
    /// @param externalAttributes the unsigned 32-bit external attributes, or the decoder's unknown sentinel
    /// @return the decoded entry path
    /// @throws NullPointerException if an array or `headerSource` is `null`
    /// @throws IOException if Unicode metadata is structurally malformed or name decoding fails under the configured action
    public String decodePath(
            byte[] rawPath,
            int generalPurposeFlags,
            byte[] extraData,
            ZipLegacyMetadataDecoder.HeaderSource headerSource,
            int versionNeededToExtract,
            int versionMadeBy,
            long externalAttributes
    ) throws IOException {
        String unicodePath = decodeUnicodeExtraField(rawPath, extraData, UNICODE_PATH_EXTRA_FIELD_ID, errorAction);
        if (unicodePath != null) {
            return unicodePath;
        }
        return decodeFallback(
                rawPath,
                generalPurposeFlags,
                extraData,
                headerSource,
                versionNeededToExtract,
                versionMadeBy,
                externalAttributes
        );
    }

    /// Decodes a complete Info-ZIP Unicode extra field value, or returns `null` when no valid field is present.
    ///
    /// A truncated unknown trailing field is ignored, while a truncated recognized field remains malformed.
    ///
    /// @param rawValue the non-Unicode bytes whose CRC-32 must match the Unicode record
    /// @param extraData the encoded extra field records
    /// @param expectedFieldId the unsigned 16-bit Unicode extra field identifier to select
    /// @return the strictly decoded UTF-8 value, or `null` when no valid matching record is present
    /// @throws NullPointerException if `rawValue` or `extraData` is `null`
    /// @throws IOException if a recognized extra field is truncated or matching UTF-8 data is malformed
    public static @Nullable String decodeUnicodeExtraField(
            byte[] rawValue,
            byte[] extraData,
            int expectedFieldId
    ) throws IOException {
        return decodeUnicodeExtraField(rawValue, extraData, expectedFieldId, ZipEntryNameErrorAction.REPORT);
    }

    /// Decodes matching Unicode data without relaxing extra-field structural checks.
    private static @Nullable String decodeUnicodeExtraField(
            byte[] rawValue, byte[] extraData, int expectedFieldId, ZipEntryNameErrorAction errorAction
    ) throws IOException {
        int offset = 0;
        while (offset < extraData.length) {
            @Nullable ZipExtraFields.Field field = ZipExtraFields.readForReading(extraData, offset);
            if (field == null) {
                return null;
            }
            if (field.id() == expectedFieldId) {
                String value = decodeUnicodeExtraFieldData(rawValue, extraData, field.dataOffset(), field.dataSize(),
                        errorAction);
                if (value != null) {
                    return value;
                }
            }
            offset = field.nextOffset();
        }
        return null;
    }

    /// Decodes a validated Info-ZIP Unicode extra field data payload.
    private static @Nullable String decodeUnicodeExtraFieldData(
            byte[] rawValue,
            byte[] extraData,
            int dataOffset,
            int dataSize,
            ZipEntryNameErrorAction errorAction
    ) throws CharacterCodingException {
        if (dataSize < 5 || extraData[dataOffset] != 1) {
            return null;
        }

        int expectedCrc32 = ByteArrayAccess.readIntLittleEndian(extraData, dataOffset + 1);
        CRC32 crc32 = new CRC32();
        crc32.update(rawValue);
        if ((int) crc32.getValue() != expectedCrc32) {
            return null;
        }

        return decode(extraData, dataOffset + 5, dataSize - 5, StandardCharsets.UTF_8, errorAction);
    }

    /// Decodes raw bytes after authoritative Unicode metadata has been considered.
    private String decodeFallback(
            byte[] rawValue,
            int generalPurposeFlags,
            byte[] extraData,
            ZipLegacyMetadataDecoder.HeaderSource headerSource,
            int versionNeededToExtract,
            int versionMadeBy,
            long externalAttributes
    ) throws IOException {
        if ((generalPurposeFlags & UTF_8_FLAG) != 0) {
            return decode(rawValue, 0, rawValue.length, StandardCharsets.UTF_8, errorAction);
        }

        if (archiveCharset != null) {
            @Nullable String ascii = ZipAutoMetadataDecoder.decodeAscii(rawValue);
            return ascii != null ? ascii : decode(rawValue, 0, rawValue.length, archiveCharset, errorAction);
        }

        String decoded;
        if (legacyMetadataDecoder instanceof ZipLegacyMetadataDecoder zipDecoder) {
            decoded = zipDecoder.decode(new ZipLegacyMetadataDecoder.Context(
                    ByteBuffer.wrap(rawValue),
                    headerSource,
                    generalPurposeFlags,
                    versionNeededToExtract,
                    versionMadeBy,
                    externalAttributes,
                    ByteBuffer.wrap(extraData)
            ));
        } else {
            decoded = legacyMetadataDecoder.decode(rawValue);
        }
        return Objects.requireNonNull(decoded, "decoded ZIP metadata");
    }

    /// Decodes a complete range, replacing invalid characters only when explicitly configured.
    private static String decode(
            byte[] value,
            int offset,
            int length,
            Charset charset,
            ZipEntryNameErrorAction errorAction
    ) throws CharacterCodingException {
        if (charset == StandardCharsets.ISO_8859_1) {
            return new String(value, offset, length, StandardCharsets.ISO_8859_1);
        }
        if (charset == StandardCharsets.UTF_8 || charset == StandardCharsets.US_ASCII) {
            int end = offset + length;
            int index = offset;
            while (index < end && value[index] >= 0) index++;
            if (index == end) {
                // These built-in charsets map ASCII identically; no replacement decoding is needed.
                return new String(value, offset, length, StandardCharsets.ISO_8859_1);
            }
        }
        CodingErrorAction action = errorAction == ZipEntryNameErrorAction.REPLACE
                ? CodingErrorAction.REPLACE : CodingErrorAction.REPORT;
        return charset.newDecoder()
                .onMalformedInput(action)
                .onUnmappableCharacter(action)
                .decode(ByteBuffer.wrap(value, offset, length))
                .toString();
    }
}
