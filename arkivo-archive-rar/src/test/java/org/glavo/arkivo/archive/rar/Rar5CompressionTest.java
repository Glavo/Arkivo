// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar;

import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.internal.ReadOnlyByteArrayChannel;
import org.glavo.arkivo.codec.DecompressionMemoryLimitException;
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
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies RAR5 compressed-body interoperability through Arkivo APIs.
@NotNullByDefault
public final class Rar5CompressionTest {
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

    /// Preserves the budget error across automatic cleanup and permits retrying a failed source close.
    @Test
    void retriesSourceCloseAfterDecoderMemoryFailure() throws IOException {
        var source = new ByteArrayInputStream(SOLID_ARCHIVE) {
            /// Number of source-close attempts.
            private int closeCount;

            /// Fails only the first source-close attempt.
            @Override
            public void close() throws IOException {
                if (++closeCount == 1) {
                    throw new IOException("source close failed");
                }
                super.close();
            }
        };
        var limits = ArchiveReadLimits.builder().maximumDecoderMemorySize(0).build();
        var options = new RarArchiveOptions(ArchiveReadOptions.DEFAULT.withLimits(limits));
        RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(source, options);
        assertTrue(reader.next());
        int remaining = source.available();
        DecompressionMemoryLimitException failure = assertThrows(DecompressionMemoryLimitException.class,
                () -> {
                    try (reader; InputStream body = reader.openInputStream()) {
                        body.readAllBytes();
                    }
                });
        assertEquals(0, failure.maximum());
        assertEquals(2, failure.getSuppressed().length);
        assertTrue(failure.getSuppressed()[0] instanceof DecompressionMemoryLimitException);
        assertNotSame(failure, failure.getSuppressed()[0]);
        assertEquals("source close failed", failure.getSuppressed()[1].getMessage());
        assertEquals(remaining, source.available());
        assertEquals(1, source.closeCount);
        reader.close();
        reader.close();
        assertEquals(2, source.closeCount);
        assertEquals(remaining, source.available());
    }

    /// Applies the workspace limit while decoding skipped solid predecessors and rebuilding indexed content.
    @Test
    void enforcesSolidDecoderMemoryLimit() throws IOException {
        // This archive declares a 1 MiB window; its tables and input/output buffers require another 134714 bytes.
        int required = (1 << 20) + 3642 + 2 * (64 << 10);
        for (int maximum : new int[]{required - 1, required}) {
            var limits = ArchiveReadLimits.builder().maximumDecoderMemorySize(maximum).build();
            var options = new RarArchiveOptions(ArchiveReadOptions.DEFAULT.withLimits(limits));
            ByteArrayInputStream source = new ByteArrayInputStream(SOLID_ARCHIVE);
            try (var reader = RarArkivoStreamingReader.open(source, options)) {
                assertTrue(reader.next());
                int remaining = source.available();
                if (maximum < required) {
                    DecompressionMemoryLimitException failure = assertThrows(DecompressionMemoryLimitException.class,
                            reader::next);
                    assertEquals(maximum, failure.maximum());
                    assertEquals(required, failure.actual());
                    assertThrows(DecompressionMemoryLimitException.class, reader::next);
                    assertEquals(remaining, source.available());
                } else {
                    for (int index = 2; index <= 9; index++) {
                        assertTrue(reader.next());
                    }
                    try (InputStream body = reader.openInputStream()) {
                        assertArrayEquals("file9\n".getBytes(StandardCharsets.UTF_8), body.readAllBytes());
                    }
                }
            }
            if (maximum < required) {
                assertThrows(DecompressionMemoryLimitException.class,
                        () -> RarArkivoFileSystem.open(new ReadOnlyByteArrayChannel(SOLID_ARCHIVE), options));
            } else {
                try (var fs = RarArkivoFileSystem.open(new ReadOnlyByteArrayChannel(SOLID_ARCHIVE), options)) {
                    assertArrayEquals("file9\n".getBytes(StandardCharsets.UTF_8),
                            Files.readAllBytes(fs.getPath("/file9.txt")));
                }
            }
        }
    }

    /// Applies the same decoder accounting after decrypting an encrypted RAR5 entry.
    @Test
    void enforcesEncryptedDecoderMemoryLimit() throws IOException {
        int required = (1 << 17) + 3642 + 2 * (64 << 10);
        for (int maximum : new int[]{required - 1, required}) {
            var limits = ArchiveReadLimits.builder().maximumDecoderMemorySize(maximum).build();
            var options = new RarArchiveOptions(ArchiveReadOptions.DEFAULT.withLimits(limits))
                    .withPasswordProvider(ArkivoPasswordProvider.fixed(PASSWORD));
            try (var reader = RarArkivoStreamingReader.open(new ByteArrayInputStream(ENCRYPTED_BODY_ARCHIVE), options)) {
                assertTrue(reader.next());
                if (maximum < required) {
                    DecompressionMemoryLimitException failure = assertThrows(DecompressionMemoryLimitException.class,
                            () -> {
                                try (InputStream body = reader.openInputStream()) {
                                    body.readAllBytes();
                                }
                            });
                    assertEquals(maximum, failure.maximum());
                    assertEquals(required, failure.actual());
                } else {
                    try (InputStream body = reader.openInputStream()) {
                        assertArrayEquals("file1\n".getBytes(StandardCharsets.UTF_8), body.readAllBytes());
                    }
                }
            }
        }
    }

    /// Reads every solid entry and verifies skipped predecessors preserve decoder state.
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
            try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    passwordOptions(PASSWORD)
            )) {
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

    /// Reads encrypted RAR5 compressed content through indexed file-system caching.
    @Test
    public void readsEncryptedCompressedEntryThroughFileSystem() throws IOException {
        Path archive = Files.createTempFile("arkivo-rar5-encrypted-", ".rar");
        Files.write(archive, ENCRYPTED_HEADER_ARCHIVE);
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

    /// Rejects an incorrect password before exposing encrypted compressed plaintext.
    @Test
    public void rejectsIncorrectCompressedEntryPassword() {
        assertThrows(IOException.class, () -> {
            try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(
                    new ByteArrayInputStream(ENCRYPTED_BODY_ARCHIVE),
                    passwordOptions("wrong".getBytes(StandardCharsets.UTF_8))
            )) {
                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
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

    /// Returns read options containing one fixed RAR5 password provider.
    private static RarArchiveOptions passwordOptions(byte[] password) {
        return RarArchiveOptions.DEFAULT.withPasswordProvider(ArkivoPasswordProvider.fixed(password));
    }

    /// Returns the required path supplied through one Gradle test system property.
    private static Path requiredPathProperty(String name) {
        return Path.of(Objects.requireNonNull(System.getProperty(name), "Missing system property: " + name));
    }
}
