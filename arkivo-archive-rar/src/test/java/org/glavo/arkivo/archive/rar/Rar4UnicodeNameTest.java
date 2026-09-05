// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies every RAR4 Unicode name command and malformed command boundary through the streaming API.
@NotNullByDefault
final class Rar4UnicodeNameTest {
    /// Decodes literal bytes, shared high bytes, explicit UTF-16 code units, and fallback-name copies.
    @Test
    void decodesAllBasicCommandKinds() throws IOException {
        byte[] fallback = {'a', 'b', 'c', 'd', 'e'};
        byte[] commands = {
                0x01,
                0x1b,
                'A',
                0x42,
                0x43, 0x02,
                0x00
        };

        assertEquals("A\u0142\u0243de", readPath(unicodeName(fallback, commands)));
    }

    /// Decodes a corrected fallback-name run using the shared high byte.
    @Test
    void decodesCorrectedFallbackRun() throws IOException {
        byte[] fallback = {0x10, 0x20, 0x30};
        byte[] commands = {0x04, (byte) 0xc0, (byte) 0x81, 0x02};

        assertEquals("\u0412\u0422\u0432", readPath(unicodeName(fallback, commands)));
    }

    /// Falls back to the legacy name when the Unicode separator has no encoded suffix.
    @Test
    void decodesLegacyNameWhenUnicodeSuffixIsEmpty() throws IOException {
        assertEquals(
                "legacy.txt",
                readPath(unicodeName("legacy.txt".getBytes(StandardCharsets.US_ASCII), new byte[0]))
        );
    }

    /// Rejects missing separators, truncated commands, and fallback runs outside the fallback name.
    @Test
    void rejectsMalformedUnicodeCommands() {
        assertRejected(new byte[]{'n', 'a', 'm', 'e'});
        assertRejected(unicodeName(new byte[]{'a'}, new byte[]{0x01, 0x00}));
        assertRejected(unicodeName(new byte[]{'a'}, new byte[]{0x01, (byte) 0x80, 0x41}));
        assertRejected(unicodeName(new byte[]{'a'}, new byte[]{0x01, (byte) 0xc0, 0x00}));
        assertRejected(unicodeName(new byte[]{'a', 'b'}, new byte[]{0x01, (byte) 0xc0, (byte) 0x80}));
        assertRejected(unicodeName(new byte[]{'a'}, new byte[]{0x01, (byte) 0xc0, (byte) 0x80, 0x01}));
    }

    /// Reads the sole decoded entry path from a generated Unicode-name archive.
    private static String readPath(byte @Unmodifiable [] nameField) throws IOException {
        byte[] archive = RarTestArchiveFixtures.rar4StoredArchive(nameField, true);
        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertTrue(reader.next());
            String path = reader.readAttributes(RarArkivoEntryAttributes.class).path();
            assertFalse(reader.next());
            return path;
        }
    }

    /// Requires the generated Unicode-name archive to fail while parsing its first entry.
    private static void assertRejected(byte @Unmodifiable [] nameField) {
        assertThrows(IOException.class, () -> {
            byte[] archive = RarTestArchiveFixtures.rar4StoredArchive(nameField, true);
            try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
                reader.next();
            }
        });
    }

    /// Joins fallback bytes, the zero separator, and encoded Unicode commands.
    private static byte @Unmodifiable [] unicodeName(
            byte @Unmodifiable [] fallback,
            byte @Unmodifiable [] commands
    ) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(fallback.length + 1 + commands.length);
        output.writeBytes(fallback);
        output.write(0);
        output.writeBytes(commands);
        return output.toByteArray();
    }
}
