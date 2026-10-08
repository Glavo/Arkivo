// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Verifies LLVM fixtures and optional GNU, BSD, and Darwin output from the LLVM archiver.
@NotNullByDefault
final class LlvmArInteropTest {
    /// Holds generated archives and native command diagnostics.
    @TempDir
    Path directory;

    /// Checks fixture provenance independently of native tool availability.
    @Test
    void referenceFilesArePresent() throws IOException {
        assertTrue(Files.readString(root().resolve("LICENSE.TXT")).contains("LLVM Exceptions"));
        assertTrue(Files.readString(root().resolve("symtab.test")).contains("NO-SYMTAB"));
        assertTrue(Files.readString(root().resolve("path-names.test")).contains("hello-b"));
        assertTrue(Files.readString(root().resolve("empty-uid-gid.test")).contains("library.dll"));
    }

    /// Reads original LLVM archives without requiring a native tool or recreating their headers.
    @ParameterizedTest
    @ValueSource(strings = {"path-names.a", "msvc-import.lib"})
    void readsOriginalFixtures(String fixture) throws Exception {
        Map<String, byte[]> expected = new LinkedHashMap<>();
        switch (fixture) {
            case "path-names.a" -> {
                expected.put("a/foo.txt", "hello-a\n".getBytes(StandardCharsets.US_ASCII));
                expected.put("b/foo.txt", "hello-b\n".getBytes(StandardCharsets.US_ASCII));
            }
            case "msvc-import.lib" -> expected.put("library.dll", new byte[0]);
            default -> throw new IllegalArgumentException(fixture);
        }
        verify(root().resolve(fixture), expected);
    }

    /// Rejects a thin archive rather than treating externally stored members as inline payloads.
    @Test
    void rejectsOriginalThinArchive() throws IOException {
        Path archive = root().resolve("a-plus-b.a");
        byte[] bytes = Files.readAllBytes(archive);
        assertEquals("!<thin>\n", new String(bytes, 0, 8, StandardCharsets.US_ASCII));
        assertThrows(IOException.class, () -> {
            try (var fileSystem = ArArkivoFileSystem.open(archive)) {
                Files.readAllBytes(fileSystem.getPath("/a.txt"));
            }
        });
        assertThrows(IOException.class, () -> {
            try (var reader = ArArkivoStreamingReader.open(new ChunkedInput(bytes, 1))) {
                reader.next();
            }
        });
    }

    /// Varies the on-disk layout and whether the producer may emit a symbol table.
    private static Stream<Arguments> configurations() {
        return Stream.of("gnu", "bsd", "darwin")
                .flatMap(format -> Stream.of(false, true).map(symbols -> Arguments.of(format, symbols)));
    }

