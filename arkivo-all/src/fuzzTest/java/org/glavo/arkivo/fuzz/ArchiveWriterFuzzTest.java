// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import org.glavo.arkivo.archive.ArchiveEntryAttributes;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.archive.ArkivoStreamingReader;
import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/// Fuzzes public streaming archive writers through valid entry lifecycle transitions and round trips.
@NotNullByDefault
public final class ArchiveWriterFuzzTest {
    /// The number of state-control bytes preceding generated entry content.
    private static final int HEADER_SIZE = 4;

    /// The largest generated entry count.
    private static final int MAXIMUM_ENTRY_COUNT = 8;

    /// Creates an archive-writer fuzz-test instance for JUnit.
    public ArchiveWriterFuzzTest() {
    }

    /// Generates a bounded archive and verifies every committed entry through the corresponding public reader.
    ///
    /// Control bytes select entry count, regular files, directories, symbolic links, channel or stream bodies,
    /// body-free files, and whether the final pending handle is completed implicitly by writer close. Every generated
    /// path is unique and archive-local.
    ///
    /// @param data state controls followed by arbitrary entry content
    /// @throws IOException if a valid writer sequence or its round trip unexpectedly fails
    @MethodSource("archiveWriterSeeds")
    @FuzzTest(maxDuration = "1m")
    void fuzzArchiveWriterState(byte @Unmodifiable [] data) throws IOException {
        if (data.length < HEADER_SIZE
                || data.length > HEADER_SIZE + FuzzSupport.MAX_ROUND_TRIP_INPUT_SIZE) {
            return;
        }

        int formatIndex = Byte.toUnsignedInt(data[0]) % FuzzSupport.STREAMING_WRITER_FORMATS.size();
        String formatName = FuzzSupport.STREAMING_WRITER_FORMATS.get(formatIndex);
        int entryCount = 1 + (Byte.toUnsignedInt(data[1]) % MAXIMUM_ENTRY_COUNT);
        int chunkSize = 1 + (Byte.toUnsignedInt(data[2]) & 0x3f);
        int globalControl = Byte.toUnsignedInt(data[3]);
        List<ExpectedEntry> expectedEntries = expectedEntries(data, entryCount, globalControl);

        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ArkivoStreamingWriter writer = ArkivoFormats.openStreamingWriter(formatName, archive)) {
            for (int index = 0; index < expectedEntries.size(); index++) {
                ExpectedEntry expected = expectedEntries.get(index);
                ArkivoStreamingWriter.Entry entry = switch (expected.kind()) {
                    case FILE -> writer.beginFile(expected.path());
                    case DIRECTORY -> writer.beginDirectory(expected.path());
                    case SYMBOLIC_LINK -> writer.beginSymbolicLink(expected.path(), expected.linkTarget());
                };
                if (!expected.hasBody()) {
                    if (index + 1 < expectedEntries.size() || (globalControl & 0x20) == 0) {
                        entry.close();
                    }
                    continue;
                }

                boolean leaveFinalBodyOpen = index + 1 == expectedEntries.size() && (globalControl & 0x40) != 0;
                if (expected.useChannel()) {
                    WritableByteChannel body = entry.openChannel();
                    write(body, expected.content(), chunkSize);
                    if (!leaveFinalBodyOpen) {
                        body.close();
                    }
                } else {
                    OutputStream body = entry.openOutputStream();
                    write(body, expected.content(), chunkSize);
                    if (!leaveFinalBodyOpen) {
                        body.close();
                    }
                }
            }
        }

