// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar;

import org.glavo.arkivo.archive.ArchiveOptions;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests legacy RAR file-data encryption interoperability.
@NotNullByDefault
public final class RarLegacyEncryptionTest {
    /*
     * The RAR 2.02 compatibility vector below originates from the rarfile test suite.
     * Copyright (c) 2005-2024 Marko Kreen <markokr@gmail.com>
     *
     * Permission to use, copy, modify, and/or distribute this software for any purpose with or without fee is hereby
     * granted, provided that the above copyright notice and this permission notice appear in all copies.
     *
     * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE INCLUDING
     * ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL,
     * DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS,
     * WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE
     * OR PERFORMANCE OF THIS SOFTWARE.
     */
    /// A real RAR 2.02 archive from the rarfile test suite with two encrypted compressed files.
    private static final byte @Unmodifiable [] RAR20_ARCHIVE = Base64.getDecoder().decode(
            "UmFyIRoHAL0BcwIAMwAAAAAAAAD4BXUAACYACgAUNO5QEABMAAAAAAABCm2gqCufB5X+B6ujga2pgBACdAyA"
                    + "QgAgAAAABwAAAAC6fRl6bgNjPRQzCQAgAAAARklMRTEuVFhUIVF1AAAZAAwADzDeWWZpbGUxY29tbWVudFP"
                    + "mfX5zzgYruwTthRwQKva/q0wthOc1jNdnNS+MxrznJeJ0DIBCACAAAAAHAAAAAOPDX3hxA2M9FDMJACAAA"
                    + "ABGSUxFMi5UWFStZHUAABkADAAPMD1eZmlsZTJjb21tZW5005C+HUPv1L95WpV8La7DSRITBPuX1rf553c"
                    + "SqgZewKg="
    );

    /// The raw single-byte password used by the legacy archive.
    private static final byte @Unmodifiable [] PASSWORD =
            "password".getBytes(StandardCharsets.ISO_8859_1);

    /// The RAR4 archive signature.
    private static final byte @Unmodifiable [] RAR4_SIGNATURE =
            new byte[]{'R', 'a', 'r', '!', 0x1a, 0x07, 0x00};

    /// The encrypted packed body of FILE1.TXT in the real RAR 2.02 fixture.
    private static final byte @Unmodifiable [] RAR20_FIRST_CIPHERTEXT = Base64.getDecoder().decode(
            "U+Z9fnPOBiu7BO2FHBAq9r+rTC2E5zWM12c1L4zGvOc="
    );

    /// The reflected CRC32 lookup table used only by the independent RAR15 fixture encoder.
    private static final int @Unmodifiable [] CRC_TABLE = createCrcTable();

