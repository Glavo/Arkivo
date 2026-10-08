// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar;

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Exchanges sparse and ordinary TAR archives with an explicitly configured GNU tar executable.
///
/// Set `ARKIVO_GNU_TAR_EXECUTABLE` to enable these tests and `ARKIVO_REQUIRE_GNU_TAR=true` to reject missing tools.
@NotNullByDefault
final class GnuTarCliInteropTest {
    /// Contains only generated archives, source files, extraction directories, and command diagnostics.
    @TempDir
    private Path directory;

    /// The configured executable, validated before each test.
    private String executable = "";

    /// Distinguishes each command's output and error logs.
    private int commandNumber;

    /// Rejects bsdtar or another program accidentally configured as GNU tar.
    @BeforeEach
    void requireGnuTar() throws IOException {
        @Nullable String configured = System.getenv("ARKIVO_GNU_TAR_EXECUTABLE");
        boolean available = configured != null && Files.isRegularFile(Path.of(configured));
        if (Boolean.parseBoolean(System.getenv("ARKIVO_REQUIRE_GNU_TAR"))) {
            assertTrue(available, "Set ARKIVO_GNU_TAR_EXECUTABLE to a GNU tar executable");
        }
        assumeTrue(available, "GNU tar is not configured");
        executable = Objects.requireNonNull(configured);
        assertTrue(run(List.of("--version")).startsWith("tar (GNU tar) "), "GNU tar is required");
    }

    /// Reads five official sparse encodings through fragmented streams and seekable entry channels.
    @ParameterizedTest
    @ValueSource(strings = {"gnu", "oldgnu", "0.0", "0.1", "1.0"})
    void readsOfficialSparseArchives(String format) throws IOException {
        List<Content> contents = contents();
        Path archive = generate(format, contents);
        assertOfficialExtraction(archive, contents);
        assertOfficialSparseEncoding(archive, format, contents);
        byte[] bytes = Files.readAllBytes(archive);
        for (int chunk : new int[]{1, 511, 512, 8192}) {
            try (var reader = TarArkivoStreamingReader.open(new FragmentedInput(bytes, chunk))) {
                for (Content content : contents) {
                    assertTrue(reader.next(), content.name());
                    var attributes = reader.readAttributes(TarArkivoEntryAttributes.class);
                    assertMetadata(attributes, content);
                    try (var input = reader.openInputStream()) {
                        assertArrayEquals(content.bytes(), input.readAllBytes(), content.name());
                        assertEquals(-1, input.read());
                    }
                }
                assertFalse(reader.next());
            }
        }
        try (var fileSystem = TarArkivoFileSystem.open(archive)) {
            for (Content content : contents) {
                Path path = fileSystem.getPath("/" + content.name());
                assertMetadata(Files.readAttributes(path, TarArkivoEntryAttributes.class), content);
                assertArrayEquals(content.bytes(), Files.readAllBytes(path));
                try (var channel = Files.newByteChannel(path)) {
                    assertEquals(content.bytes().length, channel.size());
                    // Descending seeks cross holes, data extents, and TAR block boundaries.
                    for (int position : new int[]{content.bytes().length, content.bytes().length - 1,
                            196609, 131071, 65536, 4097, 513, 512, 511, 1, 0}) {
                        if (position < 0 || position > content.bytes().length) continue;
                        channel.position(position);
                        ByteBuffer target = ByteBuffer.allocateDirect(519);
                        int count = channel.read(target);
                        if (position == content.bytes().length) {
                            assertEquals(-1, count);
                        } else {
                            assertTrue(count > 0);
                            byte[] actual = new byte[count];
                            target.flip().get(actual);
                            assertArrayEquals(Arrays.copyOfRange(content.bytes(), position, position + count), actual);
                            assertEquals(position + count, channel.position());
                        }
                    }
                }
            }
        }
    }

    /// Skips untouched and partially consumed sparse bodies before reading the following member.
    @ParameterizedTest
    @ValueSource(strings = {"gnu", "oldgnu", "0.0", "0.1", "1.0"})
    void advancesPastSparseBodies(String format) throws IOException {
        List<Content> contents = contents();
        byte[] bytes = Files.readAllBytes(generate(format, contents));
        for (int mode = 0; mode < 3; mode++) {
            try (var reader = TarArkivoStreamingReader.open(new FragmentedInput(bytes, 7))) {
                for (int index = 0; index < contents.size(); index++) {
                    Content content = contents.get(index);
                    assertTrue(reader.next());
                    if (index == contents.size() - 1) {
                        assertEquals(content.name(), reader.readAttributes().path());
                        try (var input = reader.openInputStream()) {
                            assertArrayEquals(content.bytes(), input.readAllBytes());
                        }
                    } else if (mode == 1) {
                        try (var input = reader.openInputStream()) {
                            int count = Math.min(513, content.bytes().length);
                            assertArrayEquals(Arrays.copyOf(content.bytes(), count), input.readNBytes(count));
                        }
                    } else if (mode == 2) {
                        try (var channel = reader.openChannel()) {
                            ByteBuffer target = ByteBuffer.allocate(17);
                            int count = channel.read(target);
                            assertEquals(content.bytes().length == 0, count == -1);
                        }
                    }
                }
                assertFalse(reader.next());
            }
        }
    }

