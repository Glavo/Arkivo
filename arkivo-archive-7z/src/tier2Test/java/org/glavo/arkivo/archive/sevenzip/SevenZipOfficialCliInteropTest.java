// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Exchanges independently encoded 7z archives with an explicitly configured official command-line tool.
///
/// Set `ARKIVO_7Z_EXECUTABLE` to enable these tests and `ARKIVO_REQUIRE_7Z=true` to reject a missing tool.
@NotNullByDefault
public final class SevenZipOfficialCliInteropTest {
    /// The isolated output directory for the generated archive.
    @TempDir
    private Path temporaryDirectory;

    /// A public test password exercising UTF-16LE key derivation with non-ASCII characters.
    private static final String PASSWORD = "arkivo-\u5bc6\u7801-\u00e9";

    /// The explicitly configured executable.
    private String executable = "";

    /// Distinguishes each command's diagnostic files.
    private int commandNumber;

    /// Validates the selected executable before generating archives.
    @BeforeEach
    void requireOfficialTool() throws IOException {
        @Nullable String configured = System.getenv("ARKIVO_7Z_EXECUTABLE");
        boolean available = configured != null && Files.isRegularFile(Path.of(configured));
        if (Boolean.parseBoolean(System.getenv("ARKIVO_REQUIRE_7Z"))) {
            assertTrue(available, "Set ARKIVO_7Z_EXECUTABLE to the official 7-Zip CLI");
        }
        Assumptions.assumeTrue(available, "Official 7-Zip CLI is not configured");
        executable = Path.of(Objects.requireNonNull(configured)).toAbsolutePath().toString();
        assertTrue(run(temporaryDirectory, List.of("i")).contains("7-Zip"));
    }

    /// Crosses compression and filter configurations with plain, data-encrypted, and header-encrypted sessions.
    private static Stream<Arguments> cases() {
        return configurations().stream().flatMap(configuration -> Stream.of(0, 1, 2)
                .map(encryption -> Arguments.of(configuration, encryption)));
    }

    /// Checks both directions, exact coder methods, names, complete bodies, and independent entry channels.
    @ParameterizedTest(name = "{0}; encryption={1}")
    @MethodSource("cases")
    void exchangesArchives(Configuration configuration, int encryption) throws IOException {
        List<Content> contents = contents();
        byte[] password = PASSWORD.getBytes(StandardCharsets.UTF_16LE);
        try {
            Path official = generateOfficial(configuration, encryption, contents, false);
            assertArkivoReads(official, contents, configuration, encryption, password,
                    encryption != 0 && configuration.compression().method() != SevenZipCompressionMethod.COPY);
            Path arkivo = temporaryDirectory.resolve("arkivo.7z");
            var options = SevenZipArchiveOptions.CREATE_DEFAULTS
                    .withCompression(configuration.compression()).withFilters(configuration.filters())
                    .withSolidFileCount(encryption == 0 ? 1 : 2).withEncryptHeaders(encryption == 2);
            if (encryption != 0) options = options.withPasswordProvider(ArkivoPasswordProvider.fixed(password));
            try (var writer = SevenZipArkivoStreamingWriter.create(arkivo, options)) {
                writer.beginDirectory("empty-dir").close();
                for (Content content : contents) {
                    try (var output = writer.beginFile(content.name()).openOutputStream()) {
                        output.write(content.bytes());
                    }
                }
            }
            assertArkivoReads(arkivo, contents, configuration, encryption, password, encryption != 0);
            assertOfficialReads(arkivo, contents, encryption);
        } finally {
            Arrays.fill(password, (byte) 0);
        }
    }

    /// Reads official split volumes with uncompressed data crossing physical volume boundaries.
    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void readsOfficialSplitVolumes(int encryption) throws IOException {
        Configuration configuration = configurations().get(0);
        List<Content> contents = contents();
        Path firstVolume = generateOfficial(configuration, encryption, contents, true);
        assertTrue(Files.exists(firstVolume.resolveSibling("official.7z.002")));
        byte[] password = PASSWORD.getBytes(StandardCharsets.UTF_16LE);
        try {
            assertArkivoReads(firstVolume, contents, configuration, encryption, password, false);
        } finally {
            Arrays.fill(password, (byte) 0);
        }
    }

