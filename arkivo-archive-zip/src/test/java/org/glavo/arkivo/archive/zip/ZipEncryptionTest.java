// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests ZIP encryption identifiers and streaming-writer password preconditions.
@NotNullByDefault
public final class ZipEncryptionTest {
    /// Verifies that every encryption method round-trips through its case-insensitive stable identifier.
    @Test
    public void stableIdentifiersRoundTrip() {
        for (ZipEncryption encryption : ZipEncryption.values()) {
            assertSame(encryption, ZipEncryption.parse(encryption.id().toUpperCase(Locale.ROOT)));
            assertEquals(encryption.id(), encryption.toString());
        }
    }

    /// Verifies that legacy and unrecognized encryption identifiers are rejected.
    @Test
    public void unrecognizedIdentifiersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ZipEncryption.parse("traditional"));
        assertThrows(IllegalArgumentException.class, () -> ZipEncryption.parse("vendor-extension"));
    }

    /// Verifies every encrypted regular-file and symbolic-link entry requires a password before its header is written.
    ///
    /// @param encryption the encrypted method under test
    @ParameterizedTest
    @EnumSource(value = ZipEncryption.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
    public void encryptedEntriesRequirePasswordBeforeWritingHeaders(ZipEncryption encryption) throws IOException {
        byte @Unmodifiable [] expectedArchive = emptyArchive();

        ByteArrayOutputStream fileArchive = new ByteArrayOutputStream();
        ZipArkivoStreamingWriter fileWriter = ZipArkivoStreamingWriter.open(fileArchive);
        var fileEntry = fileWriter.beginFile("secret.txt");
        Objects.requireNonNull(fileEntry.attributeView(ZipArkivoEntryAttributeView.class)).setEncryption(encryption);
        IOException fileFailure = assertThrows(IOException.class, fileEntry::openOutputStream);
        assertTrue(fileFailure.getMessage().contains("requires a password"));
        IOException fileCloseFailure = assertThrows(IOException.class, fileWriter::close);
        assertTrue(fileCloseFailure.getMessage().contains("requires a password"));
        assertArrayEquals(expectedArchive, fileArchive.toByteArray());

        ByteArrayOutputStream linkArchive = new ByteArrayOutputStream();
        ZipArkivoStreamingWriter linkWriter = ZipArkivoStreamingWriter.open(linkArchive);
        var linkEntry = linkWriter.beginSymbolicLink("secret-link", "target");
        Objects.requireNonNull(linkEntry.attributeView(ZipArkivoEntryAttributeView.class)).setEncryption(encryption);
        IOException linkFailure = assertThrows(IOException.class, linkEntry::close);
        assertTrue(linkFailure.getMessage().contains("requires a password"));
        IOException linkCloseFailure = assertThrows(IOException.class, linkWriter::close);
        assertTrue(linkCloseFailure.getMessage().contains("requires a password"));
        assertArrayEquals(expectedArchive, linkArchive.toByteArray());
    }

    /// Creates the canonical empty streaming ZIP output used to detect premature entry headers.
    private static byte @Unmodifiable [] emptyArchive() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipArkivoStreamingWriter ignored = ZipArkivoStreamingWriter.open(output)) {
            // Closing publishes the empty archive trailer.
        }
        return output.toByteArray();
    }
}