    /// Rewrites GNU sparse input without losing untouched bodies, long names, or nondefault metadata.
    @ParameterizedTest
    @ValueSource(strings = {"gnu", "oldgnu", "0.0", "0.1", "1.0"})
    void officialTarReadsRewrittenArchive(String format) throws IOException {
        List<Content> original = contents();
        Path archive = generate(format, original);
        byte[] replacement = new byte[]{11, 22, 33, 44};
        try (var fileSystem = TarArkivoFileSystem.update(archive)) {
            Files.write(fileSystem.getPath("/begin.bin"), replacement);
            Files.delete(fileSystem.getPath("/empty.bin"));
            Files.move(fileSystem.getPath("/trailing-hole.bin"), fileSystem.getPath("/renamed.bin"));
            Files.copy(fileSystem.getPath("/sparse.bin"), fileSystem.getPath("/copy.bin"),
                    StandardCopyOption.COPY_ATTRIBUTES);
            Files.write(fileSystem.getPath("/added.bin"), new byte[]{55, 66});
        }
        List<Content> expected = new ArrayList<>();
        for (Content content : original) {
            switch (content.name()) {
                case "begin.bin" -> expected.add(new Content(content.name(), replacement));
                case "empty.bin" -> { }
                case "trailing-hole.bin" -> expected.add(new Content("renamed.bin", content.bytes()));
                default -> expected.add(content);
            }
            if (content.name().equals("sparse.bin")) expected.add(new Content("copy.bin", content.bytes()));
        }
        expected.add(new Content("added.bin", new byte[]{55, 66}));
        assertOfficialExtraction(archive, expected);
        try (var fileSystem = TarArkivoFileSystem.open(archive)) {
            for (String name : List.of("sparse.bin", "renamed.bin")) {
                var attributes = Files.readAttributes(fileSystem.getPath("/" + name), TarArkivoEntryAttributes.class);
                assertEquals(0640, attributes.mode() & 0777);
                assertEquals(123, attributes.userId());
                assertEquals(456, attributes.groupId());
                assertEquals(Instant.ofEpochSecond(1700000000L), attributes.lastModifiedTime().toInstant());
            }
            assertEquals(Instant.ofEpochSecond(1700000000L),
                    Files.getLastModifiedTime(fileSystem.getPath("/copy.bin")).toInstant());
        }
    }

