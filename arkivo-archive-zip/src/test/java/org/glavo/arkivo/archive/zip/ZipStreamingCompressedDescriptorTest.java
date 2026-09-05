// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
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
    @ParameterizedTest(name = "{0} with {1}")
    @MethodSource("descriptorConfigurations")
    public void readsWriterProducedDescriptor(ZipMethod method, ZipEncryption encryption) throws IOException {
        Path archivePath = createTemporaryArchivePath("compressed-descriptor-");
        byte @Unmodifiable [] content = ("descriptor content for " + method + " and " + encryption + "\n")
                .repeat(128)
                .getBytes(StandardCharsets.UTF_8);
        byte @Unmodifiable [] followingContent = "following stored entry".getBytes(StandardCharsets.UTF_8);

        try {
            writeDescriptorArchive(archivePath, method, encryption, content, followingContent);

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
    @ParameterizedTest(name = "{0} with {1}")
    @MethodSource("descriptorMismatchConfigurations")
    public void rejectsDescriptorCrcMismatchWithoutLosingFollowingEntry(
            ZipMethod method,
            ZipEncryption encryption
    ) throws IOException {
        Path archivePath = createTemporaryArchivePath("corrupt-compressed-descriptor-");
        byte @Unmodifiable [] content = ("corrupt descriptor content for " + method + " and " + encryption + "\n")
                .repeat(128)
                .getBytes(StandardCharsets.UTF_8);
        byte @Unmodifiable [] followingContent = "following stored entry".getBytes(StandardCharsets.UTF_8);

        try {
            writeDescriptorArchive(archivePath, method, encryption, content, followingContent);
            byte @Unmodifiable [] archive = tamperFirstDataDescriptorCrc(Files.readAllBytes(archivePath));
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

    /// Returns the complete compressed-method and encryption cross-product supported by streaming descriptors.
    private static Stream<Arguments> descriptorConfigurations() {
        return Stream.of(ZipMethod.BZIP2, ZipMethod.LZMA, ZipMethod.ZSTANDARD, ZipMethod.XZ)
                .flatMap(method -> Stream.of(
                        ZipEncryption.NONE,
                        ZipEncryption.ZIP_CRYPTO,
                        ZipEncryption.WINZIP_AES_256
                ).map(encryption -> Arguments.of(method, encryption)));
    }

    /// Returns combinations whose end-delimited decoder paths validate CRC-32 after producing output.
    private static Stream<Arguments> descriptorMismatchConfigurations() {
        return Stream.of(
                Arguments.of(ZipMethod.XZ, ZipEncryption.NONE),
                Arguments.of(ZipMethod.ZSTANDARD, ZipEncryption.NONE),
                Arguments.of(ZipMethod.ZSTANDARD, ZipEncryption.ZIP_CRYPTO),
                Arguments.of(ZipMethod.ZSTANDARD, ZipEncryption.WINZIP_AES_256),
                Arguments.of(ZipMethod.BZIP2, ZipEncryption.NONE),
                Arguments.of(ZipMethod.BZIP2, ZipEncryption.ZIP_CRYPTO),
                Arguments.of(ZipMethod.BZIP2, ZipEncryption.WINZIP_AES_256)
        );
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
