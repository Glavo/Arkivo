// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar;

import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.internal.ReadOnlyByteArrayChannel;
import org.glavo.arkivo.codec.DecompressionWindowLimitException;
import org.glavo.arkivo.codec.DecompressionMemoryLimitException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies RAR4 compressed-body interoperability through Arkivo APIs.
@NotNullByDefault
public final class Rar4CompressionTest {
    /// Applies the configured decoder budget while skipping solid predecessors and replaying indexed entries.
    @Test
    void enforcesSolidDecoderMemoryLimit() throws IOException {
        for (long maximum : new long[]{1L << 20, 2L << 20}) {
            var limits = ArchiveReadLimits.builder().maximumDecoderMemorySize(maximum).build();
            var options = new RarArchiveOptions(ArchiveReadOptions.DEFAULT.withLimits(limits));
            ByteArrayInputStream source = new ByteArrayInputStream(SOLID_ARCHIVE);
            try (var reader = RarArkivoStreamingReader.open(source, options)) {
                assertTrue(reader.next());
                if (maximum == 1L << 20) {
                    int remaining = source.available();
                    assertThrows(DecompressionMemoryLimitException.class, reader::next);
                    assertThrows(DecompressionMemoryLimitException.class, reader::next);
                    assertEquals(remaining, source.available());
                } else {
                    for (int index = 2; index <= 9; index++) assertTrue(reader.next());
                    try (InputStream input = reader.openInputStream()) {
                        assertArrayEquals("file9\n".getBytes(StandardCharsets.UTF_8), input.readAllBytes());
                    }
                }
            }
            if (maximum == 1L << 20) {
                assertThrows(DecompressionMemoryLimitException.class, () -> {
                    try (var fs = RarArkivoFileSystem.open(new ReadOnlyByteArrayChannel(SOLID_ARCHIVE), options)) {
                        Files.readAllBytes(fs.getPath("/file9.txt"));
                    }
                });
            } else {
                try (var fs = RarArkivoFileSystem.open(new ReadOnlyByteArrayChannel(SOLID_ARCHIVE), options)) {
                    assertArrayEquals("file9\n".getBytes(StandardCharsets.UTF_8),
                            Files.readAllBytes(fs.getPath("/file9.txt")));
                }
            }
        }
    }
    /// The system property containing the published RAR module JAR path.
    private static final String RAR_JAR_PROPERTY = "arkivo.rar.jar";

    /// The system property containing the Arkivo archive module JAR path.
    private static final String ARCHIVE_JAR_PROPERTY = "arkivo.archive.jar";

    /// The system property containing the Arkivo archive-codec bridge module JAR path.
    private static final String ARCHIVE_CODEC_JAR_PROPERTY = "arkivo.archive-codec.jar";

    /// The system property containing the Arkivo internal base module JAR path.
    private static final String INTERNAL_BASE_JAR_PROPERTY = "arkivo.internal-base.jar";

    /// The system property containing the published codec-core JAR path.
    private static final String CODEC_JAR_PROPERTY = "arkivo.codec.jar";

    /// The system property containing the published PPMd-codec JAR path.
    private static final String PPMD_JAR_PROPERTY = "arkivo.ppmd.jar";

    /// A RAR4 solid archive containing `file1.txt` through `file9.txt`.
    private static final byte @Unmodifiable [] SOLID_ARCHIVE = Base64.getDecoder().decode(
            "UmFyIRoHADvQcwgADQAAAAAAAACylHSAkCsAGgAAAAYAAAADBPcp4veq81AdMwkApIEAAGZpbGUxLnR4dADADQwM/hAMt2G7"
                    + "9EFqVSh/2gEYP7Diz78doSBa4XSQkCsAAwAAAAYAAAADx6QEyfeq81AdMwkApIEAAGZpbGUyLnR4dADAepIAyjN0kJArAAMA"
                    + "AAAGAAAAA4aVH9D3qvNQHTMJAKSBAABmaWxlMy50eHQAwHsSAPkEdJCQKwADAAAABgAAAANBA16f96rzUB0zCQCkgQAAZmls"
                    + "ZTQudHh0AMB7kgBp1nSQkCsAAwAAAAYAAAADADJFhveq81AdMwkApIEAAGZpbGU1LnR4dADAfBIAmKd0kJArAAMAAAAGAAAA"
                    + "A8NhaK33qvNQHTMJAKSBAABmaWxlNi50eHQAwHySAAh1dJCQKwADAAAABgAAAAOCUHO096rzUB0zCQCkgQAAZmlsZTcudHh0"
                    + "AMB9EgD+yXSQkCsAAwAAAAYAAAADTUzrM/eq81AdMwkApIEAAGZpbGU4LnR4dADAfZIAbht0kJArAAMAAAAGAAAAAwx98Cr3"
                    + "qvNQHTMJAKSBAABmaWxlOS50eHQAwH4SgMQ9ewBABwA="
    );