    /// Verifies both Arkivo creation paths with the official extractor, including Unicode and long names.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void officialTarReadsArkivoOutput(boolean streaming) throws IOException {
        List<Content> contents = contents();
        Path archive = directory.resolve("arkivo.tar");
        if (streaming) {
            try (var writer = TarArkivoStreamingWriter.create(archive)) {
                for (Content content : contents) {
                    try (var output = writer.beginFile(content.name()).openOutputStream()) {
                        output.write(content.bytes());
                    }
                }
            }
        } else {
            try (var fileSystem = TarArkivoFileSystem.create(archive)) {
                for (Content content : contents) {
                    Files.write(fileSystem.getPath("/" + content.name()), content.bytes());
                }
            }
        }
        assertOfficialExtraction(archive, contents);
    }

    /// Creates deterministic source files and uses GNU tar's raw hole detection, independent of host sparse allocation.
    private Path generate(String format, List<Content> contents) throws IOException {
        Path source = Files.createDirectory(directory.resolve("source"));
        for (Content content : contents) Files.write(source.resolve(content.name()), content.bytes());
        Path archive = directory.resolve("official.tar");
        List<String> arguments = new ArrayList<>(List.of("--create", "--force-local", "--sparse",
                "--hole-detection=raw", "--format=" + (format.contains(".") ? "pax" : format),
                "--owner=123", "--group=456", "--numeric-owner", "--mode=0640", "--mtime=@1700000000",
                "--file=" + path(archive), "--directory=" + path(source)));
        if (format.contains(".")) {
            arguments.add("--sparse-version=" + format);
            arguments.add("--pax-option=delete=atime,delete=ctime");
        }
        arguments.add("--");
        for (Content content : contents) arguments.add(content.name());
        run(arguments);
        return archive;
    }

    /// Independently verifies the representative sparse member's dialect, extension map, and logical body.
    private static void assertOfficialSparseEncoding(Path archive, String format, List<Content> contents)
            throws IOException {
        try (var reference = new TarArchiveInputStream(Files.newInputStream(archive))) {
            for (Content content : contents) {
                @Nullable var entry = reference.getNextEntry();
                assertNotNull(entry);
                assertEquals(content.name(), entry.getName());
                assertEquals(content.bytes().length, entry.getRealSize());
                if (content.name().equals("sparse.bin")) {
                    assertTrue(entry.isSparse(), "Producer did not emit a sparse record");
                    assertEquals(!format.contains("."), entry.isOldGNUSparse());
                    assertEquals(format.equals("1.0"), entry.isPaxGNU1XSparse());
                    assertTrue(entry.getOrderedSparseHeaders().size() > 4, "Old GNU extension blocks must be exercised");
                    assertArrayEquals(content.bytes(), reference.readAllBytes(), content.name());
                    // GNU tar verifies the complete archive. Commons Compress 1.28.0 rereads old GNU sparse
                    // extensions after GNU long-name recursion, so it is not the oracle for that combination.
                    return;
                }
                assertArrayEquals(content.bytes(), reference.readAllBytes(), content.name());
            }
            throw new AssertionError("Missing representative sparse member");
        }
    }

    /// Extracts only locally generated regular-file archives and compares every byte and the complete file set.
    private void assertOfficialExtraction(Path archive, List<Content> contents) throws IOException {
        Path extracted = Files.createDirectory(directory.resolve("extracted"));
        run(List.of("--extract", "--force-local", "--no-same-owner", "--no-same-permissions",
                "--file=" + path(archive), "--directory=" + path(extracted)));
        try (var paths = Files.list(extracted)) {
            assertEquals(contents.stream().map(Content::name).sorted().toList(),
                    paths.map(p -> p.getFileName().toString()).sorted().toList());
        }
        for (Content content : contents) {
            assertArrayEquals(content.bytes(), Files.readAllBytes(extracted.resolve(content.name())), content.name());
        }
    }

    /// Checks metadata explicitly selected for the official producer, including logical sparse size.
    private static void assertMetadata(TarArkivoEntryAttributes attributes, Content content) {
        assertEquals(content.name(), attributes.path());
        assertTrue(attributes.isRegularFile());
        assertEquals(content.bytes().length, attributes.size());
        assertEquals(0640, attributes.mode() & 0777);
        assertEquals(123, attributes.userId());
        assertEquals(456, attributes.groupId());
        assertEquals(Instant.ofEpochSecond(1700000000L), attributes.lastModifiedTime().toInstant());
    }

    /// Returns regular, empty, all-hole, leading-hole, trailing-hole, and many-extent members in physical order.
    private static @Unmodifiable List<Content> contents() {
        byte[] sparse = new byte[256 * 1024 + 17];
        for (int extent = 0; extent < 12; extent++) {
            int start = extent * 16384 + 511;
            for (int index = 0; index < 519; index++) sparse[start + index] = (byte) (index * 31 + extent + 1);
        }
        byte[] leading = new byte[65537];
        leading[leading.length - 1] = 99;
        byte[] trailing = new byte[65537];
        trailing[0] = 88;
        return List.of(new Content("begin.bin", new byte[]{1, 2, 3}),
                new Content("sparse.bin", sparse), new Content("empty.bin", new byte[0]),
                new Content("all-hole.bin", new byte[65537]), new Content("leading-hole.bin", leading),
                new Content("trailing-hole.bin", trailing),
                new Content("\u7a7a\u6d1e-\u00e9.bin", sparse),
                new Content("long-" + "segment".repeat(18) + ".bin", sparse),
                new Content("end.bin", new byte[]{4, 5, 6, 7}));
    }

    /// Returns forward-slash paths accepted by both native and MSYS GNU tar.
    private static String path(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    /// Runs GNU tar without ambient options, with bounded waiting and separate file-backed diagnostics.
    private String run(List<String> arguments) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(arguments);
        int number = commandNumber++;
        Path output = directory.resolve("command-" + number + ".out");
        Path error = directory.resolve("command-" + number + ".err");
        ProcessBuilder builder = new ProcessBuilder(command).redirectOutput(output.toFile()).redirectError(error.toFile());
        builder.environment().remove("TAR_OPTIONS");
        builder.environment().remove("POSIXLY_CORRECT");
        builder.environment().put("LC_ALL", "C.UTF-8");
        Process process = builder.start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                throw new IOException("GNU tar timed out: " + arguments);
            }
            assertEquals(0, process.exitValue(), () -> {
                try {
                    return arguments + "\n" + Files.readString(error);
                } catch (IOException failure) {
                    return arguments + "\nCannot read diagnostics: " + failure;
                }
            });
            return Files.readString(output);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for GNU tar", failure);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    /// One generated regular file and its complete expected logical body.
    ///
    /// @param name the relative file name
    /// @param bytes the immutable expected bytes, including holes
    @NotNullByDefault
    private record Content(String name, byte @Unmodifiable [] bytes) {
    }

    /// Restricts physical read sizes while preserving normal input-stream ownership.
    @NotNullByDefault
    private static final class FragmentedInput extends ByteArrayInputStream {
        /// The maximum bytes returned by one bulk read.
        private final int chunk;

        /// Wraps the archive without modifying its bytes.
        private FragmentedInput(byte[] bytes, int chunk) {
            super(bytes);
            this.chunk = chunk;
        }

        /// Reads at most the configured number of physical archive bytes.
        @Override
        public synchronized int read(byte[] bytes, int offset, int length) {
            return super.read(bytes, offset, Math.min(length, chunk));
        }
    }
}
