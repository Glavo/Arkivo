// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.internal.ReadOnlyByteArrayChannel;
import org.glavo.arkivo.archive.tar.TarArkivoFileSystem;
import org.glavo.arkivo.archive.tar.TarArkivoStreamingReader;
import org.glavo.arkivo.archive.zip.ZipArchiveOptions;
import org.glavo.arkivo.archive.zip.ZipArkivoEntryAttributes;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.archive.zip.ZipArkivoStreamingReader;
import org.glavo.arkivo.archive.zip.ZipEncryption;
import org.glavo.arkivo.archive.zip.ZipMethod;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Reads original embedded SharpZipLib regression archives through Arkivo's indexed and streaming APIs.
@NotNullByDefault
final class SharpZipLibCorpusTest {
    /// The expected BZip2 entry contents from the upstream 7-Zip interoperability regression.
    private static final String DESCRIPTION = "SharpZipLib (#ziplib, formerly NZipLib) is a compression library "
            + "that supports Zip files using both stored and deflate compression methods, PKZIP 2.0 style and AES encryption.";

    /// Describes a valid embedded ZIP without embedding its binary representation in the repository.
    ///
    /// @param source the upstream C# source path relative to the test project
    /// @param constant the source variable containing Base64 data
    /// @param occurrence the zero-based declaration occurrence in that source
    /// @param entry the single entry name
    /// @param content the independently specified decoded text
    /// @param method the actual compression method
    /// @param password the public fixture password, or null for an unencrypted entry
    @NotNullByDefault
    private record Sample(String source, String constant, int occurrence, String entry, String content,
                          ZipMethod method, @Nullable String password) {
    }

    /// Supplies both 7-Zip BZip2 archives, the minimal BZip2 stream, and the empty-password AES regression.
    private static Stream<Sample> samples() {
        return Stream.of(
                new Sample("Zip/StreamHandling.cs", "BZip2CompressedZip", 0, "a.dat", "0000000", ZipMethod.BZIP2, null),
                new Sample("Zip/ZipFileHandling.cs", "bZip2CompressedZipCreatedBy7Zip", 0, "Hello.txt",
                        DESCRIPTION, ZipMethod.BZIP2, null),
                new Sample("Zip/ZipFileHandling.cs", "bZip2CompressedZipCreatedBy7Zip", 1, "Hello.txt",
                        DESCRIPTION, ZipMethod.BZIP2, "password"),
                new Sample("Zip/ZipEncryptionHandling.cs", "TestFileWithEmptyPassword", 0, "test",
                        "Lorem ipsum dolor sit amet, consectetur adipiscing elit.", ZipMethod.DEFLATED, ""));
    }

    /// Combines short source reads with complete, partial, and unopened entry consumption.
    private static Stream<Arguments> readCases() {
        return samples().flatMap(sample -> Stream.of(1, 7, 8192).flatMap(chunk -> Stream.of(0, 1, 2)
                .map(mode -> Arguments.of(sample, chunk, mode))));
    }

    /// Checks exact contents and encryption metadata, including AES with an explicitly empty password.
    @ParameterizedTest(name = "{0}, chunk={1}, mode={2}")
    @MethodSource("readCases")
    void readsOriginalZip(Sample sample, int chunk, int mode) throws IOException {
        byte[] archive = fixture(sample.source(), sample.constant(), sample.occurrence());
        byte[] expected = sample.content().getBytes(StandardCharsets.UTF_8);
        var options = options(sample.password());
        try (var fileSystem = ZipArkivoFileSystem.open(new ReadOnlyByteArrayChannel(archive), options)) {
            Path path = fileSystem.getPath("/" + sample.entry());
            var attributes = Files.readAttributes(path, ZipArkivoEntryAttributes.class);
            assertEquals(sample.method(), attributes.compressionMethod());
            assertEquals(sample.password() == null ? ZipEncryption.NONE : ZipEncryption.WINZIP_AES_256,
                    attributes.encryption());
            assertEquals(expected.length, attributes.size());
            assertArrayEquals(expected, Files.readAllBytes(path));
        }
        if ("TestFileWithEmptyPassword".equals(sample.constant())) {
            IOException failure = assertThrows(IOException.class,
                    () -> readStreaming(sample, archive, chunk, mode));
            assertEquals("WinZip AES data descriptor does not match entry data", failure.getMessage());
            // The original AE-2 sample stores a nonzero descriptor CRC. Authentication and ciphertext stay unchanged.
            byte[] corrected = archive.clone();
            int central = ByteArrayAccess.readIntLittleEndian(corrected, corrected.length - 6);
            int descriptor = central - 24;
            assertEquals(0x08074b50, ByteArrayAccess.readIntLittleEndian(corrected, descriptor));
            ByteArrayAccess.writeIntLittleEndian(corrected, descriptor + 4, 0);
            ByteArrayAccess.writeIntLittleEndian(corrected, 14, 0);
            ByteArrayAccess.writeIntLittleEndian(corrected, central + 16, 0);
            readStreaming(sample, corrected, chunk, mode);
        } else {
            readStreaming(sample, archive, chunk, mode);
        }
    }