    /// Reads both compressed members from a real RAR 2.02 encrypted archive.
    @Test
    public void readsRealRar20EncryptedArchive() throws IOException {
        Map<String, Object> environment = Map.of(
                RarArkivoFileSystem.PASSWORD_PROVIDER.key(),
                ArkivoPasswordProvider.fixed(PASSWORD)
        );
        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(
                new ByteArrayInputStream(RAR20_ARCHIVE),
                ArchiveOptions.fromEnvironment(environment)
        )) {
            assertEquals(true, reader.next());
            RarArkivoEntryAttributes first = reader.readAttributes(RarArkivoEntryAttributes.class);
            assertEquals("FILE1.TXT", first.path());
            assertEquals(true, first.isEncrypted());
            assertEquals(3, first.compressionMethod());
            try (var input = reader.openInputStream()) {
                assertArrayEquals("file1\r\n".getBytes(StandardCharsets.US_ASCII), input.readAllBytes());
            }

            assertEquals(true, reader.next());
            RarArkivoEntryAttributes second = reader.readAttributes(RarArkivoEntryAttributes.class);
            assertEquals("FILE2.TXT", second.path());
            assertEquals(true, second.isEncrypted());
            assertEquals(3, second.compressionMethod());
            try (var input = reader.openInputStream()) {
                assertArrayEquals("file2\r\n".getBytes(StandardCharsets.US_ASCII), input.readAllBytes());
            }
            assertEquals(false, reader.next());
        }
    }

    /// Reads a synthetic RAR 1.3 encrypted stored member.
    @Test
    public void readsRar13EncryptedStoredEntry() throws IOException {
        byte[] content = "RAR 1.3 encrypted stored content".getBytes(StandardCharsets.US_ASCII);
        byte[] archive = rar4Volume(
                true,
                new Rar4Part(
                        "rar13.txt",
                        13,
                        0,
                        content.length,
                        crc32(content),
                        encryptRar13(content, PASSWORD),
                        true,
                        false,
                        false
                )
        );

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(
                new ByteArrayInputStream(archive),
                ArchiveOptions.fromEnvironment(passwordEnvironment(PASSWORD))
        )) {
            assertEquals(true, reader.next());
            RarArkivoEntryAttributes attributes = reader.readAttributes(RarArkivoEntryAttributes.class);
            assertEquals(0, attributes.compressionMethod());
            assertEquals(true, attributes.isEncrypted());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            assertEquals(false, reader.next());
        }
    }

    /// Reads a synthetic RAR 1.5 encrypted stored member through the channel entry point.
    @Test
    public void readsRar15EncryptedStoredEntryFromChannel() throws IOException {
        byte[] content = "RAR 1.5 encrypted stored content".getBytes(StandardCharsets.US_ASCII);
        byte[] archive = rar4Volume(
                true,
                new Rar4Part(
                        "rar15.txt",
                        15,
                        0,
                        content.length,
                        crc32(content),
                        cryptRar15(content, PASSWORD),
                        true,
                        false,
                        false
                )
        );

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(
                Channels.newChannel(new ByteArrayInputStream(archive)),
                ArchiveOptions.fromEnvironment(passwordEnvironment(PASSWORD))
        )) {
            assertEquals(true, reader.next());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            assertEquals(false, reader.next());
        }
    }

    /// Rejects a wrong password while decoding the real RAR 2.02 encrypted member.
    @Test
    public void rejectsWrongRar20Password() throws IOException {
        byte[] wrongPassword = "incorrect".getBytes(StandardCharsets.ISO_8859_1);
        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(
                new ByteArrayInputStream(RAR20_ARCHIVE),
                ArchiveOptions.fromEnvironment(passwordEnvironment(wrongPassword))
        )) {
            assertEquals(true, reader.next());
            var input = reader.openInputStream();
            IOException exception = assertThrows(IOException.class, input::readAllBytes);
            assertEquals(true, exception.getMessage() != null && !exception.getMessage().isEmpty());
            assertThrows(IOException.class, input::close);
        }
    }

    /// Reads the real RAR 2.02 encrypted archive through the indexed file-system API.
    @Test
    public void readsRealRar20EncryptedArchiveFromFileSystem(@TempDir Path temporaryDirectory) throws IOException {
        Path archivePath = temporaryDirectory.resolve("rar202-comment-psw.rar");
        Files.write(archivePath, RAR20_ARCHIVE);

        try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(
                archivePath,
                ArchiveOptions.fromEnvironment(passwordEnvironment(PASSWORD))
        )) {
            assertArrayEquals(
                    "file1\r\n".getBytes(StandardCharsets.US_ASCII),
                    Files.readAllBytes(fileSystem.getPath("/FILE1.TXT"))
            );
            assertArrayEquals(
                    "file2\r\n".getBytes(StandardCharsets.US_ASCII),
                    Files.readAllBytes(fileSystem.getPath("/FILE2.TXT"))
            );
        }
    }

    /// Keeps one RAR20 block-cipher stream alive across an unaligned physical volume boundary.
    @Test
    public void readsRar20EncryptedCompressedEntryAcrossVolumes(@TempDir Path temporaryDirectory) throws IOException {
        byte[] content = "file1\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] afterContent = "after".getBytes(StandardCharsets.US_ASCII);
        int splitOffset = 7;
        byte[] firstCiphertext = Arrays.copyOfRange(RAR20_FIRST_CIPHERTEXT, 0, splitOffset);
        byte[] secondCiphertext = Arrays.copyOfRange(
                RAR20_FIRST_CIPHERTEXT,
                splitOffset,
                RAR20_FIRST_CIPHERTEXT.length
        );

        Path firstVolume = temporaryDirectory.resolve("legacy.part1.rar");
        Path secondVolume = temporaryDirectory.resolve("legacy.part2.rar");
        Files.write(
                firstVolume,
                rar4Volume(
                        false,
                        new Rar4Part(
                                "FILE1.TXT",
                                20,
                                3,
                                content.length,
                                crc32(firstCiphertext),
                                firstCiphertext,
                                true,
                                false,
                                true
                        )
                )
        );
        Files.write(
                secondVolume,
                rar4Volume(
                        true,
                        new Rar4Part(
                                "FILE1.TXT",
                                20,
                                3,
                                content.length,
                                crc32(content),
                                secondCiphertext,
                                true,
                                true,
                                false
                        ),
                        new Rar4Part(
                                "after.txt",
                                20,
                                0,
                                afterContent.length,
                                crc32(afterContent),
                                afterContent,
                                false,
                                false,
                                false
                        )
                )
        );

        try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(
                ArkivoVolumeSource.of(List.of(firstVolume, secondVolume)),
                ArchiveOptions.fromEnvironment(passwordEnvironment(PASSWORD))
        )) {
            assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/FILE1.TXT")));
            assertArrayEquals(afterContent, Files.readAllBytes(fileSystem.getPath("/after.txt")));
        }
    }

    /// Creates one complete synthetic RAR4 volume.
    private static byte[] rar4Volume(boolean includeEndHeader, Rar4Part... parts) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(RAR4_SIGNATURE);
        output.write(rar4BlockHeader(0x73, 0L, new byte[6]));
        for (Rar4Part part : parts) {
            output.write(rar4PartHeader(part));
            output.write(part.body());
        }
        if (includeEndHeader) {
            output.write(rar4BlockHeader(0x7b, 0L, new byte[0]));
        }
        return output.toByteArray();
    }

    /// Encodes one synthetic RAR4 file-part header.
    private static byte[] rar4PartHeader(Rar4Part part) throws IOException {
        byte[] name = part.path().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream fields = new ByteArrayOutputStream();
        writeUInt32(fields, part.body().length);
        writeUInt32(fields, part.unpackedSize());
        fields.write(3);
        writeUInt32(fields, part.crc32());
        writeUInt32(fields, 0L);
        fields.write(part.extractionVersion());
        fields.write(0x30 + part.compressionMethod());
        writeUInt16(fields, name.length);
        writeUInt32(fields, 0100644);
        fields.write(name);

        long flags = 0x8000L;
        if (part.continuesFromPreviousVolume()) {
            flags |= 0x0001L;
        }
        if (part.continuesInNextVolume()) {
            flags |= 0x0002L;
        }
        if (part.encrypted()) {
            flags |= 0x0004L;
        }
        return rar4BlockHeader(0x74, flags, fields.toByteArray());
    }

    /// Encodes one complete RAR4 block header including its CRC16 field.
    private static byte[] rar4BlockHeader(int type, long flags, byte[] fields) throws IOException {
        ByteArrayOutputStream headerData = new ByteArrayOutputStream();
        headerData.write(type);
        writeUInt16(headerData, flags);
        writeUInt16(headerData, 7 + fields.length);
        headerData.write(fields);

        byte[] protectedData = headerData.toByteArray();
        CRC32 crc32 = new CRC32();
        crc32.update(protectedData, 0, protectedData.length);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeUInt16(output, crc32.getValue());
        output.write(protectedData);
        return output.toByteArray();
    }

    /// Encrypts plaintext with the inverse of the RAR 1.3 byte decryptor.
    private static byte[] encryptRar13(byte[] plaintext, byte[] password) {
        byte[] ciphertext = plaintext.clone();
        int key0 = 0;
        int key1 = 0;
        int key2 = 0;
        for (byte value : password) {
            int unsigned = Byte.toUnsignedInt(value);
            key0 = key0 + unsigned & 0xff;
            key1 ^= unsigned;
            key2 = key2 + unsigned & 0xff;
            key2 = (key2 << 1 | key2 >>> 7) & 0xff;
        }
        for (int index = 0; index < ciphertext.length; index++) {
            key1 = key1 + key2 & 0xff;
            key0 = key0 + key1 & 0xff;
            ciphertext[index] = (byte) (Byte.toUnsignedInt(ciphertext[index]) + key0);
        }
        return ciphertext;
    }

    /// Applies the symmetric RAR 1.5 stream cipher to fixture bytes.
    private static byte[] cryptRar15(byte[] input, byte[] password) {
        int passwordCrc = 0xffff_ffff;
        for (byte value : password) {
            passwordCrc = CRC_TABLE[(passwordCrc ^ value) & 0xff] ^ passwordCrc >>> 8;
        }

        int key0 = passwordCrc & 0xffff;
        int key1 = passwordCrc >>> 16 & 0xffff;
        int key2 = 0;
        int key3 = 0;
        for (byte value : password) {
            int unsigned = Byte.toUnsignedInt(value);
            int crc = CRC_TABLE[unsigned];
            key2 = (key2 ^ unsigned ^ crc) & 0xffff;
            key3 = key3 + unsigned + (crc >>> 16) & 0xffff;
        }

        byte[] output = input.clone();
        for (int index = 0; index < output.length; index++) {
            key0 = key0 + 0x1234 & 0xffff;
            int crc = CRC_TABLE[(key0 & 0x1fe) >>> 1];
            key1 = (key1 ^ crc) & 0xffff;
            key2 = key2 - (crc >>> 16) & 0xffff;
            key0 = (key0 ^ key2) & 0xffff;
            key3 = (rotateRight16(key3) ^ key1) & 0xffff;
            key3 = rotateRight16(key3);
            key0 = (key0 ^ key3) & 0xffff;
            output[index] = (byte) (output[index] ^ key0 >>> 8);
        }
        return output;
    }

    /// Builds the reflected CRC32 table used by the RAR 1.5 fixture cipher.
    private static int[] createCrcTable() {
        int[] table = new int[256];
        for (int index = 0; index < table.length; index++) {
            int value = index;
            for (int bit = 0; bit < Byte.SIZE; bit++) {
                value = (value & 1) != 0 ? value >>> 1 ^ 0xedb8_8320 : value >>> 1;
            }
            table[index] = value;
        }
        return table;
    }

    /// Rotates one unsigned 16-bit fixture key right by one bit.
    private static int rotateRight16(int value) {
        return (value >>> 1 | value << 15) & 0xffff;
    }

    /// Writes one little-endian unsigned 32-bit value.
    private static void writeUInt32(ByteArrayOutputStream output, long value) {
        output.write((byte) value);
        output.write((byte) (value >>> 8));
        output.write((byte) (value >>> 16));
        output.write((byte) (value >>> 24));
    }

    /// Writes one little-endian unsigned 16-bit value.
    private static void writeUInt16(ByteArrayOutputStream output, long value) {
        output.write((byte) value);
        output.write((byte) (value >>> 8));
    }

    /// Returns the unsigned CRC32 of fixture bytes.
    private static long crc32(byte[] data) {
        CRC32 crc32 = new CRC32();
        crc32.update(data, 0, data.length);
        return crc32.getValue();
    }

    /// Returns a fixed archive-level legacy password environment.
    private static Map<String, Object> passwordEnvironment(byte[] password) {
        return Map.of(
                RarArkivoFileSystem.PASSWORD_PROVIDER.key(),
                ArkivoPasswordProvider.fixed(password)
        );
    }

    /// Stores one synthetic RAR4 physical file part.
    ///
    /// @param path entry path
    /// @param extractionVersion extraction version selecting compression and encryption algorithms
    /// @param compressionMethod normalized compression method
    /// @param unpackedSize logical unpacked size of the complete entry
    /// @param crc32 header CRC32 value for this physical part
    /// @param body packed bytes in this physical part
    /// @param encrypted whether the packed bytes are encrypted
    /// @param continuesFromPreviousVolume whether this part continues an earlier volume
    /// @param continuesInNextVolume whether this part continues in a later volume
    @NotNullByDefault
    private record Rar4Part(
            String path,
            int extractionVersion,
            int compressionMethod,
            long unpackedSize,
            long crc32,
            byte @Unmodifiable [] body,
            boolean encrypted,
            boolean continuesFromPreviousVolume,
            boolean continuesInNextVolume
    ) {
        /// Creates one immutable synthetic physical part.
        private Rar4Part {
            Objects.requireNonNull(path, "path");
            if (extractionVersion < 0 || extractionVersion > 0xff) {
                throw new IllegalArgumentException("extractionVersion must fit in one byte");
            }
            if (compressionMethod < 0 || compressionMethod > 5) {
                throw new IllegalArgumentException("compressionMethod must be between 0 and 5");
            }
            if (unpackedSize < 0L) {
                throw new IllegalArgumentException("unpackedSize must not be negative");
            }
            body = body.clone();
        }
    }
}
