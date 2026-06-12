// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.glavo.arkivo.zip.ZipEntryNameEncoding;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.CRC32;

import static org.glavo.arkivo.zip.internal.ZipLittleEndian.readInt;

/// Decodes raw ZIP entry name bytes.
@NotNullByDefault
public final class ZipEntryNameDecoder {
    /// The general purpose bit flag that marks entry names as UTF-8.
    public static final int UTF_8_FLAG = ZipConstants.UTF8_FLAG;

    /// The Info-ZIP Unicode Path Extra Field identifier.
    public static final int UNICODE_PATH_EXTRA_FIELD_ID = 0x7075;

    /// The Info-ZIP Unicode Comment Extra Field identifier.
    public static final int UNICODE_COMMENT_EXTRA_FIELD_ID = 0x6375;

    /// The entry name encoding policy used when no authoritative Unicode name is available.
    private final ZipEntryNameEncoding encoding;

    /// Creates an entry name decoder.
    public ZipEntryNameDecoder(ZipEntryNameEncoding encoding) {
        this.encoding = Objects.requireNonNull(encoding, "encoding");
    }

    /// Decodes a raw ZIP entry path.
    public String decodePath(byte[] rawPath, int generalPurposeFlags, byte[] extraData) throws IOException {
        String unicodePath = decodeUnicodeExtraField(rawPath, extraData, UNICODE_PATH_EXTRA_FIELD_ID);
        if (unicodePath != null) {
            return unicodePath;
        }
        return decodeFallback(rawPath, generalPurposeFlags);
    }

    /// Decodes a raw ZIP entry comment, or returns `null` when no comment is present.
    public @Nullable String decodeComment(
            byte @Nullable [] rawComment,
            int generalPurposeFlags,
            byte[] extraData
    ) throws IOException {
        if (rawComment == null || rawComment.length == 0) {
            return null;
        }

        try {
            String unicodeComment =
                    decodeUnicodeExtraField(rawComment, extraData, UNICODE_COMMENT_EXTRA_FIELD_ID);
            if (unicodeComment != null) {
                return unicodeComment;
            }
            return decodeFallback(rawComment, generalPurposeFlags);
        } catch (CharacterCodingException exception) {
            throw new IOException("Failed to decode ZIP entry comment", exception);
        }
    }

    /// Decodes a validated Info-ZIP Unicode extra field value, or returns `null` when no valid field is present.
    public static @Nullable String decodeUnicodeExtraField(
            byte[] rawValue,
            byte[] extraData,
            int expectedFieldId
    ) throws IOException {
        int offset = 0;
        while (offset < extraData.length) {
            ZipExtraFields.Field field = ZipExtraFields.read(extraData, offset);
            if (field.id() == expectedFieldId) {
                String value = decodeUnicodeExtraFieldData(rawValue, extraData, field.dataOffset(), field.dataSize());
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
            int dataSize
    ) throws CharacterCodingException {
        if (dataSize < 5 || extraData[dataOffset] != 1) {
            return null;
        }

        int expectedCrc32 = readInt(extraData, dataOffset + 1);
        CRC32 crc32 = new CRC32();
        crc32.update(rawValue);
        if ((int) crc32.getValue() != expectedCrc32) {
            return null;
        }

        return strictDecode(extraData, dataOffset + 5, dataSize - 5, java.nio.charset.StandardCharsets.UTF_8);
    }

    /// Decodes raw bytes after authoritative Unicode metadata has been considered.
    private String decodeFallback(byte[] rawValue, int generalPurposeFlags) throws CharacterCodingException {
        if ((generalPurposeFlags & UTF_8_FLAG) != 0) {
            return strictDecode(rawValue, java.nio.charset.StandardCharsets.UTF_8);
        }

        return switch (encoding.mode()) {
            case STANDARD -> strictDecode(rawValue, ZipEntryNameEncoding.cp437());
            case CHARSET -> strictDecode(rawValue, Objects.requireNonNull(encoding.charset(), "charset"));
            case AUTO -> autoDecode(rawValue);
        };
    }

    /// Chooses a fallback charset from the configured automatic candidate list.
    private String autoDecode(byte[] rawValue) throws CharacterCodingException {
        String bestValue = null;
        int bestScore = Integer.MIN_VALUE;

        for (Charset candidate : encoding.candidates()) {
            String decodedValue;
            try {
                decodedValue = strictDecode(rawValue, candidate);
            } catch (CharacterCodingException ignored) {
                continue;
            }
            if (!roundTrips(rawValue, decodedValue, candidate)) {
                continue;
            }

            int score = decodedNameScore(decodedValue);
            if (score > bestScore) {
                bestValue = decodedValue;
                bestScore = score;
            }
        }

        if (bestValue != null) {
            return bestValue;
        }
        return strictDecode(rawValue, ZipEntryNameEncoding.cp437());
    }

    /// Strictly decodes a complete byte array with the given charset.
    private static String strictDecode(byte[] value, Charset charset) throws CharacterCodingException {
        return strictDecode(value, 0, value.length, charset);
    }

    /// Strictly decodes a byte array range with the given charset.
    private static String strictDecode(
            byte[] value,
            int offset,
            int length,
            Charset charset
    ) throws CharacterCodingException {
        return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value, offset, length))
                .toString();
    }

    /// Returns whether the decoded value encodes back to the same raw bytes.
    private static boolean roundTrips(byte[] rawValue, String decodedValue, Charset charset) throws CharacterCodingException {
        ByteBuffer encodedValue = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(decodedValue));
        byte[] bytes = new byte[encodedValue.remaining()];
        encodedValue.get(bytes);
        return Arrays.equals(rawValue, bytes);
    }

    /// Scores a decoded path so automatic detection prefers readable names over mojibake.
    private static int decodedNameScore(String value) {
        int score = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == 0 || Character.isISOControl(character)) {
                return Integer.MIN_VALUE;
            }
            if (character == '/') {
                score += 3;
            } else if (character <= 0x7f) {
                score += asciiScore(character);
            } else if (Character.isLetterOrDigit(character)) {
                score += 8;
            } else if (Character.isWhitespace(character)) {
                score += 2;
            } else {
                score += 1;
            }
        }
        return score;
    }

    /// Scores an ASCII character inside a decoded path.
    private static int asciiScore(char character) {
        if (Character.isLetterOrDigit(character)) {
            return 4;
        }
        return switch (character) {
            case '.', '_', '-', ' ', '(', ')', '[', ']' -> 3;
            default -> 1;
        };
    }

}