    /// Rejects an incorrect password without preventing an independent open with the correct password.
    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void rejectsWrongPassword(int encryption) throws IOException {
        Configuration configuration = configurations().get(2);
        List<Content> contents = contents();
        Path archive = generateOfficial(configuration, encryption, contents, false);
        byte[] wrong = "incorrect".getBytes(StandardCharsets.UTF_16LE);
        byte[] correct = PASSWORD.getBytes(StandardCharsets.UTF_16LE);
        try {
            var options = SevenZipArchiveOptions.READ_DEFAULTS.withPasswordProvider(ArkivoPasswordProvider.fixed(wrong));
            assertThrows(IOException.class, () -> {
                try (var fileSystem = SevenZipArkivoFileSystem.open(archive, options)) {
                    Files.readAllBytes(fileSystem.getPath("/a.bin"));
                }
            });
            assertArkivoReads(archive, contents, configuration, encryption, correct, true);
        } finally {
            Arrays.fill(wrong, (byte) 0);
            Arrays.fill(correct, (byte) 0);
        }
    }

    /// Rejects modified packed bytes through the official archive's decoded CRC, with and without AES.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void rejectsCorruptedOfficialData(int encryption) throws IOException {
        Path archive = generateOfficial(configurations().get(0), encryption, contents(), false);
        byte[] password = PASSWORD.getBytes(StandardCharsets.UTF_16LE);
        try {
            var options = SevenZipArchiveOptions.READ_DEFAULTS;
            if (encryption != 0) options = options.withPasswordProvider(ArkivoPasswordProvider.fixed(password));
            long offset;
            try (var fileSystem = SevenZipArkivoFileSystem.open(archive, options)) {
                var attributes = Files.readAttributes(fileSystem.getPath("/a.bin"), SevenZipArkivoEntryAttributes.class);
                assertTrue(attributes.packedSize() > 0);
                offset = attributes.dataOffset();
            }
            byte[] corrupted = Files.readAllBytes(archive);
            assertTrue(offset >= 32 && offset < corrupted.length);
            corrupted[Math.toIntExact(offset)] ^= 0x40;
            Files.write(archive, corrupted);
            var readOptions = options;
            assertThrows(IOException.class, () -> {
                try (var fileSystem = SevenZipArkivoFileSystem.open(archive, readOptions)) {
                    Files.readAllBytes(fileSystem.getPath("/a.bin"));
                }
            });
        } finally {
            Arrays.fill(password, (byte) 0);
        }
    }

    /// Re-encodes official input after edits and lets the official decoder verify every surviving body.
    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void officialToolReadsUpdatedArchive(int encryption) throws IOException {
        List<Content> original = contents();
        Path archive = generateOfficial(configurations().get(2), encryption, original, false);
        byte[] password = PASSWORD.getBytes(StandardCharsets.UTF_16LE);
        try {
            var options = SevenZipArchiveOptions.UPDATE_DEFAULTS
                    .withCompression(SevenZipCompression.lzma2(65536)).withSolidFileCount(2)
                    .withEncryptHeaders(encryption == 2);
            if (encryption != 0) options = options.withPasswordProvider(ArkivoPasswordProvider.fixed(password));
            try (var fileSystem = SevenZipArkivoFileSystem.update(archive, options)) {
                Files.write(fileSystem.getPath("/a.bin"), new byte[]{3, 2, 1});
                Files.delete(fileSystem.getPath("/empty.bin"));
                Files.move(fileSystem.getPath("/b.bin"), fileSystem.getPath("/renamed.bin"));
                Files.write(fileSystem.getPath("/added.bin"), new byte[]{4, 5});
            }
            List<Content> expected = new ArrayList<>();
            for (Content content : original) {
                switch (content.name()) {
                    case "a.bin" -> expected.add(new Content("a.bin", new byte[]{3, 2, 1}));
                    case "empty.bin" -> { }
                    case "b.bin" -> expected.add(new Content("renamed.bin", content.bytes()));
                    default -> expected.add(content);
                }
            }
            expected.add(new Content("added.bin", new byte[]{4, 5}));
            assertOfficialReads(archive, expected, encryption);
        } finally {
            Arrays.fill(password, (byte) 0);
        }
    }

    /// Creates official input with bounded dictionaries and explicit coder selection.
    private Path generateOfficial(Configuration configuration, int encryption, List<Content> contents, boolean split)
            throws IOException {
        Path source = Files.createDirectory(temporaryDirectory.resolve("source"));
        Files.createDirectory(source.resolve("empty-dir"));
        for (Content content : contents) {
            Path path = source.resolve(content.name());
            Files.createDirectories(path.getParent());
            Files.write(path, content.bytes());
        }
        Path archive = temporaryDirectory.resolve("official.7z");
        List<String> arguments = new ArrayList<>(List.of("a", "-t7z", "-y", "-bd", "-bb0", "-mmt=1",
                "-mf=off", encryption == 0 ? "-ms=off" : "-ms=2f", "-mhc=on"));
        arguments.addAll(configuration.arguments());
        if (encryption != 0) {
            arguments.add("-p" + PASSWORD);
            arguments.add(encryption == 2 ? "-mhe=on" : "-mhe=off");
        }
        if (split) arguments.add("-v4k");
        arguments.add(archive.toAbsolutePath().toString());
        arguments.add("empty-dir");
        for (Content content : contents) arguments.add(content.name());
        run(source, arguments);
        Path result = split ? archive.resolveSibling("official.7z.001") : archive;
        List<String> listing = new ArrayList<>(List.of("l", "-slt", result.toString()));
        if (encryption != 0) listing.add("-p" + PASSWORD);
        String metadata = run(source, listing);
        // The official Copy encoder disables solid packing even when -ms=2f is supplied.
        boolean solid = encryption != 0 && configuration.compression().method() != SevenZipCompressionMethod.COPY;
        assertTrue(metadata.contains("Solid = " + (solid ? "+" : "-")), metadata);
        return result;
    }

    /// Compares full bodies and interleaved entry channels, then verifies the configured coder graph.
    private static void assertArkivoReads(Path archive, List<Content> contents, Configuration configuration,
                                         int encryption, byte[] password, boolean expectedSolid) throws IOException {
        var options = SevenZipArchiveOptions.READ_DEFAULTS;
        if (encryption != 0) options = options.withPasswordProvider(ArkivoPasswordProvider.fixed(password));
        try (var fileSystem = SevenZipArkivoFileSystem.open(archive, options)) {
            assertTrue(Files.isDirectory(fileSystem.getPath("/empty-dir")));
            Path root = fileSystem.getPath("/");
            try (var paths = Files.walk(root)) {
                assertEquals(contents.stream().map(Content::name).sorted().toList(),
                        paths.filter(Files::isRegularFile).map(p -> root.relativize(p).toString().replace('\\', '/'))
                                .sorted().toList());
            }
            boolean foundSolidFolder = false;
            for (int index = contents.size() - 1; index >= 0; index--) {
                Content content = contents.get(index);
                Path path = fileSystem.getPath("/" + content.name());
                var attributes = Files.readAttributes(path, SevenZipArkivoEntryAttributes.class);
                assertEquals(content.bytes().length, attributes.size());
                assertArrayEquals(content.bytes(), Files.readAllBytes(path), content.name());
                if (content.bytes().length == 0) continue;
                @Nullable var graph = attributes.coderGraph();
                assertNotNull(graph);
                List<SevenZipCoderMethod> methods = graph.coders().stream().map(SevenZipCoder::method).toList();
                assertTrue(methods.contains(SevenZipCoderMethod.valueOf(configuration.compression().method().name())),
                        () -> "Compression method missing from " + methods);
                assertEquals(encryption != 0, methods.contains(SevenZipCoderMethod.AES));
                for (var filter : configuration.filters().filters()) {
                    assertTrue(methods.contains(SevenZipCoderMethod.valueOf(filter.method().name())),
                            () -> "Filter missing from " + methods);
                }
                if (encryption == 0) assertFalse(attributes.solid());
                foundSolidFolder |= attributes.solid();
            }
            assertEquals(expectedSolid, foundSolidFolder, "Solid-folder policy");
            Path path = fileSystem.getPath("/a.bin");
            byte[] expected = contents.get(0).bytes();
            byte[] otherExpected = contents.get(1).bytes();
            try (var first = Files.newByteChannel(path);
                 var second = Files.newByteChannel(fileSystem.getPath("/b.bin"))) {
                for (int position : new int[]{expected.length - 13, 0, 8191, 31}) {
                    first.position(position);
                    second.position((expected.length - position - 13) % (otherExpected.length - 12));
                    for (var channel : List.of(first, second)) {
                        int start = (int) channel.position();
                        ByteBuffer target = ByteBuffer.allocateDirect(13);
                        while (target.hasRemaining()) assertTrue(channel.read(target) > 0);
                        byte[] actual = new byte[13];
                        target.flip().get(actual);
                        byte[] original = channel == first ? expected : otherExpected;
                        assertArrayEquals(Arrays.copyOfRange(original, start, start + 13), actual);
                    }
                }
            }
        }
    }

    /// Runs the official integrity check and compares every extracted regular file with its original bytes.
    private void assertOfficialReads(Path archive, List<Content> contents, int encryption) throws IOException {
        List<String> passwordOption = encryption == 0 ? List.of() : List.of("-p" + PASSWORD);
        List<String> test = new ArrayList<>(List.of("t", "-bd", "-bb0", archive.toAbsolutePath().toString()));
        test.addAll(passwordOption);
        run(temporaryDirectory, test);
        Path extracted = temporaryDirectory.resolve("extracted");
        List<String> extract = new ArrayList<>(List.of("x", "-y", "-bd", "-bb0", "-o" + extracted,
                archive.toAbsolutePath().toString()));
        extract.addAll(passwordOption);
        run(temporaryDirectory, extract);
        assertTrue(Files.isDirectory(extracted.resolve("empty-dir")));
        try (var paths = Files.walk(extracted)) {
            assertEquals(contents.stream().map(Content::name).sorted().toList(),
                    paths.filter(Files::isRegularFile).map(p -> extracted.relativize(p).toString().replace('\\', '/'))
                            .sorted().toList());
        }
        for (Content content : contents) {
            assertArrayEquals(content.bytes(), Files.readAllBytes(extracted.resolve(content.name())), content.name());
        }
    }

    /// Returns seven official compression methods and every Arkivo preprocessing filter.
    private static @Unmodifiable List<Configuration> configurations() {
        List<Configuration> configurations = new ArrayList<>();
        configurations.add(new Configuration("Copy", SevenZipCompression.copy(), SevenZipFilterChain.EMPTY, List.of("-m0=Copy")));
        configurations.add(new Configuration("LZMA", SevenZipCompression.lzma(65536), SevenZipFilterChain.EMPTY, List.of("-m0=LZMA:d64k")));
        configurations.add(new Configuration("LZMA2", SevenZipCompression.lzma2(65536), SevenZipFilterChain.EMPTY, List.of("-m0=LZMA2:d64k")));
        configurations.add(new Configuration("BZip2", SevenZipCompression.bzip2(1), SevenZipFilterChain.EMPTY, List.of("-m0=BZip2:d100k")));
        configurations.add(new Configuration("Deflate", SevenZipCompression.deflate(6), SevenZipFilterChain.EMPTY, List.of("-m0=Deflate")));
        configurations.add(new Configuration("Deflate64", SevenZipCompression.deflate64(6), SevenZipFilterChain.EMPTY, List.of("-m0=Deflate64")));
        configurations.add(new Configuration("PPMd", SevenZipCompression.ppmd(6, 1 << 20), SevenZipFilterChain.EMPTY, List.of("-m0=PPMd:o6:mem1m")));
        for (SevenZipFilterMethod method : SevenZipFilterMethod.values()) {
            String option = switch (method) {
                case DELTA -> "Delta:4";
                case BCJ_X86 -> "BCJ";
                case BCJ2 -> "BCJ2";
                case BCJ_POWERPC -> "PPC";
                case BCJ_IA64 -> "IA64";
                case BCJ_ARM -> "ARM";
                case BCJ_ARM_THUMB -> "ARMT";
                case BCJ_SPARC -> "SPARC";
                case BCJ_ARM64 -> "ARM64";
                case BCJ_RISCV -> "RISCV";
            };
            List<String> arguments = method == SevenZipFilterMethod.BCJ2
                    ? List.of("-m0=BCJ2", "-m1=LZMA2:d64k", "-m2=LZMA2:d64k", "-m3=LZMA2:d64k",
                    "-mb0:1", "-mb0s1:2", "-mb0s2:3")
                    : List.of("-m0=" + option, "-m1=LZMA2:d64k");
            SevenZipFilter filter = method == SevenZipFilterMethod.DELTA ? SevenZipFilter.delta(4) : SevenZipFilter.of(method);
            configurations.add(new Configuration(option, SevenZipCompression.lzma2(65536), SevenZipFilterChain.of(filter), arguments));
        }
        return List.copyOf(configurations);
    }

    /// Builds files with empty, Unicode, long-name, and nested members around branch-rich content.
    private static @Unmodifiable List<Content> contents() {
        byte[] content = bcj2FriendlyContent();
        return List.of(new Content("a.bin", content), new Content("b.bin", Arrays.copyOf(content, 8197)),
                new Content("empty.bin", new byte[0]), new Content("nested/\u6587\u4ef6-\u00e9.bin", Arrays.copyOf(content, 1027)),
                new Content("long-" + "segment".repeat(18) + ".bin", new byte[]{9, 8, 7, 6, 5}));
    }

    /// Runs a noninteractive official command with bounded waiting and file-backed diagnostics.
    private String run(Path workingDirectory, List<String> arguments) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("-sccUTF-8");
        command.addAll(arguments);
        int number = commandNumber++;
        Path output = temporaryDirectory.resolve("command-" + number + ".out");
        Path error = temporaryDirectory.resolve("command-" + number + ".err");
        Process process = new ProcessBuilder(command).directory(workingDirectory.toFile())
                .redirectOutput(output.toFile()).redirectError(error.toFile()).start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(30, TimeUnit.SECONDS)) throw new IOException("7-Zip command timed out");
            String text = Files.readString(output);
            assertEquals(0, process.exitValue(), text + "\n" + Files.readString(error));
            return text;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for 7-Zip", failure);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    /// Corresponding Arkivo and official CLI configurations.
    ///
    /// @param name the test-report label
    /// @param compression the Arkivo compression method
    /// @param filters the Arkivo preprocessing chain
    /// @param arguments the official method and binding switches
    @NotNullByDefault
    private record Configuration(String name, SevenZipCompression compression, SevenZipFilterChain filters,
                                 @Unmodifiable List<String> arguments) {
        /// Returns the method or filter name for parameterized reports.
        @Override
        public String toString() {
            return name;
        }
    }

    /// A regular archive member and its original bytes.
    ///
    /// @param name the relative archive path
    /// @param bytes the immutable expected body
    @NotNullByDefault
    private record Content(String name, byte @Unmodifiable [] bytes) {
    }

    /// Returns deterministic x86-like bytes that exercise all BCJ2 side streams.
    private static byte[] bcj2FriendlyContent() {
        byte[] content = new byte[16 * 1024];
        for (int index = 0; index < content.length; index++) {
            content[index] = (byte) (index * 43 + 7);
        }
        for (int position = 48, branch = 0; position + 6 < content.length; position += 211, branch++) {
            int displacement;
            if (branch % 3 == 2) {
                content[position] = 0x0f;
                content[position + 1] = (byte) 0x85;
                displacement = position + 2;
            } else {
                content[position] = (byte) (branch % 3 == 0 ? 0xe8 : 0xe9);
                displacement = position + 1;
            }
            ByteArrayAccess.writeIntLittleEndian(content, displacement, 12 - displacement - 4);
        }
        return content;
    }
}