    /// Cross-checks native output, Arkivo rewrites, and Arkivo streaming creation.
    @ParameterizedTest(name = "{0}, symbolTable={1}")
    @MethodSource("configurations")
    void exchangesArchives(String format, boolean symbols) throws Exception {
        String executable = executable();
        Map<String, byte[]> content = inputs();
        Path archive = directory.resolve("native.a");
        List<String> arguments = new ArrayList<>(List.of("--format=" + format, symbols ? "rcsD" : "rcSD",
                archive.toString()));
        for (var entry : content.entrySet()) {
            Files.write(directory.resolve(entry.getKey()), entry.getValue());
            arguments.add(entry.getKey());
        }
        run(executable, arguments);
        Map<String, byte[]> expected = new LinkedHashMap<>();
        for (var entry : content.entrySet()) {
            byte[] actual = run(executable, List.of("p", archive.toString(), entry.getKey()));
            if (format.equals("darwin")) {
                // Darwin counts its data alignment bytes in the member size; llvm-ar returns them too.
                assertTrue(actual.length >= entry.getValue().length && actual.length <= entry.getValue().length + 7);
                assertArrayEquals(entry.getValue(), Arrays.copyOf(actual, entry.getValue().length));
                for (int i = entry.getValue().length; i < actual.length; i++) assertEquals((byte) '\n', actual[i]);
            } else {
                assertArrayEquals(entry.getValue(), actual);
            }
            expected.put(entry.getKey(), actual);
        }
        verify(archive, expected);
        byte[] original = Files.readAllBytes(archive);
        if (format.equals("darwin")) {
            assertEquals(symbols, new String(original, StandardCharsets.ISO_8859_1).contains("__.SYMDEF"));
        }
        assertTruncated(Arrays.copyOf(original, 7));
        assertTruncated(Arrays.copyOf(original, original.length - 1));

        try (var fileSystem = ArArkivoFileSystem.update(archive)) {
            Files.delete(fileSystem.getPath("/empty"));
            Files.move(fileSystem.getPath("/odd"), fileSystem.getPath("/renamed-odd-member"));
            Files.write(fileSystem.getPath("/added"), new byte[]{9, 8, 7});
        }
        expected.remove("empty");
        expected.put("renamed-odd-member", Objects.requireNonNull(expected.remove("odd")));
        expected.put("added", new byte[]{9, 8, 7});
        verify(archive, expected);
        verifyWithNative(executable, archive, expected);

        Path created = directory.resolve("arkivo.a");
        try (var writer = ArArkivoStreamingWriter.open(Files.newOutputStream(created))) {
            for (var entry : content.entrySet()) {
                try (var body = writer.beginFile(entry.getKey()).openOutputStream()) {
                    body.write(entry.getValue());
                }
            }
        }
        verifyWithNative(executable, created, content);
    }