        if ("7z".equals(formatName) || "zip".equals(formatName)) {
            verifyFileSystem(formatName, archive.toByteArray(), expectedEntries);
        } else {
            verifyStreaming(formatName, archive.toByteArray(), expectedEntries);
        }
    }

    /// Derives unique entries and their lifecycle decisions from one fuzz input.
    private static List<ExpectedEntry> expectedEntries(
            byte @Unmodifiable [] data,
            int entryCount,
            int globalControl
    ) {
        List<ExpectedEntry> result = new ArrayList<>(entryCount);
        int payloadSize = data.length - HEADER_SIZE;
        for (int index = 0; index < entryCount; index++) {
            int start = HEADER_SIZE + payloadSize * index / entryCount;
            int end = HEADER_SIZE + payloadSize * (index + 1) / entryCount;
            int entryControl = payloadSize == 0
                    ? globalControl + index
                    : Byte.toUnsignedInt(data[HEADER_SIZE + index % payloadSize]);
            EntryKind kind = switch ((entryControl >>> 2) % 3) {
                case 0 -> EntryKind.FILE;
                case 1 -> EntryKind.DIRECTORY;
                default -> EntryKind.SYMBOLIC_LINK;
            };
            boolean hasBody = kind == EntryKind.FILE && (entryControl & 1) == 0;
            boolean useChannel = (entryControl & 2) == 0;
            byte @Unmodifiable [] content = hasBody ? Arrays.copyOfRange(data, start, end) : new byte[0];
            String path = "entry-" + index + '-' + Integer.toHexString(entryControl) + ".bin";
            String linkTarget = "target-" + index + '-' + Integer.toHexString(entryControl) + ".txt";
            result.add(new ExpectedEntry(path, content, kind, hasBody, useChannel, linkTarget));
        }
        return result;
    }

    /// Writes a complete entry body through a caller-selected channel chunk size.
    private static void write(
            WritableByteChannel output,
            byte @Unmodifiable [] content,
            int chunkSize
    ) throws IOException {
        int offset = 0;
        while (offset < content.length) {
            int count = Math.min(chunkSize, content.length - offset);
            ByteBuffer source = ByteBuffer.wrap(content, offset, count);
            while (source.hasRemaining()) {
                int written = output.write(source);
                if (written == 0) {
                    throw new AssertionError("Archive entry channel made no progress");
                }
            }
            offset += count;
        }
    }

    /// Writes a complete entry body through a caller-selected stream chunk size.
    private static void write(
            OutputStream output,
            byte @Unmodifiable [] content,
            int chunkSize
    ) throws IOException {
        int offset = 0;
        while (offset < content.length) {
            int count = Math.min(chunkSize, content.length - offset);
            output.write(content, offset, count);
            offset += count;
        }
    }

    /// Verifies generated entries through a forward-only reader.
    private static void verifyStreaming(
            String formatName,
            byte @Unmodifiable [] archive,
            List<ExpectedEntry> expectedEntries
    ) throws IOException {
        int index = 0;
        try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(
                formatName,
                new ByteArrayInputStream(archive),
                FuzzSupport.ARCHIVE_READ_OPTIONS
        )) {
            while (reader.next()) {
                if (index >= expectedEntries.size()) {
                    throw new AssertionError("Archive writer emitted an unexpected entry");
                }
                ExpectedEntry expected = expectedEntries.get(index++);
                ArchiveEntryAttributes attributes = reader.readAttributes();
                String actualPath = attributes.path();
                boolean pathMatches = expected.path().equals(actualPath)
                        || expected.kind() == EntryKind.DIRECTORY
                        && (expected.path() + '/').equals(actualPath);
                boolean kindMatches = switch (expected.kind()) {
                    case FILE -> attributes.isRegularFile();
                    case DIRECTORY -> attributes.isDirectory();
                    case SYMBOLIC_LINK -> attributes.isSymbolicLink();
                };
                if (!pathMatches || !kindMatches) {
                    throw new AssertionError("Archive writer changed generated entry metadata");
                }
                if (expected.kind() == EntryKind.FILE) {
                    try (InputStream body = reader.openInputStream()) {
                        if (!Arrays.equals(expected.content(), body.readAllBytes())) {
                            throw new AssertionError("Archive writer changed generated entry content");
                        }
                    }
                }
            }
        }
        if (index != expectedEntries.size()) {
            throw new AssertionError("Archive reader omitted generated entries");
        }
    }

    /// Verifies generated ZIP or 7z entries through its indexed file system.
    private static void verifyFileSystem(
            String formatName,
            byte @Unmodifiable [] archive,
            List<ExpectedEntry> expectedEntries
    ) throws IOException {
        ReadOnlyByteArrayChannel source = new ReadOnlyByteArrayChannel(archive);
        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(
                formatName,
                source,
                FuzzSupport.ARCHIVE_READ_OPTIONS
        )) {
            for (ExpectedEntry expected : expectedEntries) {
                Path path = fileSystem.getPath("/" + expected.path());
                BasicFileAttributes attributes = Files.readAttributes(
                        path,
                        BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS
                );
                switch (expected.kind()) {
                    case FILE -> {
                        if (!attributes.isRegularFile()
                                || !Arrays.equals(expected.content(), Files.readAllBytes(path))) {
                            throw new AssertionError("Archive writer changed generated file content");
                        }
                    }
                    case DIRECTORY -> {
                        if (!attributes.isDirectory()) {
                            throw new AssertionError("Archive writer changed a generated directory");
                        }
                    }
                    case SYMBOLIC_LINK -> {
                        if (!attributes.isSymbolicLink()
                                || !expected.linkTarget().equals(Files.readSymbolicLink(path).toString())) {
                            throw new AssertionError("Archive writer changed a generated symbolic link");
                        }
                    }
                }
            }
            try (Stream<java.nio.file.Path> paths = Files.walk(fileSystem.getPath("/"))) {
                long actualEntryCount = paths.skip(1L).count();
                if (actualEntryCount != expectedEntries.size()) {
                    throw new AssertionError("Archive writer emitted an unexpected number of entries");
                }
            }
        }
        if (source.isOpen()) {
            throw new AssertionError("Archive file system did not close its owned source");
        }
    }

    /// Supplies representative lifecycle controls to every public streaming archive writer.
    ///
    /// @return deterministic archive-writer seed arguments
    private static Stream<Arguments> archiveWriterSeeds() {
        return java.util.stream.IntStream.range(0, FuzzSupport.STREAMING_WRITER_FORMATS.size())
                .boxed()
                .flatMap(index -> Stream.of(
                        Arguments.of((Object) FuzzSupport.prefix(
                                new byte[]{index.byteValue(), 3, 7, 0, 0},
                                FuzzSupport.SEED_CONTENT
                        )),
                        Arguments.of((Object) FuzzSupport.prefix(
                                new byte[]{index.byteValue(), 3, 1, 0x60, 0, 4, 8},
                                FuzzSupport.SEED_CONTENT
                        ))
                ));
    }

    /// Identifies a generated portable archive entry kind.
    @NotNullByDefault
    private enum EntryKind {
        /// A regular file that may expose an explicit body.
        FILE,

        /// A metadata-only directory.
        DIRECTORY,

        /// A metadata-only symbolic link.
        SYMBOLIC_LINK
    }

    /// Describes one generated entry and its selected body transport.
    ///
    /// @param path the unique archive-local path
    /// @param content the expected entry bytes
    /// @param kind the portable entry kind
    /// @param hasBody whether the entry body is explicitly opened
    /// @param useChannel whether an explicitly opened body uses a channel rather than a stream
    /// @param linkTarget the symbolic-link target, retained for every kind to avoid nullable model state
    @NotNullByDefault
    private record ExpectedEntry(
            String path,
            byte @Unmodifiable [] content,
            EntryKind kind,
            boolean hasBody,
            boolean useChannel,
            String linkTarget
    ) {
    }
}
