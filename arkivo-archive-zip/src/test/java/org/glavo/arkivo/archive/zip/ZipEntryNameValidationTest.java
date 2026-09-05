// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.internal.ReadOnlyByteArrayChannel;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies ZIP entry-name decoding, normalization, writing, and indexed-path conflict rejection.
@NotNullByDefault
final class ZipEntryNameValidationTest {
    /// The local-file-header signature.
    private static final int LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50;

    /// The central-directory-file-header signature.
    private static final int CENTRAL_DIRECTORY_HEADER_SIGNATURE = 0x02014b50;

    /// The end-of-central-directory signature.
    private static final int END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50;

    /// The general-purpose flag declaring UTF-8 entry metadata.
    private static final int UTF8_FLAG = 1 << 11;

    /// Verifies streaming and indexed readers enforce the same unsafe-name boundary.
    ///
    /// @param description the case name used in parameterized-test output
    /// @param rawName the encoded entry name
    /// @param flags the general-purpose flags stored in both headers
    /// @param expectedMessage the diagnostic fragment required from both APIs
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidEntryNames")
    void rejectsUnsafeNamesThroughBothReaderModels(
            String description,
            byte @Unmodifiable [] rawName,
            int flags,
            String expectedMessage
    ) throws IOException {
        byte @Unmodifiable [] archive = archive(flags, List.of(rawName));

        IOException streamingFailure;
        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            streamingFailure = assertThrows(IOException.class, reader::next);
        }
        assertTrue(streamingFailure.getMessage().contains(expectedMessage), description);