    /// A RAR4 archive with plain headers and one AES-encrypted compressed file body.
    private static final byte @Unmodifiable [] ENCRYPTED_BODY_ARCHIVE = Base64.getDecoder().decode(
            "UmFyIRoHAM+QcwAADQAAAAAAAADqWnQklDMAIAAAAAYAAAADBPcp4veq81AdMwkApIEAAGZpbGUxLnR4dFioM3YCpGjgAMAs"
                    + "mDgcSsnwnUn6kzM4BlpBbF+uQO0D7ORxNIz3Z4nsScQ9ewBABwA="
    );

    /// A RAR4 archive with independently encrypted headers and one compressed file body.
    private static final byte @Unmodifiable [] ENCRYPTED_HEADER_ARCHIVE = Base64.getDecoder().decode(
            "UmFyIRoHAM6Zc4AADQAAAAAAAAC5PWsXQCo7KTFMRRa743HEkQGnfYjGSpKHt825oSyxCV2WDs3/AhC15uLyC1vdqD3ZMfc1"
                    + "QT5xF6bbvZU5vlm7C1ewuX2zpB/y0IluICILdJAyPey4XXsY0qQOAhCYeXgNFqlwuBxUOrk9axdAKjspjUYtP7PIL6k2eg0g"
                    + "ENTs9Q=="
    );

    /// The UTF-16LE password used by the encrypted fixtures.
    private static final byte @Unmodifiable [] PASSWORD = "junrar".getBytes(StandardCharsets.UTF_16LE);

    /// Enforces the real solid archive's 1 MiB window while skipping predecessors and replaying indexed content.
    @Test
    void enforcesSolidWindowLimit() throws IOException {
        for (int maximum : new int[]{(1 << 20) - 1, 1 << 20}) {
            var limits = ArchiveReadLimits.builder().maximumCompressionWindowSize(maximum).build();
            var options = new RarArchiveOptions(ArchiveReadOptions.DEFAULT.withLimits(limits));
            ByteArrayInputStream source = new ByteArrayInputStream(SOLID_ARCHIVE);
            try (var reader = RarArkivoStreamingReader.open(source, options)) {
                assertTrue(reader.next());
                int remaining = source.available();
                if (maximum < 1 << 20) {
                    assertThrows(DecompressionWindowLimitException.class, reader::next);
                    assertThrows(DecompressionWindowLimitException.class, reader::next);
                    assertEquals(remaining, source.available());
                } else {
                    for (int entry = 2; entry <= 9; entry++) {
                        assertTrue(reader.next());
                    }
                    try (InputStream body = reader.openInputStream()) {
                        assertArrayEquals("file9\n".getBytes(StandardCharsets.UTF_8), body.readAllBytes());
                    }
                }
            }
            if (maximum < 1 << 20) {
                assertThrows(DecompressionWindowLimitException.class, () -> {
                    try (var fs = RarArkivoFileSystem.open(new ReadOnlyByteArrayChannel(SOLID_ARCHIVE), options)) {
                        Files.readAllBytes(fs.getPath("/file9.txt"));
                    }
                });
            } else {
                try (var fs = RarArkivoFileSystem.open(new ReadOnlyByteArrayChannel(SOLID_ARCHIVE), options)) {
                    assertArrayEquals("file9\n".getBytes(StandardCharsets.UTF_8),
                            Files.readAllBytes(fs.getPath("/file9.txt")));
                }
            }
        }
    }

