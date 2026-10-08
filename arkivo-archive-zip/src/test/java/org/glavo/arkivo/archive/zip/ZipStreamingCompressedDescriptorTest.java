// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.glavo.arkivo.archive.zip.ZipTestArchiveFixtures.createTemporaryArchivePath;
import static org.glavo.arkivo.archive.zip.ZipTestArchiveFixtures.deleteTemporaryArchive;
import static org.glavo.arkivo.archive.zip.ZipTestArchiveFixtures.tamperFirstDataDescriptorCrc;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies writer-produced data descriptors across compressed ZIP methods and encryption schemes.
@NotNullByDefault
public final class ZipStreamingCompressedDescriptorTest {
    /// The ZIP LZMA general-purpose flag indicating an end-of-stream marker.
    private static final int LZMA_EOS_MARKER_FLAG = 1 << 1;

    /// The password shared by encrypted descriptor cases.
    private static final byte @Unmodifiable [] PASSWORD =
            "descriptor secret".getBytes(StandardCharsets.UTF_8);

    /// Reads a compressed data-descriptor entry and the stored entry immediately following it.
    @ParameterizedTest(name = "{0} with {1}, AE-2={2}")
    @MethodSource("descriptorConfigurations")
    public void readsWriterProducedDescriptor(ZipMethod method, ZipEncryption encryption, boolean ae2) throws IOException {
        Path archivePath = createTemporaryArchivePath("compressed-descriptor-");
        byte @Unmodifiable [] content = ("descriptor content for " + method + " and " + encryption + "\n")
                .repeat(128)
                .getBytes(StandardCharsets.UTF_8);
        byte @Unmodifiable [] followingContent = "following stored entry".getBytes(StandardCharsets.UTF_8);

        try {
            writeDescriptorArchive(archivePath, method, encryption, content, followingContent);
            if (ae2) {
                Files.write(archivePath, withAe2FirstEntry(Files.readAllBytes(archivePath)));
            }

            ZipArchiveOptions.Read readOptions = ZipArchiveOptions.READ_DEFAULTS
                    .withPasswordProvider(ArkivoPasswordProvider.fixed(PASSWORD));
            byte @Unmodifiable [] archive = Files.readAllBytes(archivePath);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    readOptions
            )) {
                assertTrue(reader.next());
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("compressed.bin", attributes.path());
                assertEquals(method, attributes.compressionMethod());
                assertEquals(encryption, attributes.encryption());
                assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, attributes.compressedSize());
                assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, attributes.size());
                if (method == ZipMethod.LZMA) {
                    assertTrue((attributes.generalPurposeFlags() & LZMA_EOS_MARKER_FLAG) != 0);
                }
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(content, input.readAllBytes());
                }

                assertTrue(reader.next());
                ZipArkivoEntryAttributes followingAttributes =
                        reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("following.txt", followingAttributes.path());
                assertEquals(ZipMethod.STORED, followingAttributes.compressionMethod());
                assertEquals(ZipEncryption.NONE, followingAttributes.encryption());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(followingContent, input.readAllBytes());
                }
                assertFalse(reader.next());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Rejects a corrupt descriptor without consuming the following local file record.
    @ParameterizedTest(name = "{0} with {1}, AE-2={2}")
    @MethodSource("descriptorConfigurations")
    public void rejectsDescriptorCrcMismatchWithoutLosingFollowingEntry(
            ZipMethod method,
            ZipEncryption encryption,
            boolean ae2
    ) throws IOException {
        Path archivePath = createTemporaryArchivePath("corrupt-compressed-descriptor-");
        byte @Unmodifiable [] content = ("corrupt descriptor content for " + method + " and " + encryption + "\n")
                .repeat(128)
                .getBytes(StandardCharsets.UTF_8);
        byte @Unmodifiable [] followingContent = "following stored entry".getBytes(StandardCharsets.UTF_8);

        try {
            writeDescriptorArchive(archivePath, method, encryption, content, followingContent);
            byte[] original = Files.readAllBytes(archivePath);
            byte @Unmodifiable [] archive = tamperFirstDataDescriptorCrc(ae2 ? withAe2FirstEntry(original) : original);
            ZipArchiveOptions.Read readOptions = ZipArchiveOptions.READ_DEFAULTS
                    .withPasswordProvider(ArkivoPasswordProvider.fixed(PASSWORD));

            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    readOptions
            )) {
                assertTrue(reader.next());
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals(method, attributes.compressionMethod());
                assertEquals(encryption, attributes.encryption());
                var input = reader.openInputStream();
                IOException exception = assertThrows(IOException.class, input::readAllBytes);
                assertTrue(exception.getMessage().contains("data descriptor does not match"));
                input.close();

                assertTrue(reader.next());
                ZipArkivoEntryAttributes followingAttributes =
                        reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("following.txt", followingAttributes.path());
                assertEquals(ZipMethod.STORED, followingAttributes.compressionMethod());
                assertEquals(ZipEncryption.NONE, followingAttributes.encryption());
                try (var followingInput = reader.openInputStream()) {
                    assertArrayEquals(followingContent, followingInput.readAllBytes());
                }
                assertFalse(reader.next());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Requires AE-1 plaintext CRC validation even when header and descriptor CRCs agree with one another.
    @ParameterizedTest
    @EnumSource(value = ZipMethod.class, names = {"STORED", "DEFLATED", "DEFLATE64", "BZIP2", "LZMA", "ZSTANDARD", "XZ"})
    void rejectsAe1BodyCrcMismatch(ZipMethod method) throws IOException {
        Path archivePath = createTemporaryArchivePath("aes-body-crc-");
        byte[] content = "Authenticated ciphertext cannot replace the AE-1 plaintext CRC check."
                .repeat(16).getBytes(StandardCharsets.UTF_8);
        var options = ZipArchiveOptions.READ_DEFAULTS.withPasswordProvider(ArkivoPasswordProvider.fixed(PASSWORD));
        try {
            writeDescriptorArchive(archivePath, method, ZipEncryption.WINZIP_AES_256, content, new byte[]{42});
            try (var fileSystem = ZipArkivoFileSystem.open(archivePath, options)) {
                assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/compressed.bin")));
            }
            byte[] bytes = Files.readAllBytes(archivePath);
            int central = ByteArrayAccess.readIntLittleEndian(bytes, bytes.length - 22 + 16);
            int compressedSize = ByteArrayAccess.readIntLittleEndian(bytes, central + 20);
            int data = 30 + Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(bytes, 26))
                    + Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(bytes, 28));
            int changedCrc = ByteArrayAccess.readIntLittleEndian(bytes, central + 16) ^ 1;
            assertEquals(0x08074b50, ByteArrayAccess.readIntLittleEndian(bytes, data + compressedSize));
            ByteArrayAccess.writeIntLittleEndian(bytes, central + 16, changedCrc);
            ByteArrayAccess.writeIntLittleEndian(bytes, data + compressedSize + 4, changedCrc);
            Files.write(archivePath, bytes);
            try (var fileSystem = ZipArkivoFileSystem.open(archivePath, options)) {
                IOException failure = assertThrows(IOException.class,
                        () -> Files.readAllBytes(fileSystem.getPath("/compressed.bin")));
                assertEquals("ZIP entry data does not match central directory", failure.getMessage());
            }

            // Give the streaming reader explicit sizes, exercising its non-descriptor validation path.
            short flags = ByteArrayAccess.readShortLittleEndian(bytes, 6);
            ByteArrayAccess.writeShortLittleEndian(bytes, 6, (short) (flags & ~(1 << 3)));
            ByteArrayAccess.writeIntLittleEndian(bytes, 14, changedCrc);
            ByteArrayAccess.writeIntLittleEndian(bytes, 18, compressedSize);
            ByteArrayAccess.writeIntLittleEndian(bytes, 22, content.length);
            try (var reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(bytes), options)) {
                assertTrue(reader.next());
                IOException failure = assertThrows(IOException.class, () -> {
                    try (var input = reader.openInputStream()) {
                        input.readAllBytes();
                    }
                });
                assertEquals("ZIP entry data does not match local header", failure.getMessage());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Returns the complete compressed-method and encryption cross-product supported by streaming descriptors.
    private static Stream<Arguments> descriptorConfigurations() {
        return Stream.of(ZipMethod.STORED, ZipMethod.DEFLATED, ZipMethod.DEFLATE64,
                        ZipMethod.BZIP2, ZipMethod.LZMA, ZipMethod.ZSTANDARD, ZipMethod.XZ)
                .flatMap(method -> Stream.of(
                        ZipEncryption.NONE,
                        ZipEncryption.ZIP_CRYPTO,
                        ZipEncryption.WINZIP_AES_256
                ).flatMap(encryption -> (encryption == ZipEncryption.WINZIP_AES_256
                        ? Stream.of(false, true) : Stream.of(false))
                        .map(ae2 -> Arguments.of(method, encryption, ae2))));
    }

    /// Converts the first writer-generated AE-1 entry to AE-2 without changing its ciphertext or authentication code.
    private static byte[] withAe2FirstEntry(byte[] archive) {
        int central = ByteArrayAccess.readIntLittleEndian(archive, archive.length - 22 + 16);
        assertEquals(0x02014b50, ByteArrayAccess.readIntLittleEndian(archive, central));
        int data = 30 + Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(archive, 26))
                + Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(archive, 28));
        int descriptor = data + ByteArrayAccess.readIntLittleEndian(archive, central + 20);
        assertEquals(0x08074b50, ByteArrayAccess.readIntLittleEndian(archive, descriptor));
        ByteArrayAccess.writeIntLittleEndian(archive, 14, 0);
        ByteArrayAccess.writeIntLittleEndian(archive, central + 16, 0);
        ByteArrayAccess.writeIntLittleEndian(archive, descriptor + 4, 0);
        for (int header : new int[]{0, central}) {
            int fixedSize = header == 0 ? 30 : 46;
            int nameSize = Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(archive, header + (header == 0 ? 26 : 28)));
            int extraSize = Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(archive, header + (header == 0 ? 28 : 30)));
            int start = header + fixedSize + nameSize;
            boolean found = false;
            for (int offset = start; offset < start + extraSize;
                 offset += 4 + Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(archive, offset + 2))) {
                if (Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(archive, offset)) == 0x9901) {
                    assertEquals(1, ByteArrayAccess.readShortLittleEndian(archive, offset + 4));
                    ByteArrayAccess.writeShortLittleEndian(archive, offset + 4, (short) 2);
                    found = true;
                    break;
                }
            }
            assertTrue(found);
        }
        return archive;
    }

    /// Writes one compressed descriptor entry followed by an unencrypted stored entry.
    private static void writeDescriptorArchive(
            Path archivePath,
            ZipMethod method,
            ZipEncryption encryption,
            byte @Unmodifiable [] content,
            byte @Unmodifiable [] followingContent
    ) throws IOException {
        ZipArchiveOptions.Create createOptions = ZipArchiveOptions.CREATE_DEFAULTS
                .withPasswordProvider(ArkivoPasswordProvider.fixed(PASSWORD));
        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath, createOptions)) {
            ZipArkivoStreamingWriter.Entry compressedEntry = writer.beginFile("compressed.bin");
            ZipArkivoEntryAttributeView compressedView =
                    compressedEntry.attributeView(ZipArkivoEntryAttributeView.class);
            assertNotNull(compressedView);
            compressedView.setMethod(method);
            compressedView.setEncryption(encryption);
            try (var output = compressedEntry.openOutputStream()) {
                output.write(content);
            }

            ZipArkivoStreamingWriter.Entry followingEntry = writer.beginFile("following.txt");
            ZipArkivoEntryAttributeView followingView =
                    followingEntry.attributeView(ZipArkivoEntryAttributeView.class);
            assertNotNull(followingView);
            followingView.setMethod(ZipMethod.STORED);
            try (var output = followingEntry.openOutputStream()) {
                output.write(followingContent);
            }
        }
    }
}
