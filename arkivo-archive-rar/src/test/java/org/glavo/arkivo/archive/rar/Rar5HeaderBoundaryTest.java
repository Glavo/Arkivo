// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar;

import org.glavo.arkivo.archive.internal.ReadOnlyByteArrayChannel;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies RAR5 unsigned integer ranges and authoritative UTF-8 metadata using CRC-valid headers.
@NotNullByDefault
final class Rar5HeaderBoundaryTest {
    /// Rejects unsigned lengths before narrowing them to signed integers or file sizes.
    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, Long.MIN_VALUE + 1, -1L})
    void rejectsUnrepresentableSizes(long value) throws IOException {
        byte[] fields = concat(vints(0, 0, 0, 0, 1, 1), new byte[]{'x'});
        assertRejected(concat(vints(2, 1, value), fields), "RAR extra area size");
        assertRejected(concat(vints(2, 2, value), fields), "RAR data area size");
        assertRejected(concat(vints(2, 0, 0, value, 0, 0, 1, 1), new byte[]{'x'}), "RAR unpacked size");
        assertRejected(concat(vints(2, 0, 0, 0, 0, 0, 1, value), new byte[]{'x'}), "RAR name length");
        assertRejected(concat(vints(2, 0, 0, 0, 0, 0, value, 1), new byte[]{'x'}), "RAR host OS");
        assertRejected(vints(value, 0), "RAR header type");
    }

    /// Rejects extra-record integer truncation and IDs that would collide with absent-value sentinels.
    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, Long.MIN_VALUE + 1, -1L})
    void rejectsUnrepresentableExtraFields(long value) throws IOException {
        assertRejected(entryHeader(new byte[]{'x'}, record(vints(value))), "RAR extra record type");
        assertRejected(entryHeader(new byte[]{'x'}, record(vints(5, value, 0, 0))), "RAR redirection type");
        assertRejected(entryHeader(new byte[]{'x'}, record(vints(5, 1, 0, value))), "RAR redirection name length");
        assertRejected(entryHeader(new byte[]{'x'}, record(vints(6, 1, value))), "RAR Unix owner user name length");
        assertRejected(entryHeader(new byte[]{'x'}, record(vints(6, 2, value))), "RAR Unix owner group name length");
        assertRejected(entryHeader(new byte[]{'x'}, record(vints(6, 4, value))), "RAR Unix owner user ID");
        assertRejected(entryHeader(new byte[]{'x'}, record(vints(6, 8, value))), "RAR Unix owner group ID");
    }

    /// Ignores the raw unpacked size only when the format's unknown-size flag is present.
    @ParameterizedTest
    @ValueSource(longs = {0, Long.MAX_VALUE, Long.MIN_VALUE, -1L})
    void preservesUnknownSizeFlag(long ignoredSize) throws IOException {
        byte[] header = concat(vints(2, 0, 8, ignoredSize, 0, 0, 1, 1), new byte[]{'x'});
        try (RarArkivoStreamingReader reader = open(header)) {
            assertTrue(reader.next());
            assertEquals(RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE,
                    reader.readAttributes(RarArkivoEntryAttributes.class).unpackedSize());
            try (var body = reader.openInputStream()) {
                assertEquals(-1, body.read());
            }
            assertFalse(reader.next());
        }
    }

    /// Preserves the sign bit in opaque bit fields instead of treating it as a negative size.
    @Test
    void preservesUnsignedFlagsAndAttributes() throws IOException {
        byte[] header = concat(vints(2, Long.MIN_VALUE, Long.MIN_VALUE, 0, Long.MIN_VALUE, 0, 1, 1),
                new byte[]{'x'});
        try (RarArkivoStreamingReader reader = open(header)) {
            assertTrue(reader.next());
            assertEquals(Long.MIN_VALUE, reader.readAttributes(RarArkivoEntryAttributes.class).fileAttributes());
            assertFalse(reader.next());
        }
    }

    /// Rejects malformed UTF-8 in names and link targets without substituting replacement characters.
    @ParameterizedTest
    @ValueSource(strings = {"ff", "80", "c2", "c080", "e282", "eda080", "f4908080"})
    void rejectsMalformedUtf8(String hex) throws IOException {
        byte[] malformed = HexFormat.of().parseHex(hex);
        assertRejected(entryHeader(malformed, new byte[0]), "RAR entry name");
        byte[] redirection = record(concat(vints(5, 1, 0, malformed.length), malformed));
        assertRejected(entryHeader(new byte[]{'x'}, redirection), "RAR redirection name");
    }

    /// Accepts valid replacement, supplementary, and RAR private-use mapping characters unchanged.
    @ParameterizedTest
    @ValueSource(strings = {"café", "\ufffd", "\ud83d\udcc1", "\ufffe\ue0ff"})
    void preservesValidUtf8(String name) throws IOException {
        byte[] encoded = name.getBytes(StandardCharsets.UTF_8);
        byte[] header = entryHeader(encoded, record(concat(vints(5, 1, 0, encoded.length), encoded)));
        try (RarArkivoStreamingReader reader = open(header)) {
            assertTrue(reader.next());
            RarArkivoEntryAttributes attributes = reader.readAttributes(RarArkivoEntryAttributes.class);
            assertEquals(name, attributes.path());
            assertEquals(name, attributes.linkName());
        }
    }

    /// Keeps native-encoding Unix owner names outside the authoritative UTF-8 validation path.
    @Test
    void acceptsNativeOwnerNames() throws IOException {
        byte[] owner = record(concat(vints(6, 3, 1), new byte[]{(byte) 0xff}, vints(1), new byte[]{(byte) 0xff}));
        try (RarArkivoStreamingReader reader = open(entryHeader(new byte[]{'x'}, owner))) {
            assertTrue(reader.next());
            assertEquals("x", reader.readAttributes(RarArkivoEntryAttributes.class).path());
            assertFalse(reader.next());
        }
    }

    /// Requires both streaming and indexed readers to report the malformed field as an I/O failure.
    private static void assertRejected(byte[] header, String description) throws IOException {
        byte[] archive = RarTestArchiveFixtures.rar5ArchiveWithFileHeader(header);
        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            IOException failure = assertThrows(IOException.class, reader::next);
            assertTrue(failure.getMessage().contains(description), failure::getMessage);
        }
        IOException failure = assertThrows(IOException.class, () -> {
            try (var fileSystem = RarArkivoFileSystem.open(new ReadOnlyByteArrayChannel(archive))) {
                java.nio.file.Files.readAttributes(fileSystem.getPath("/x"), RarArkivoEntryAttributes.class);
            }
        });
        assertTrue(failure.getMessage().contains(description), failure::getMessage);
    }

    /// Opens a complete archive containing one CRC-valid file header.
    private static RarArkivoStreamingReader open(byte[] header) throws IOException {
        return RarArkivoStreamingReader.open(new ByteArrayInputStream(
                RarTestArchiveFixtures.rar5ArchiveWithFileHeader(header)));
    }

    /// Encodes an empty stored entry with arbitrary name and extra-area bytes.
    private static byte[] entryHeader(byte[] name, byte[] extra) {
        return concat(vints(2, extra.length == 0 ? 0 : 1),
                extra.length == 0 ? new byte[0] : vints(extra.length),
                vints(0, 0, 0, 0, 1, name.length), name, extra);
    }

    /// Prefixes one extra record with its encoded length.
    private static byte[] record(byte[] fields) {
        return concat(vints(fields.length), fields);
    }

    /// Encodes consecutive unsigned integers, including values with the sign bit set.
    private static byte[] vints(long... values) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (long value : values) {
            output.writeBytes(RarTestArchiveFixtures.vint(value));
        }
        return output.toByteArray();
    }

    /// Concatenates encoded header fields without changing their bytes.
    private static byte[] concat(byte[]... fields) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] field : fields) {
            output.writeBytes(field);
        }
        return output.toByteArray();
    }
}