    /// Supplies lengths around name and data boundaries, including UTF-8 and embedded spaces.
    private static Map<String, byte[]> inputs() throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        result.put("a.txt", Files.readAllBytes(root().resolve("a.txt")));
        result.put("b.txt", Files.readAllBytes(root().resolve("b.txt")));
        String[] names = {"empty", "odd", "seven", "eight", "nine", "123456789012345",
                "1234567890123456", "12345678901234567", "name with spaces.txt",
                "unicode-\u00e9-\u4e2d.txt", "long-member-name-with-a-shared-prefix-a",
                "long-member-name-with-a-shared-prefix-b"};
        int[] sizes = {0, 1, 7, 8, 9, 15, 16, 17, 255, 256, 257, 8193};
        Random random = new Random(0x4152_4348L);
        for (int i = 0; i < names.length; i++) {
            byte[] content = new byte[sizes[i]];
            random.nextBytes(content);
            result.put(names[i], content);
        }
        return result;
    }

    /// Checks path and channel entry points, then streams with full, partial, and skipped member bodies.
    private static void verify(Path archive, Map<String, byte[]> expected) throws Exception {
        try (var fileSystem = ArArkivoFileSystem.open(archive)) {
            verifyFileSystem(fileSystem, expected);
        }
        try (var fileSystem = ArArkivoFileSystem.open(Files.newByteChannel(archive))) {
            verifyFileSystem(fileSystem, expected);
        }
        byte[] bytes = Files.readAllBytes(archive);
        for (int chunk : new int[]{1, 7, 8192}) {
            for (int mode = 0; mode < 3; mode++) {
                List<String> names = new ArrayList<>();
                try (var reader = ArArkivoStreamingReader.open(new ChunkedInput(bytes, chunk))) {
                    while (reader.next()) {
                        var attributes = reader.readAttributes();
                        String name = attributes.path();
                        byte[] content = Objects.requireNonNull(expected.get(name), name);
                        assertFalse(names.contains(name), "Duplicate member " + name);
                        names.add(name);
                        assertEquals(content.length, attributes.size(), name);
                        if (mode != 2) {
                            try (var input = reader.openInputStream()) {
                                if (mode == 0) assertArrayEquals(content, input.readAllBytes(), name);
                                else assertEquals(content.length == 0 ? -1 : content[0] & 0xff, input.read(), name);
                            }
                        }
                    }
                }
                assertEquals(expected.keySet(), new java.util.LinkedHashSet<>(names));
            }
        }
    }

    /// Verifies complete member bodies and that symbol/name tables are absent from the file tree.
    private static void verifyFileSystem(ArArkivoFileSystem fileSystem, Map<String, byte[]> expected) throws IOException {
        List<String> names = new ArrayList<>();
        try (var paths = Files.walk(fileSystem.getPath("/"))) {
            for (Path path : paths.toList()) {
                if (!Files.isDirectory(path)) names.add(path.toString().substring(1));
            }
        }
        assertEquals(expected.keySet(), new java.util.HashSet<>(names));
        for (var entry : expected.entrySet()) {
            assertArrayEquals(entry.getValue(), Files.readAllBytes(fileSystem.getPath("/" + entry.getKey())));
        }
    }

    /// Requires the reference tool to list exactly the expected members and reproduce their complete bytes.
    private void verifyWithNative(String executable, Path archive, Map<String, byte[]> expected) throws Exception {
        List<String> names = new String(run(executable, List.of("t", archive.toString())), StandardCharsets.UTF_8)
                .lines().toList();
        assertEquals(expected.size(), names.size());
        assertEquals(expected.keySet(), new java.util.HashSet<>(names));
        for (var entry : expected.entrySet()) {
            assertArrayEquals(entry.getValue(), run(executable, List.of("p", archive.toString(), entry.getKey())));
        }
    }

    /// Requires a checked truncation failure while traversing and draining the generated archive.
    private static void assertTruncated(byte @Unmodifiable [] bytes) {
        assertThrows(IOException.class, () -> {
            try (var reader = ArArkivoStreamingReader.open(new ChunkedInput(bytes, 1))) {
                while (reader.next()) {
                    try (var body = reader.openInputStream()) {
                        body.transferTo(java.io.OutputStream.nullOutputStream());
                    }
                }
            }
        });
    }

    /// Runs llvm-ar directly or through Zig's ar subcommand without interpreting arguments in a shell.
    private byte[] run(String executable, List<String> arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(executable);
        String name = Path.of(executable).getFileName().toString();
        if (name.equalsIgnoreCase("zig.exe") || name.equals("zig")) command.add("ar");
        command.addAll(arguments);
        Path output = directory.resolve("stdout.bin");
        Path error = directory.resolve("stderr.txt");
        Process process = new ProcessBuilder(command).directory(directory.toFile())
                .redirectOutput(output.toFile()).redirectError(error.toFile()).start();
        try {
            process.getOutputStream().close();
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "LLVM ar timed out");
            assertEquals(0, process.exitValue(), Files.readString(error));
            return Files.readAllBytes(output);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "LLVM ar did not terminate");
            }
        }
    }

    /// Returns an explicitly configured LLVM archiver or Zig driver, with an opt-in required-tool check.
    private static String executable() {
        @Nullable String configured = System.getenv("ARKIVO_LLVM_AR_EXECUTABLE");
        boolean available = configured != null && Files.isRegularFile(Path.of(configured));
        if (Boolean.parseBoolean(System.getenv("ARKIVO_REQUIRE_LLVM_AR"))) {
            assertTrue(available, "Set ARKIVO_LLVM_AR_EXECUTABLE to llvm-ar or zig");
        }
        assumeTrue(available, "ARKIVO_LLVM_AR_EXECUTABLE is not configured");
        return Objects.requireNonNull(configured);
    }

    /// Returns the checksum-verified resource directory supplied by Gradle.
    private static Path root() {
        return Path.of(Objects.requireNonNull(System.getProperty("arkivo.llvmAr.testDataDirectory")));
    }

    /// Limits bulk reads and skips to expose field boundaries to the streaming parser.
    @NotNullByDefault
    private static final class ChunkedInput extends ByteArrayInputStream {
        /// The maximum progress allowed in a bulk operation.
        private final int chunk;

        /// Wraps an archive with the requested positive chunk size.
        private ChunkedInput(byte @Unmodifiable [] bytes, int chunk) {
            super(bytes);
            this.chunk = chunk;
        }

        @Override
        public synchronized int read(byte[] target, int offset, int length) {
            return super.read(target, offset, Math.min(length, chunk));
        }

        @Override
        public synchronized long skip(long count) {
            return super.skip(Math.min(count, chunk));
        }
    }
}
