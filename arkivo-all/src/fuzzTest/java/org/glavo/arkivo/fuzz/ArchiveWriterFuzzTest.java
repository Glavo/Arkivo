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
    /// Control bytes select entry count, write chunking, channel or stream bodies, body-free entries, and whether the
    /// final pending handle is completed implicitly by writer close. Every generated path is unique and archive-local.
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
                ArkivoStreamingWriter.Entry entry = writer.beginFile(expected.path());
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

        if ("7z".equals(formatName)) {
            verifyFileSystem(archive.toByteArray(), expectedEntries);
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
            boolean hasBody = (entryControl & 1) == 0;
            boolean useChannel = (entryControl & 2) == 0;
            byte[] content = hasBody ? Arrays.copyOfRange(data, start, end) : new byte[0];
            String path = "entry-" + index + '-' + Integer.toHexString(entryControl) + ".bin";
            result.add(new ExpectedEntry(path, content, hasBody, useChannel));
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
                if (!expected.path().equals(attributes.path()) || !attributes.isRegularFile()) {
                    throw new AssertionError("Archive writer changed generated entry metadata");
                }
                try (InputStream body = reader.openInputStream()) {
                    if (!Arrays.equals(expected.content(), body.readAllBytes())) {
                        throw new AssertionError("Archive writer changed generated entry content");
                    }
                }
            }
        }
        if (index != expectedEntries.size()) {
            throw new AssertionError("Archive reader omitted generated entries");
        }
    }

    /// Verifies generated 7z entries through its indexed file system.
    private static void verifyFileSystem(
            byte @Unmodifiable [] archive,
            List<ExpectedEntry> expectedEntries
    ) throws IOException {
        try (ReadOnlyByteArrayChannel source = new ReadOnlyByteArrayChannel(archive);
             ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(
                     "7z",
                     source,
                     FuzzSupport.ARCHIVE_READ_OPTIONS
             )) {
            for (ExpectedEntry expected : expectedEntries) {
                byte[] actual = Files.readAllBytes(fileSystem.getPath("/" + expected.path()));
                if (!Arrays.equals(expected.content(), actual)) {
                    throw new AssertionError("7z writer changed generated entry content");
                }
            }
            try (Stream<java.nio.file.Path> paths = Files.walk(fileSystem.getPath("/"))) {
                long actualEntryCount = paths.filter(Files::isRegularFile).count();
                if (actualEntryCount != expectedEntries.size()) {
                    throw new AssertionError("7z writer emitted an unexpected number of entries");
                }
            }
        }
    }

    /// Supplies representative lifecycle controls to every public streaming archive writer.
    ///
    /// @return deterministic archive-writer seed arguments
    private static Stream<Arguments> archiveWriterSeeds() {
        return java.util.stream.IntStream.range(0, FuzzSupport.STREAMING_WRITER_FORMATS.size())
                .mapToObj(index -> Arguments.of((Object) FuzzSupport.prefix(
                        new byte[]{(byte) index, 3, 7, 0x60},
                        FuzzSupport.SEED_CONTENT
                )));
    }

    /// Describes one generated regular-file entry and its selected body transport.
    ///
    /// @param path the unique archive-local path
    /// @param content the expected entry bytes
    /// @param hasBody whether the entry body is explicitly opened
    /// @param useChannel whether an explicitly opened body uses a channel rather than a stream
    @NotNullByDefault
    private record ExpectedEntry(
            String path,
            byte @Unmodifiable [] content,
            boolean hasBody,
            boolean useChannel
    ) {
    }
}
