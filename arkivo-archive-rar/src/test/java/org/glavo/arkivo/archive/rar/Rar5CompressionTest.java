// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar;

import org.glavo.arkivo.archive.ArchiveOptions;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
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

/// Verifies RAR5 compressed-body interoperability through Arkivo APIs.
@NotNullByDefault
public final class Rar5CompressionTest {
    /// The system property containing the published RAR module JAR path.
    private static final String RAR_JAR_PROPERTY = "arkivo.rar.jar";

    /// The system property containing the Arkivo archive module JAR path.
    private static final String ARCHIVE_JAR_PROPERTY = "arkivo.archive.jar";

    /// The system property containing the Arkivo internal base module JAR path.
    private static final String INTERNAL_BASE_JAR_PROPERTY = "arkivo.internal-base.jar";

    /// The system property containing the published codec-core JAR path.
    private static final String CODEC_JAR_PROPERTY = "arkivo.codec.jar";

    /// The system property containing the published PPMd-codec JAR path.
    private static final String PPMD_JAR_PROPERTY = "arkivo.ppmd.jar";

    /// A RAR5 solid archive containing `file1.txt` through `file9.txt`.
    private static final byte @Unmodifiable [] SOLID_ARCHIVE = Base64.getDecoder().decode(
            "UmFyIRoHAQAJ78hvCwEFBwQGAQGAgIAA5PLueR8CApsABoYApIMCY0kUXwT3KeKAGwEJZmlsZTEudHh0wYMYNDAz"
                    + "+EAy3Ybv0QWJRKH8DMPdhxsia/YACAfgmR8CAoUABoYApIMCY0kUX8ekBMnAGwEJZmlsZTIudHh0QhoCe4DgrmJz"
                    + "HwIChQAGhgCkgwJjSRRfhpUf0MAbAQlmaWxlMy50eHRCGgJ8ALv5fIsfAgKFAAaGAKSDAmNJFF9BA16fwBsBCWZp"
                    + "bGU0LnR4dEIaAnyAU1D+YR8CAoUABoYApIMCY0kUXwAyRYbAGwEJZmlsZTUudHh0QhoCfQAqrAiFHwIChQAGhgCk"
                    + "gwJjSRRfw2ForcAbAQlmaWxlNi50eHRCGgJ9gMIFim8fAgKFAAaGAKSDAmNJFF+CUHO0wBsBCWZpbGU3LnR4dEIa"
                    + "An4A3QRFrh8CAoUABoYApIMCY0kUX01M6zPAGwEJZmlsZTgudHh0QhoCfoA1rcdEHwIChQAGhgCkgwJjSRRfDH3w"
                    + "KsAbAQlmaWxlOS50eHRCGgJ/AB13VlEDBQQA"
    );

    /// A RAR5 archive with encrypted headers and one AES-encrypted compressed body.
    private static final byte @Unmodifiable [] ENCRYPTED_HEADER_ARCHIVE = Base64.getDecoder().decode(
            "UmFyIRoHAQAYOJrPIQQAAAEPprqRs1Vs70VeAnJr65GiUWzJnBs88EB6pEDZCDMNebpM1FRWRidOoP8NEKunwvQX"
                    + "SE6qyWZSmFdTmJz5B4PRrGmc/9wgf07nAr0VnT/SUD7KGRm04mC2+uJap3bok3fPNwjtWnVbqxga+30ke8uVJYZk"
                    + "iuuGhz7dmPmsjcbbifv8JRtif4lMcFsoiFclxaKGgHWEAQ5iUr3A418NqLr87fq2lB4LpFyCVjVgrfNS3Ou5IdI"
                    + "1MBz0SPemsbqYy9mjOR0uTISWgDqgl9qBApSZxzov0pm4HbMDGpR2jEAzLnvZFtWMyUYPoVpguDM="
    );

