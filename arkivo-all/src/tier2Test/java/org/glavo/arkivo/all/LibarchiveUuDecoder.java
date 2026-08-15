// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

/// Decodes the traditional and base64 uuencode representations used for libarchive test fixtures.
@NotNullByDefault
final class LibarchiveUuDecoder {
    /// Prevents instantiation of this utility class.
    private LibarchiveUuDecoder() {
    }

    /// Decodes one complete uuencoded file and returns its binary payload.
    static byte @Unmodifiable [] decode(Path source) throws IOException {
        List<String> lines = Files.readAllLines(source, StandardCharsets.US_ASCII);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.startsWith("begin-base64 ")) {
                validateHeader(source, line);
                return decodeBase64(source, lines, index + 1);
            }
            if (line.startsWith("begin ")) {
                validateHeader(source, line);
                return decodeTraditional(source, lines, index + 1);
            }
        }
        throw malformed(source, "missing begin header");
    }

    /// Decodes a traditional uuencode body beginning at the specified line.
    private static byte @Unmodifiable [] decodeTraditional(
            Path source,
            List<String> lines,
            int firstBodyLine
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean zeroLengthLineFound = false;
        boolean endFound = false;

        for (int index = firstBodyLine; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.equals("end")) {
                endFound = true;
                break;
            }
            if (line.isEmpty()) {
                throw malformed(source, "empty encoded line");
            }

            int decodedLength = decodeCharacter(source, line.charAt(0));
            if (decodedLength == 0) {
                zeroLengthLineFound = true;
                continue;
            }
            if (zeroLengthLineFound) {
                throw malformed(source, "data follows the zero-length terminator");
            }
            if (decodedLength > 45) {
                throw malformed(source, "encoded line declares more than 45 bytes");
            }

            int encodedLength = Math.multiplyExact((decodedLength + 2) / 3, 4);
            if (line.length() < encodedLength + 1) {
                throw malformed(source, "encoded line is shorter than its declared length");
            }
            decodeLine(source, line, decodedLength, output);
        }

        if (!zeroLengthLineFound) {
            throw malformed(source, "missing zero-length terminator");
        }
        if (!endFound) {
            throw malformed(source, "missing end marker");
        }
        return output.toByteArray();
    }

    /// Decodes a base64 uuencode body beginning at the specified line.
    private static byte @Unmodifiable [] decodeBase64(
            Path source,
            List<String> lines,
            int firstBodyLine
    ) throws IOException {
        StringBuilder encoded = new StringBuilder();
        for (int index = firstBodyLine; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.equals("====")) {
                try {
                    return Base64.getDecoder().decode(encoded.toString());
                } catch (IllegalArgumentException exception) {
                    throw malformed(source, "invalid base64 body", exception);
                }
            }
            if (line.isEmpty()) {
                throw malformed(source, "empty encoded line");
            }
            encoded.append(line);
        }
        throw malformed(source, "missing base64 end marker");
    }

    /// Validates the mode and filename fields in a uuencode header.
    private static void validateHeader(Path source, String header) throws IOException {
        String @Unmodifiable [] components = header.split(" ", 3);
        if (components.length != 3
                || !components[1].matches("[0-7]{3,4}")
                || components[2].isBlank()) {
            throw malformed(source, "invalid begin header");
        }
    }

    /// Decodes one non-empty uuencode data line.
    private static void decodeLine(
            Path source,
            String line,
            int decodedLength,
            ByteArrayOutputStream output
    ) throws IOException {
        int remaining = decodedLength;
        int offset = 1;
        while (remaining > 0) {
            int first = decodeCharacter(source, line.charAt(offset));
            int second = decodeCharacter(source, line.charAt(offset + 1));
            int third = decodeCharacter(source, line.charAt(offset + 2));
            int fourth = decodeCharacter(source, line.charAt(offset + 3));

            output.write((first << 2) | (second >>> 4));
            if (remaining > 1) {
                output.write((second << 4) | (third >>> 2));
            }
            if (remaining > 2) {
                output.write((third << 6) | fourth);
            }
            remaining -= Math.min(remaining, 3);
            offset += 4;
        }
    }

    /// Converts one printable uuencode character to its six-bit value.
    private static int decodeCharacter(Path source, char character) throws IOException {
        if (character < ' ' || character > '`') {
            throw malformed(source, "character is outside the uuencode alphabet");
        }
        return (character - ' ') & 0x3f;
    }

    /// Creates a checked failure identifying a malformed fixture.
    private static IOException malformed(Path source, String detail) {
        return new IOException("Malformed uuencoded fixture " + source + ": " + detail);
    }

    /// Creates a checked failure identifying a malformed fixture and its cause.
    private static IOException malformed(Path source, String detail, Throwable cause) {
        return new IOException("Malformed uuencoded fixture " + source + ": " + detail, cause);
    }
}