    /// Accepts an encrypted compressed body with its actual 128 KiB window, not an implementation-sized minimum.
    @Test
    void acceptsEncryptedBodyAtDeclaredWindowLimit() throws IOException {
        var limits = ArchiveReadLimits.builder().maximumCompressionWindowSize(1 << 17).build();
        var options = new RarArchiveOptions(ArchiveReadOptions.DEFAULT.withLimits(limits))
                .withPasswordProvider(ArkivoPasswordProvider.fixed(PASSWORD));
        try (var reader = RarArkivoStreamingReader.open(new ByteArrayInputStream(ENCRYPTED_BODY_ARCHIVE), options)) {
            assertTrue(reader.next());
            try (InputStream body = reader.openInputStream()) {
                assertArrayEquals("file1\n".getBytes(StandardCharsets.UTF_8), body.readAllBytes());
            }
        }
    }

    /// Reads every solid entry and verifies skipped predecessors preserve decompression history.
    @Test
    public void readsSolidCompressedEntries() throws IOException {
        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(
                new ByteArrayInputStream(SOLID_ARCHIVE)
        )) {
            for (int index = 1; index <= 9; index++) {
                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                RarArkivoEntryAttributes attributes = reader.readAttributes(RarArkivoEntryAttributes.class);
                assertEquals("file" + index + ".txt", attributes.path());
                assertEquals(3, attributes.compressionMethod());
                try (InputStream input = reader.openInputStream()) {
                    assertArrayEquals(
                            ("file" + index + "\n").getBytes(StandardCharsets.UTF_8),
                            input.readAllBytes()
                    );
                }
            }
            org.junit.jupiter.api.Assertions.assertFalse(reader.next());
        }

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(
                new ByteArrayInputStream(SOLID_ARCHIVE)
        )) {
            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            assertEquals("file1.txt", reader.readAttributes(RarArkivoEntryAttributes.class).path());
            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            try (InputStream input = reader.openInputStream()) {
                assertArrayEquals("file2\n".getBytes(StandardCharsets.UTF_8), input.readAllBytes());
            }
        }
    }

    /// Preserves solid decompression history when an opened entry is only partially consumed.
    @Test
    public void closesPartiallyConsumedSolidEntryBeforeReadingNextEntry() throws IOException {
        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(
                new ByteArrayInputStream(SOLID_ARCHIVE)
        )) {
            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            InputStream first = reader.openInputStream();

            assertEquals(0, first.read(new byte[0], 0, 0));
            assertEquals('f', first.read());
            assertEquals(2L, first.skip(2L));
            assertTrue(first.available() >= 0);
            first.close();
            first.close();
            assertThrows(IOException.class, first::read);

            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            assertEquals("file2.txt", reader.readAttributes(RarArkivoEntryAttributes.class).path());
            try (InputStream second = reader.openInputStream()) {
                assertArrayEquals("file2\n".getBytes(StandardCharsets.UTF_8), second.readAllBytes());
            }
        }
    }

    /// Reads a solid archive through the indexed file system and its cached content storage.
    @Test
    public void readsSolidCompressedEntriesThroughFileSystem() throws IOException {
        Path archive = Files.createTempFile("arkivo-rar4-solid-", ".rar");
        Files.write(archive, SOLID_ARCHIVE);
        try {
            try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(archive)) {
                assertArrayEquals(
                        "file9\n".getBytes(StandardCharsets.UTF_8),
                        Files.readAllBytes(fileSystem.getPath("/file9.txt"))
                );
                assertEquals(
                        3,
                        Files.readAttributes(
                                fileSystem.getPath("/file9.txt"),
                                RarArkivoEntryAttributes.class
                        ).compressionMethod()
                );
            }
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    /// Reads AES-encrypted compressed data with both plain and encrypted RAR4 headers.
    @Test
    public void readsEncryptedCompressedEntries() throws IOException {
        byte[][] archives = {ENCRYPTED_BODY_ARCHIVE, ENCRYPTED_HEADER_ARCHIVE};
        for (int index = 0; index < archives.length; index++) {
            ByteArrayInputStream source = new ByteArrayInputStream(archives[index]);
            RarArkivoStreamingReader openedReader = index == 0
                    ? RarArkivoStreamingReader.open(source, passwordOptions(PASSWORD))
                    : RarArkivoStreamingReader.open(Channels.newChannel(source), passwordOptions(PASSWORD));
            try (RarArkivoStreamingReader reader = openedReader) {
                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                RarArkivoEntryAttributes attributes = reader.readAttributes(RarArkivoEntryAttributes.class);
                assertEquals("file1.txt", attributes.path());
                assertEquals(3, attributes.compressionMethod());
                assertTrue(attributes.isEncrypted());
                try (InputStream input = reader.openInputStream()) {
                    assertArrayEquals("file1\n".getBytes(StandardCharsets.UTF_8), input.readAllBytes());
                }
                org.junit.jupiter.api.Assertions.assertFalse(reader.next());
            }
        }
    }

    /// Reads an AES-encrypted compressed entry through the indexed file system cache.
    @Test
    public void readsEncryptedCompressedEntryThroughFileSystem() throws IOException {
        Path archive = Files.createTempFile("arkivo-rar4-encrypted-", ".rar");
        Files.write(archive, ENCRYPTED_BODY_ARCHIVE);
        try {
            try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(
                    archive,
                    passwordOptions(PASSWORD)
            )) {
                assertArrayEquals(
                        "file1\n".getBytes(StandardCharsets.UTF_8),
                        Files.readAllBytes(fileSystem.getPath("/file1.txt"))
                );
            }
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    /// Loads the published module in isolation and verifies its relocated decoder can read compressed data.
    @Test
    public void readsCompressedEntryFromPublishedModule() throws Exception {
        ModuleFinder finder = ModuleFinder.of(
                requiredPathProperty(RAR_JAR_PROPERTY),
                requiredPathProperty(ARCHIVE_JAR_PROPERTY),
                requiredPathProperty(ARCHIVE_CODEC_JAR_PROPERTY),
                requiredPathProperty(INTERNAL_BASE_JAR_PROPERTY),
                requiredPathProperty(CODEC_JAR_PROPERTY),
                requiredPathProperty(PPMD_JAR_PROPERTY)
        );
        Configuration configuration = ModuleLayer.boot().configuration().resolve(
                finder,
                ModuleFinder.of(),
                Set.of("org.glavo.arkivo.archive.rar")
        );
        ModuleLayer layer = ModuleLayer.defineModulesWithOneLoader(
                configuration,
                List.of(ModuleLayer.boot()),
                ClassLoader.getPlatformClassLoader()
        ).layer();
        ClassLoader loader = layer.findLoader("org.glavo.arkivo.archive.rar");
        Class<?> readerType = Class.forName("org.glavo.arkivo.archive.rar.RarArkivoStreamingReader", true, loader);
        assertEquals("org.glavo.arkivo.archive.rar", readerType.getModule().getName());

        Object reader = readerType.getMethod("open", InputStream.class).invoke(
                null,
                new ByteArrayInputStream(SOLID_ARCHIVE)
        );
        try {
            assertEquals(true, readerType.getMethod("next").invoke(reader));
            try (InputStream input = (InputStream) readerType.getMethod("openInputStream").invoke(reader)) {
                assertArrayEquals("file1\n".getBytes(StandardCharsets.UTF_8), input.readAllBytes());
            }
        } finally {
            readerType.getMethod("close").invoke(reader);
        }
    }

    /// Rejects a wrong RAR3 password through decompression or plaintext CRC validation.
    @Test
    public void rejectsIncorrectCompressedEntryPassword() {
        assertThrows(IOException.class, () -> {
            try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(
                    new ByteArrayInputStream(ENCRYPTED_BODY_ARCHIVE),
                    passwordOptions("wrong".getBytes(StandardCharsets.UTF_16LE))
            )) {
                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                try (InputStream input = reader.openInputStream()) {
                    input.readAllBytes();
                }
            }
        });
    }

    /// Returns read options containing one fixed UTF-16LE RAR3 password provider.
    private static RarArchiveOptions passwordOptions(byte[] password) {
        return RarArchiveOptions.DEFAULT.withPasswordProvider(ArkivoPasswordProvider.fixed(password));
    }

    /// Returns the required path supplied through one Gradle test system property.
    private static Path requiredPathProperty(String name) {
        String value = Objects.requireNonNull(System.getProperty(name), "Missing system property: " + name);
        return Path.of(value);
    }
}
