// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar;

import org.glavo.arkivo.archive.ArkivoPasswordProvider;
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
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies RAR4 compressed-body interoperability through Arkivo APIs.
@NotNullByDefault
public final class Rar4CompressionTest {
    /// The system property containing the published RAR module JAR path.
    private static final String RAR_JAR_PROPERTY = "arkivo.rar.jar";

    /// The system property containing the Arkivo base module JAR path.
    private static final String BASE_JAR_PROPERTY = "arkivo.base.jar";

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

    /// Reads every solid entry and verifies skipped predecessors preserve decompression history.
    @Test
    public void readsSolidCompressedEntries() throws IOException {
        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(
                new ByteArrayInputStream(SOLID_ARCHIVE)
        )) {
            for (int index = 1; index <= 9; index++) {
                assertTrue(reader.next());
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
            assertEquals(false, reader.next());
        }

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(
                new ByteArrayInputStream(SOLID_ARCHIVE)
        )) {
            assertTrue(reader.next());
            assertEquals("file1.txt", reader.readAttributes(RarArkivoEntryAttributes.class).path());
            assertTrue(reader.next());
            try (InputStream input = reader.openInputStream()) {
                assertArrayEquals("file2\n".getBytes(StandardCharsets.UTF_8), input.readAllBytes());
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
                    ? RarArkivoFormat.instance().openStreamingReader(source, passwordEnvironment(PASSWORD))
                    : RarArkivoFormat.instance().openStreamingReader(
                            Channels.newChannel(source),
                            passwordEnvironment(PASSWORD)
                    );
            try (RarArkivoStreamingReader reader = openedReader) {
                assertTrue(reader.next());
                RarArkivoEntryAttributes attributes = reader.readAttributes(RarArkivoEntryAttributes.class);
                assertEquals("file1.txt", attributes.path());
                assertEquals(3, attributes.compressionMethod());
                assertTrue(attributes.isEncrypted());
                try (InputStream input = reader.openInputStream()) {
                    assertArrayEquals("file1\n".getBytes(StandardCharsets.UTF_8), input.readAllBytes());
                }
                assertEquals(false, reader.next());
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
                    passwordEnvironment(PASSWORD)
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
                requiredPathProperty(BASE_JAR_PROPERTY)
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
                    passwordEnvironment("wrong".getBytes(StandardCharsets.UTF_16LE))
            )) {
                assertTrue(reader.next());
                try (InputStream input = reader.openInputStream()) {
                    input.readAllBytes();
                }
            }
        });
    }

    /// Returns environment options containing one fixed UTF-16LE RAR3 password provider.
    private static Map<String, Object> passwordEnvironment(byte[] password) {
        return Map.of(
                RarArkivoFileSystem.PASSWORD_PROVIDER.key(),
                ArkivoPasswordProvider.fixed(password)
        );
    }

    /// Returns the required path supplied through one Gradle test system property.
    private static Path requiredPathProperty(String name) {
        String value = Objects.requireNonNull(System.getProperty(name), "Missing system property: " + name);
        return Path.of(value);
    }
}