    /// A RAR5 archive with plain headers and one AES-encrypted compressed body.
    private static final byte @Unmodifiable [] ENCRYPTED_BODY_ARCHIVE = Base64.getDecoder().decode(
            "UmFyIRoHAQAzkrXlCgEFBgAFAQGAgABfIc6zUQIDMaAABoYApIMCY0kUXxnn0+uAAwEJZmlsZTEudHh0MAEAAw+r"
                    + "gP7jvy7bm3QlOdS3YZwFYG45NDcQ1tsYPE/SbYZEVJmc4Qhprj8yi5S4gj56t2Cqe0X/M28NnKG0ccGCC/yKLzwO"
                    + "LmAr44eAnrJUHXdWUQMFBAA="
    );

    /// The UTF-8 password used by the encrypted fixtures.
    private static final byte @Unmodifiable [] PASSWORD = "junrar".getBytes(StandardCharsets.UTF_8);

    /// Reads every solid entry and verifies skipped predecessors preserve decoder state.
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
            assertTrue(reader.next());
            try (InputStream input = reader.openInputStream()) {
                assertArrayEquals("file2\n".getBytes(StandardCharsets.UTF_8), input.readAllBytes());
            }
        }
    }

    /// Reads solid RAR5 compressed content through indexed file-system caching.
    @Test
    public void readsSolidCompressedEntriesThroughFileSystem() throws IOException {
        Path archive = Files.createTempFile("arkivo-rar5-solid-", ".rar");
        Files.write(archive, SOLID_ARCHIVE);
        try {
            try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(archive)) {
                assertArrayEquals(
                        "file9\n".getBytes(StandardCharsets.UTF_8),
                        Files.readAllBytes(fileSystem.getPath("/file9.txt"))
                );
            }
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    /// Reads AES-encrypted compressed bodies with both plain and encrypted RAR5 headers.
    @Test
    public void readsEncryptedCompressedEntries() throws IOException {
        for (byte[] archive : new byte[][]{ENCRYPTED_BODY_ARCHIVE, ENCRYPTED_HEADER_ARCHIVE}) {
            try (RarArkivoStreamingReader reader = RarArkivoFormat.instance().openStreamingReader(
                    new ByteArrayInputStream(archive),
                    ArchiveOptions.fromEnvironment(passwordEnvironment(PASSWORD))
            )) {
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

    /// Reads encrypted RAR5 compressed content through indexed file-system caching.
    @Test
    public void readsEncryptedCompressedEntryThroughFileSystem() throws IOException {
        Path archive = Files.createTempFile("arkivo-rar5-encrypted-", ".rar");
        Files.write(archive, ENCRYPTED_HEADER_ARCHIVE);
        try {
            try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(
                    archive,
                    ArchiveOptions.fromEnvironment(passwordEnvironment(PASSWORD))
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

    /// Rejects an incorrect password before exposing encrypted compressed plaintext.
    @Test
    public void rejectsIncorrectCompressedEntryPassword() {
        assertThrows(IOException.class, () -> {
            try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(
                    new ByteArrayInputStream(ENCRYPTED_BODY_ARCHIVE),
                    ArchiveOptions.fromEnvironment(passwordEnvironment("wrong".getBytes(StandardCharsets.UTF_8)))
            )) {
                assertTrue(reader.next());
                try (InputStream input = reader.openInputStream()) {
                    input.readAllBytes();
                }
            }
        });
    }

    /// Loads the published module in isolation and verifies its relocated RAR5 decoder.
    @Test
    public void readsCompressedEntryFromPublishedModule() throws Exception {
        ModuleFinder finder = ModuleFinder.of(
                requiredPathProperty(RAR_JAR_PROPERTY),
                requiredPathProperty(ARCHIVE_JAR_PROPERTY),
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

    /// Returns environment options containing one fixed RAR5 password provider.
    private static Map<String, Object> passwordEnvironment(byte[] password) {
        return Map.of(
                RarArkivoFileSystem.PASSWORD_PROVIDER.key(),
                ArkivoPasswordProvider.fixed(password)
        );
    }

    /// Returns the required path supplied through one Gradle test system property.
    private static Path requiredPathProperty(String name) {
        return Path.of(Objects.requireNonNull(System.getProperty(name), "Missing system property: " + name));
    }
}
