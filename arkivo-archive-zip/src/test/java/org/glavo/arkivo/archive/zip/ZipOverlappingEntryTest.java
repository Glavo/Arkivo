// Copyright (c) 2026 Glavo
// Fixtures adapted from CPython 3.14.0's test_zipfile/test_core.py; see LICENSES/Python.txt.
// SPDX-License-Identifier: MPL-2.0 AND PSF-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.internal.ReadOnlyByteArrayChannel;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HexFormat;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Checks overlapping physical records without treating central-directory enumeration as local validation.
@NotNullByDefault
final class ZipOverlappingEntryTest {
    /// Reads both central names independently when they alias one local record.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void readsSharedHeaderUnderEachCentralName(boolean firstMatches) throws IOException {
        byte[] bytes = sharedHeader();
        if (firstMatches) bytes[30] = 'a';
        try (var fs = ZipArkivoFileSystem.open(new ReadOnlyByteArrayChannel(bytes))) {
            byte[] first = Files.readAllBytes(fs.getPath("a"));
            assertEquals(1033, first.length);
            assertArrayEquals(first, Files.readAllBytes(fs.getPath("b")));
            assertEquals("a", Files.readAttributes(fs.getPath("a"), ZipArkivoEntryAttributes.class).path());
            assertEquals("b", Files.readAttributes(fs.getPath("b"), ZipArkivoEntryAttributes.class).path());
        }
    }

    /// Checks each alias's CRC independently without poisoning another alias's cached local metadata.
    @Test
    void validatesEachAliasAgainstItsOwnCentralCrc() throws IOException {
        byte[] bytes = sharedHeader();
        int centralOffset = ByteArrayAccess.readIntLittleEndian(bytes, bytes.length - 6);
        ByteArrayAccess.writeIntLittleEndian(bytes, centralOffset + 16, 0);
        try (var fs = ZipArkivoFileSystem.open(new ReadOnlyByteArrayChannel(bytes))) {
            assertThrows(IOException.class, () -> Files.readAllBytes(fs.getPath("a")));
            assertEquals(1033, Files.readAllBytes(fs.getPath("b")).length);
            assertThrows(IOException.class, () -> Files.readAllBytes(fs.getPath("a")));
        }
    }

    /// Rejects duplicate logical names even when both directory entries refer to identical data.
    @Test
    void rejectsDuplicateNameOnSharedHeader() {
        byte[] bytes = sharedHeader();
        bytes[30] = 'a';
        bytes[140] = 'a';
        assertThrows(IOException.class, () -> {
            try (var fs = ZipArkivoFileSystem.open(new ReadOnlyByteArrayChannel(bytes))) {
                Files.readAllBytes(fs.getPath("a"));
            }
        });
    }

    /// Rejects an entry enclosing another local header without disabling the enclosed entry.
    @ParameterizedTest
    @CsvSource({"0,false,0", "0,true,0", "23,false,0", "23,true,0",
            "100,false,1", "100,true,1", "100,false,2", "100,true,2", "100,false,3"})
    void rejectsQuotedOverlap(int prefix, boolean reverseDirectory, int absoluteOffsets) throws IOException {
        byte[] original = quotedOverlap();
        if ((absoluteOffsets & 1) != 0) ByteArrayAccess.writeIntLittleEndian(original, 83 + 42, prefix);
        if ((absoluteOffsets & 2) != 0) ByteArrayAccess.writeIntLittleEndian(original, 130 + 42, prefix + 36);
        if (reverseDirectory) {
            byte[] first = java.util.Arrays.copyOfRange(original, 83, 130);
            System.arraycopy(original, 130, original, 83, 47);
            System.arraycopy(first, 0, original, 130, 47);
        }
        byte[] archive = new byte[prefix + original.length];
        System.arraycopy(original, 0, archive, prefix, original.length);
        try (var fs = ZipArkivoFileSystem.open(new ReadOnlyByteArrayChannel(archive))) {
            assertThrows(IOException.class, () -> Files.readAttributes(fs.getPath("a"), ZipArkivoEntryAttributes.class));
            assertThrows(IOException.class, () -> Files.readAllBytes(fs.getPath("a")));
            assertEquals(1033, Files.readAllBytes(fs.getPath("b")).length);
        }
    }

    /// Rejects a descriptor whose bytes are actually the following local header, as in CPython's partial-overlap case.
    @Test
    void rejectsDescriptorOverlappingNextHeader() throws IOException {
        byte[] archive = storedArchive();
        ByteArrayAccess.writeShortLittleEndian(archive, 6, (short) 8);
        ByteArrayAccess.writeIntLittleEndian(archive, 14, 0);
        ByteArrayAccess.writeIntLittleEndian(archive, 18, 0);
        ByteArrayAccess.writeIntLittleEndian(archive, 22, 0);
        int central = ByteArrayAccess.readIntLittleEndian(archive, archive.length - 6);
        ByteArrayAccess.writeShortLittleEndian(archive, central + 8, (short) 8);
        try (var fs = ZipArkivoFileSystem.open(new ReadOnlyByteArrayChannel(archive))) {
            assertThrows(IOException.class, () -> Files.readAllBytes(fs.getPath("a")));
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(fs.getPath("b")));
        }
    }

    /// External truncation violates source stability but must still report an I/O failure rather than return success.
    @Test
    void reportsTruncationAfterEntryOpen(@TempDir Path directory) throws IOException {
        Path archive = Files.write(directory.resolve("truncated.zip"), storedArchive());
        try (var fs = ZipArkivoFileSystem.open(archive);
             var input = Files.newInputStream(fs.getPath("a"));
             var mutator = Files.newByteChannel(archive, StandardOpenOption.WRITE)) {
            mutator.truncate(32);
            assertThrows(IOException.class, input::readAllBytes);
        }
    }

    /// Creates two adjacent stored records with known content and no descriptors.
    private static byte[] storedArchive() throws IOException {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            byte[] content = {1, 2, 3};
            var crc = new CRC32();
            crc.update(content);
            for (String name : new String[]{"a", "b"}) {
                var entry = new ZipEntry(name);
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(content.length);
                entry.setCrc(crc.getValue());
                zip.putNextEntry(entry);
                zip.write(content);
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    /// Rejects local records located in the directory or the archive comment, including during deferred validation.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsMetadataAsLocalRecord(boolean inComment) {
        byte[] bytes = inComment ? commentOverlap() : centralOverlap();
        assertThrows(IOException.class, () -> {
            try (var fs = ZipArkivoFileSystem.open(new ReadOnlyByteArrayChannel(bytes))) {
                Files.readAllBytes(fs.getPath("a"));
            }
        });
    }

    /// Does not publish copied invalid local records when an otherwise unrelated entry changes.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void updateValidatesRetainedRecords(boolean quoted, @TempDir Path directory) throws IOException {
        byte[] original = quoted ? quotedOverlap() : sharedHeader();
        Path archive = Files.write(directory.resolve("overlap.zip"), original);
        assertThrows(IOException.class, () -> {
            try (var fs = ZipArkivoFileSystem.update(archive)) {
                Files.writeString(fs.getPath("added"), "new");
            }
        });
        assertArrayEquals(original, Files.readAllBytes(archive));
    }

    /// Returns CPython's two central names referencing the same local header named b.
    private static byte[] sharedHeader() {
        return HexFormat.of().parseHex(
                "504b0304140000000800a06c4805e21e38bb10000000090400000100000062edc08108000000c030d6fb4b5c640b6050"
                + "4b01021400140000000800a06c4805e21e38bb100000000904000001000000000000000000000000000000000061504b"
                + "01021400140000000800a06c4805e21e38bb100000000904000001000000000000000000000000000000000062504b05"
                + "0600000000020002005e0000002f0000000000");
    }

    /// Returns CPython's local header embedded in the preceding entry's compressed bytes.
    private static byte[] quotedOverlap() {
        return HexFormat.of().parseHex(
                "504b0304140000000800a06c480559fc380434000000280400000100000061001f00e0ff504b0304140000000800a06c"
                + "4805e21e38bb10000000090400000100000062edc08108000000c030d6fb4b5c640b60504b01021400140000000800a0"
                + "6c480559fc3804340000002804000001000000000000000000000000000000000061504b01021400140000000800a06c"
                + "4805e21e38bb100000000904000001000000000000000000000000002400000062504b050600000000020002005e0000"
                + "00530000000000");
    }

    /// Returns CPython's central record pointing to itself instead of a local record.
    private static byte[] centralOverlap() {
        return HexFormat.of().parseHex(
                "504b01021403140000000800475f7c5ae21e38bb0b00000009040000010000000000000000000000b481000000006150"
                + "4b050600000000010001002f000000000000000000");
    }

    /// Returns CPython's local record embedded in the end record's comment.
    private static byte[] commentOverlap() {
        return HexFormat.of().parseHex(
                "504b01021403140000000800475f7c5ae21e38bb0b00000009040000010000000000000000000000b481450000006150"
                + "4b050600000000010001002f000000000000002a00504b0304140000000800475f7c5ae21e38bb0b0000000904000001"
                + "000000614b4c1c05a360148c780000");
    }
}