        IOException indexedFailure = assertIndexedReadRejected(archive);
        assertTrue(indexedFailure.getMessage().contains(expectedMessage), description);
    }

    /// Verifies the streaming writer rejects unsafe paths without leaving an entry pending.
    ///
    /// @param description the case name used in parameterized-test output
    /// @param path the unsafe logical entry path
    /// @param expectedMessage the diagnostic fragment required from the writer
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidWriterEntryPaths")
    void rejectsUnsafeWriterPathsWithoutPoisoningWriter(
            String description,
            String path,
            String expectedMessage
    ) throws IOException {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(archive)) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> writer.beginFile(path)
            );
            assertTrue(failure.getMessage().contains(expectedMessage), description);

            try (var output = writer.beginFile("safe.txt").openOutputStream()) {
                output.write('x');
            }
        }

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                new ByteArrayInputStream(archive.toByteArray())
        )) {
            assertTrue(reader.next());
            assertEquals("safe.txt", reader.readAttributes(ZipArkivoEntryAttributes.class).path());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(new byte[]{'x'}, input.readAllBytes());
            }
            assertFalse(reader.next());
        }
    }

    /// Verifies separator and dot-segment normalization remains consistent with raw metadata preservation.
    @Test
    void normalizesSafeNamesWithoutDiscardingRawMetadata() throws IOException {
        byte @Unmodifiable [] backslashName = "dir\\file.txt".getBytes(StandardCharsets.UTF_8);
        byte @Unmodifiable [] backslashArchive = archive(UTF8_FLAG, List.of(backslashName));

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                new ByteArrayInputStream(backslashArchive)
        )) {
            assertTrue(reader.next());
            ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("dir/file.txt", attributes.path());
            assertArrayEquals(backslashName, attributes.rawPath());
            assertFalse(reader.next());
        }

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                new ReadOnlyByteArrayChannel(backslashArchive)
        )) {
            assertTrue(Files.isRegularFile(fileSystem.getPath("/dir/file.txt")));
        }

        byte @Unmodifiable [] dottedName = "dir//./hello.txt".getBytes(StandardCharsets.UTF_8);
        byte @Unmodifiable [] dottedArchive = archive(UTF8_FLAG, List.of(dottedName));
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                new ReadOnlyByteArrayChannel(dottedArchive)
        )) {
            var path = fileSystem.getPath("/dir/hello.txt");
            ZipArkivoEntryAttributes attributes = Files.readAttributes(path, ZipArkivoEntryAttributes.class);
            assertEquals("dir//./hello.txt", attributes.path());
            assertArrayEquals(dottedName, attributes.rawPath());
            assertArrayEquals(new byte[0], Files.readAllBytes(path));
        }

        byte @Unmodifiable [] directoryName = "nested\\".getBytes(StandardCharsets.UTF_8);
        byte @Unmodifiable [] fileName = "nested\\value.txt".getBytes(StandardCharsets.UTF_8);
        byte @Unmodifiable [] hierarchyArchive = archive(UTF8_FLAG, List.of(directoryName, fileName));
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                new ReadOnlyByteArrayChannel(hierarchyArchive)
        )) {
            assertTrue(Files.isDirectory(fileSystem.getPath("/nested")));
            assertTrue(Files.isRegularFile(fileSystem.getPath("/nested/value.txt")));
        }
    }

    /// Verifies exact, normalized, mixed-separator, and file-parent conflicts are rejected while indexing.
    ///
    /// @param description the case name used in parameterized-test output
    /// @param firstName the first encoded entry name
    /// @param secondName the conflicting second encoded entry name
    /// @param expectedMessage the diagnostic fragment required from the indexed reader
    @ParameterizedTest(name = "{0}")
    @MethodSource("conflictingEntryNames")
    void rejectsConflictingIndexedNames(
            String description,
            byte @Unmodifiable [] firstName,
            byte @Unmodifiable [] secondName,
            String expectedMessage
    ) {
        byte @Unmodifiable [] archive = archive(UTF8_FLAG, List.of(firstName, secondName));

        IOException failure = assertIndexedReadRejected(archive);
        assertTrue(failure.getMessage().contains(expectedMessage), description);
    }

    /// Returns unsafe encoded names and their stable validation diagnostics.
    private static Stream<Arguments> invalidEntryNames() {
        return Stream.of(
                Arguments.of("malformed UTF-8", new byte[]{(byte) 0xc3, 0x28}, UTF8_FLAG,
                        "Failed to decode ZIP entry name"),
                Arguments.of("empty", new byte[0], 0, "ZIP entry is missing a path"),
                Arguments.of("dot only", new byte[]{'.'}, 0, "ZIP entry is missing a path"),
                Arguments.of("parent segment", bytes("../evil.txt"), 0,
                        "ZIP entry path must not contain .."),
                Arguments.of("absolute", bytes("/evil.txt"), 0, "ZIP entry path must be relative"),
                Arguments.of("drive root", bytes("C:/evil.txt"), 0, "ZIP entry path must be relative"),
                Arguments.of("backslash parent segment", bytes("..\\evil.txt"), 0,
                        "ZIP entry path must not contain ..")
        );
    }

    /// Returns unsafe logical paths and their stable streaming-writer diagnostics.
    private static Stream<Arguments> invalidWriterEntryPaths() {
        return Stream.of(
                Arguments.of("empty", "", "must not be empty"),
                Arguments.of("dot only", ".", "must not be empty"),
                Arguments.of("absolute", "/evil.txt", "must be relative"),
                Arguments.of("backslash absolute", "\\evil.txt", "must be relative"),
                Arguments.of("drive root", "C:/evil.txt", "must not contain drive roots"),
                Arguments.of("backslash drive root", "C:\\evil.txt", "must not contain drive roots"),
                Arguments.of("parent segment", "safe/../evil.txt", "must not contain ..")
        );
    }

    /// Returns indexed name pairs that collide after archive-path normalization.
    private static Stream<Arguments> conflictingEntryNames() {
        return Stream.of(
                Arguments.of("exact duplicate", bytes("duplicate.txt"), bytes("duplicate.txt"),
                        "Duplicate ZIP entry path"),
                Arguments.of("dot and repeated separators", bytes("dir/hello.txt"), bytes("dir//./hello.txt"),
                        "Duplicate ZIP entry path"),
                Arguments.of("mixed separators", bytes("dir/file.txt"), bytes("dir\\file.txt"),
                        "Duplicate ZIP entry path"),
                Arguments.of("regular file used as parent", bytes("dir"), bytes("dir/file.txt"),
                        "ZIP entry path conflicts with directory"),
                Arguments.of("implicit directory replaced by regular file", bytes("dir/file.txt"), bytes("dir"),
                        "ZIP entry path conflicts with directory")
        );
    }

    /// Forces indexed metadata parsing and returns its validation failure.
    private static IOException assertIndexedReadRejected(byte @Unmodifiable [] archive) {
        return assertThrows(IOException.class, () -> {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                    new ReadOnlyByteArrayChannel(archive)
            )) {
                Files.readAttributes(fileSystem.getPath("/probe"), ZipArkivoEntryAttributes.class);
            }
        });
    }

    /// Encodes an ASCII test name.
    private static byte @Unmodifiable [] bytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    /// Builds a classic ZIP archive containing empty stored entries with caller-controlled raw names.
    private static byte @Unmodifiable [] archive(
            int flags,
            @Unmodifiable List<byte @Unmodifiable []> rawNames
    ) {
        int localSize = 0;
        int centralSize = 0;
        for (byte @Unmodifiable [] rawName : rawNames) {
            localSize = Math.addExact(localSize, 30 + rawName.length);
            centralSize = Math.addExact(centralSize, 46 + rawName.length);
        }

        ByteBuffer output = ByteBuffer.allocate(Math.addExact(Math.addExact(localSize, centralSize), 22))
                .order(ByteOrder.LITTLE_ENDIAN);
        int[] localOffsets = new int[rawNames.size()];

        for (int index = 0; index < rawNames.size(); index++) {
            byte @Unmodifiable [] rawName = rawNames.get(index);
            localOffsets[index] = output.position();
            output.putInt(LOCAL_FILE_HEADER_SIGNATURE);
            output.putShort((short) 20);
            output.putShort((short) flags);
            output.putShort((short) ZipMethod.STORED.id());
            output.putShort((short) 0);
            output.putShort((short) 0);
            output.putInt(0);
            output.putInt(0);
            output.putInt(0);
            output.putShort((short) rawName.length);
            output.putShort((short) 0);
            output.put(rawName);
        }

        int centralDirectoryOffset = output.position();
        for (int index = 0; index < rawNames.size(); index++) {
            byte @Unmodifiable [] rawName = rawNames.get(index);
            boolean directory = rawName.length > 0
                    && (rawName[rawName.length - 1] == '/' || rawName[rawName.length - 1] == '\\');
            output.putInt(CENTRAL_DIRECTORY_HEADER_SIGNATURE);
            output.putShort((short) 20);
            output.putShort((short) 20);
            output.putShort((short) flags);
            output.putShort((short) ZipMethod.STORED.id());
            output.putShort((short) 0);
            output.putShort((short) 0);
            output.putInt(0);
            output.putInt(0);
            output.putInt(0);
            output.putShort((short) rawName.length);
            output.putShort((short) 0);
            output.putShort((short) 0);
            output.putShort((short) 0);
            output.putShort((short) 0);
            output.putInt(directory ? 0x10 : 0);
            output.putInt(localOffsets[index]);
            output.put(rawName);
        }
        int centralDirectorySize = output.position() - centralDirectoryOffset;

        output.putInt(END_OF_CENTRAL_DIRECTORY_SIGNATURE);
        output.putShort((short) 0);
        output.putShort((short) 0);
        output.putShort((short) rawNames.size());
        output.putShort((short) rawNames.size());
        output.putInt(centralDirectorySize);
        output.putInt(centralDirectoryOffset);
        output.putShort((short) 0);
        return output.array();
    }

}