    /// Reads or skips the single entry and consumes its remaining local records.
    private static void readStreaming(Sample sample, byte @Unmodifiable [] archive, int chunk, int mode) throws IOException {
        byte[] expected = sample.content().getBytes(StandardCharsets.UTF_8);
        try (var reader = ZipArkivoStreamingReader.open(new ChunkedInput(archive, chunk), options(sample.password()))) {
            assertTrue(reader.next());
            assertEquals(sample.entry(), reader.readAttributes().path());
            if (mode != 2) {
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(mode == 0 ? expected : Arrays.copyOf(expected, 3),
                            mode == 0 ? input.readAllBytes() : input.readNBytes(3));
                }
            }
            assertFalse(reader.next());
        }
    }

    /// Selects fixtures that require a password, distinguishing an empty password from a missing one.
    private static Stream<Sample> encryptedSamples() {
        return samples().filter(sample -> sample.password() != null);
    }

    /// Rejects modified authentication bytes even when the password and decoded contents remain correct.
    @ParameterizedTest
    @MethodSource("encryptedSamples")
    void rejectsModifiedAuthentication(Sample sample) throws IOException {
        byte[] archive = fixture(sample.source(), sample.constant(), sample.occurrence());
        long compressedSize;
        try (var fileSystem = ZipArkivoFileSystem.open(new ReadOnlyByteArrayChannel(archive), options(sample.password()))) {
            compressedSize = Files.readAttributes(fileSystem.getPath("/" + sample.entry()),
                    ZipArkivoEntryAttributes.class).compressedSize();
        }
        int dataOffset = 30 + Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(archive, 26))
                + Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(archive, 28));
        archive[Math.toIntExact(dataOffset + compressedSize - 1)] ^= 1;
        for (int mode : new int[]{0, 1}) {
            IOException failure = assertThrows(IOException.class, () -> readStreaming(sample, archive, 1, mode));
            assertTrue(failure.getMessage().contains("authentication"), failure.getMessage());
        }
        if ((ByteArrayAccess.readShortLittleEndian(archive, 6) & 8) == 0) {
            // Unopened known-size entries are skipped as raw bytes; next() does not authenticate their contents.
            readStreaming(sample, archive, 1, 2);
        } else {
            IOException failure = assertThrows(IOException.class, () -> readStreaming(sample, archive, 1, 2));
            assertTrue(failure.getMessage().contains("authentication"), failure.getMessage());
        }
        assertThrows(IOException.class, () -> {
            try (var fileSystem = ZipArkivoFileSystem.open(new ReadOnlyByteArrayChannel(archive), options(sample.password()))) {
                Files.readAllBytes(fileSystem.getPath("/" + sample.entry()));
            }
        });
    }

    /// Rejects missing and wrong passwords without treating an encrypted entry as an empty file.
    @ParameterizedTest
    @MethodSource("encryptedSamples")
    void rejectsWrongPassword(Sample sample) throws IOException {
        byte[] archive = fixture(sample.source(), sample.constant(), sample.occurrence());
        for (var options : new ZipArchiveOptions.Read[]{options(null), options("incorrect")}) {
            assertThrows(IOException.class, () -> {
                try (var fileSystem = ZipArkivoFileSystem.open(new ReadOnlyByteArrayChannel(archive), options)) {
                    Files.readAllBytes(fileSystem.getPath("/" + sample.entry()));
                }
            });
            assertThrows(IOException.class, () -> {
                try (var reader = ZipArkivoStreamingReader.open(new ChunkedInput(archive, 1), options)) {
                    assertTrue(reader.next());
                    try (var input = reader.openInputStream()) {
                        input.readAllBytes();
                    }
                }
            });
        }
    }

    /// Copies untouched encrypted and BZip2 local records while adding an independently readable entry.
    @ParameterizedTest
    @MethodSource("samples")
    void preservesRecordsDuringUpdate(Sample sample, @TempDir Path directory) throws IOException {
        Path archive = directory.resolve("update.zip");
        Files.write(archive, fixture(sample.source(), sample.constant(), sample.occurrence()));
        try (var fileSystem = ZipArkivoFileSystem.update(archive)) {
            Files.writeString(fileSystem.getPath("/added"), "added content", StandardCharsets.UTF_8);
        }
        try (var fileSystem = ZipArkivoFileSystem.open(archive, options(sample.password()))) {
            assertArrayEquals(sample.content().getBytes(StandardCharsets.UTF_8),
                    Files.readAllBytes(fileSystem.getPath("/" + sample.entry())));
            assertEquals("added content", Files.readString(fileSystem.getPath("/added")));
        }
    }

    /// Rejects the original zero-code-length reproducer through both read and skip paths.
    @ParameterizedTest
    @ValueSource(ints = {1, 7, 8192})
    void rejectsZeroCodeLength(int chunk) throws IOException {
        byte[] archive = fixture("Zip/ZipCorruptionHandling.cs", "TestFileZeroCodeLength", 0);
        for (boolean read : new boolean[]{false, true}) {
            assertThrows(IOException.class, () -> {
                try (var reader = ZipArkivoStreamingReader.open(new ChunkedInput(archive, chunk))) {
                    assertTrue(reader.next());
                    if (read) {
                        try (var input = reader.openInputStream()) {
                            input.readAllBytes();
                        }
                    } else {
                        reader.next();
                    }
                }
            });
        }
    }

    /// Rejects contradictory local sizes, then resolves saturated sizes after correcting that independent defect.
    @Test
    void readsZip64CentralSizes() throws IOException {
        byte[] archive = fixture("Zip/ZipCorruptionHandling.cs", "TestFileBadCDGoodCD64", 0);
        try (var fileSystem = ZipArkivoFileSystem.open(new ReadOnlyByteArrayChannel(archive))) {
            Path path = fileSystem.getPath("/testfile");
            IOException failure = assertThrows(IOException.class, () -> Files.size(path));
            assertEquals("ZIP local header uncompressed size does not match central directory", failure.getMessage());
        }
        int extra = 30 + Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(archive, 26));
        assertEquals(1, Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(archive, extra)));
        assertEquals(0, ByteArrayAccess.readLongLittleEndian(archive, extra + 4));
        ByteArrayAccess.writeLongLittleEndian(archive, extra + 4, 18);
        try (var fileSystem = ZipArkivoFileSystem.open(new ReadOnlyByteArrayChannel(archive))) {
            Path path = fileSystem.getPath("/testfile");
            assertEquals(18, Files.size(path));
            assertEquals("testfile contents\n", Files.readString(path));
        }
        try (var reader = ZipArkivoStreamingReader.open(new ChunkedInput(archive, 1))) {
            assertTrue(reader.next());
            try (var input = reader.openInputStream()) {
                assertArrayEquals("testfile contents\n".getBytes(StandardCharsets.UTF_8), input.readAllBytes());
            }
            assertFalse(reader.next());
        }
    }

    /// Preserves the PAX path that exceeds the legacy name field in the upstream TAR regression.
    @ParameterizedTest
    @ValueSource(ints = {1, 511, 8192})
    void readsPaxLongName(int chunk) throws IOException {
        byte[] archive = Arrays.copyOf(fixture("Tar/TarTests.cs", "input64", 0), 2560);
        String expected = literal("Tar/TarTests.cs", "expectedName", 0).replaceAll("\\s", "");
        try (var reader = TarArkivoStreamingReader.open(new ChunkedInput(archive, chunk))) {
            assertTrue(reader.next());
            assertEquals(expected, reader.readAttributes().path());
            try (var input = reader.openInputStream()) {
                assertEquals(-1, input.read());
            }
            assertFalse(reader.next());
        }
        try (var fileSystem = TarArkivoFileSystem.open(new ReadOnlyByteArrayChannel(archive))) {
            assertEquals(0, Files.size(fileSystem.getPath("/" + expected)));
        }
    }

    /// Applies the fixture password only when encryption is expected.
    private static ZipArchiveOptions.Read options(@Nullable String password) {
        return password == null ? ZipArchiveOptions.READ_DEFAULTS : ZipArchiveOptions.READ_DEFAULTS
                .withPasswordProvider(ArkivoPasswordProvider.fixed(password.getBytes(StandardCharsets.UTF_8)));
    }

    /// Decodes a quoted Base64 initializer directly from the verified upstream source file.
    private static byte[] fixture(String source, String name, int occurrence) throws IOException {
        return Base64.getDecoder().decode(literal(source, name, occurrence).replaceAll("\\s", ""));
    }

    /// Joins ordinary concatenated literals or a verbatim multiline literal from a pinned declaration.
    private static String literal(String source, String name, int occurrence) throws IOException {
        Path root = Path.of(Objects.requireNonNull(System.getProperty("arkivo.sharpziplib.testDataDirectory"),
                "SharpZipLib test data directory is not configured"));
        String text = Files.readString(root.resolve("test/ICSharpCode.SharpZipLib.Tests").resolve(source));
        var declaration = Pattern.compile("\\b(?:const\\s+string|var|string)\\s+" + Pattern.quote(name)
                + "\\s*=\\s*(.*?);", Pattern.DOTALL).matcher(text);
        for (int index = 0; index <= occurrence; index++) {
            assertTrue(declaration.find(), source + ": missing declaration " + name);
        }
        var strings = Pattern.compile("\"([^\"]*)\"").matcher(declaration.group(1));
        var result = new StringBuilder();
        while (strings.find()) {
            result.append(strings.group(1));
        }
        assertFalse(result.isEmpty(), source + ": empty declaration " + name);
        return result.toString();
    }

    /// Restricts source reads without changing EOF behavior.
    @NotNullByDefault
    private static final class ChunkedInput extends ByteArrayInputStream {
        /// The maximum bytes returned per source read.
        private final int chunk;

        /// Creates a short-reading source for one archive.
        private ChunkedInput(byte @Unmodifiable [] archive, int chunk) {
            super(archive);
            this.chunk = chunk;
        }

        @Override
        public synchronized int read(byte[] target, int offset, int length) {
            return super.read(target, offset, Math.min(length, chunk));
        }
    }
}
