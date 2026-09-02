// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import org.glavo.arkivo.archive.ArchiveEntryAttributes;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.archive.ArkivoStreamingReader;
import org.glavo.arkivo.archive.dmg.DMGImage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.stream.Stream;

/// Fuzzes forward-only and random-access archive parsing with finite operation limits.
@NotNullByDefault
public final class ArchiveFuzzTest {
    /// The number of control bytes preceding an archive payload.
    private static final int HEADER_SIZE = 2;

    /// The independent traversal bound applied in addition to public archive limits.
    private static final int MAXIMUM_VISITED_PATHS = 64;

    /// Creates an archive fuzz-test instance for JUnit.
    public ArchiveFuzzTest() {
    }

    /// Fuzzes named and detected forward-only archive readers, including entry bodies.
    ///
    /// @param data format and chunk controls followed by arbitrary archive bytes
    @MethodSource("streamingArchiveSeeds")
    @FuzzTest(maxDuration = "1m")
    void fuzzArchiveStreaming(byte @Unmodifiable [] data) {
        if (data.length < HEADER_SIZE || data.length > HEADER_SIZE + FuzzSupport.MAX_PARSER_INPUT_SIZE) {
            return;
        }

        int formatControl = Byte.toUnsignedInt(data[0]);
        boolean detectFormat = (formatControl & 0x80) != 0;
        String formatName = FuzzSupport.STREAMING_ARCHIVE_FORMATS.get(
                (formatControl & 0x7f) % FuzzSupport.STREAMING_ARCHIVE_FORMATS.size()
        );
        int bufferSize = 1 + (Byte.toUnsignedInt(data[1]) & 0x7f);
        ByteArrayInputStream source = new ByteArrayInputStream(data, HEADER_SIZE, data.length - HEADER_SIZE);

        try (ArkivoStreamingReader reader = detectFormat
                ? ArkivoFormats.openStreamingReader(source, FuzzSupport.ARCHIVE_READ_OPTIONS)
                : ArkivoFormats.openStreamingReader(formatName, source, FuzzSupport.ARCHIVE_READ_OPTIONS)) {
            int entryCount = 0;
            while (reader.next()) {
                entryCount++;
                if (entryCount > MAXIMUM_VISITED_PATHS) {
                    throw new AssertionError("Archive entry limit was not enforced");
                }
                ArchiveEntryAttributes attributes = reader.readAttributes();
                if (attributes.isRegularFile()) {
                    try (InputStream body = reader.openInputStream()) {
                        drain(body, bufferSize);
                    }
                }
            }
        } catch (IOException | UnsupportedOperationException expectedMalformedArchive) {
            // Malformed data, configured limits, and unsupported stored methods are normal fuzz outcomes.
        }
    }

    /// Fuzzes named and detected random-access archive file systems, metadata, directories, and entry bodies.
    ///
    /// @param data format and chunk controls followed by arbitrary archive bytes
    @MethodSource("fileSystemArchiveSeeds")
    @FuzzTest(maxDuration = "1m")
    void fuzzArchiveFileSystem(byte @Unmodifiable [] data) {
        if (data.length < HEADER_SIZE || data.length > HEADER_SIZE + FuzzSupport.MAX_PARSER_INPUT_SIZE) {
            return;
        }

        int formatControl = Byte.toUnsignedInt(data[0]);
        boolean detectFormat = (formatControl & 0x80) != 0;
        String formatName = FuzzSupport.FILE_SYSTEM_ARCHIVE_FORMATS.get(
                (formatControl & 0x7f) % FuzzSupport.FILE_SYSTEM_ARCHIVE_FORMATS.size()
        );
        int bufferSize = 1 + (Byte.toUnsignedInt(data[1]) & 0x7f);

        try (ReadOnlyByteArrayChannel source = new ReadOnlyByteArrayChannel(
                java.util.Arrays.copyOfRange(data, HEADER_SIZE, data.length)
        ); ArkivoFileSystem fileSystem = detectFormat
                ? ArkivoFormats.openFileSystem(source, FuzzSupport.ARCHIVE_READ_OPTIONS)
                : ArkivoFormats.openFileSystem(formatName, source, FuzzSupport.ARCHIVE_READ_OPTIONS)) {
            inspectFileSystem(fileSystem, bufferSize);
        } catch (DirectoryIteratorException expectedLazyIoFailure) {
            if (expectedLazyIoFailure.getCause() == null) {
                throw expectedLazyIoFailure;
            }
        } catch (IOException | UnsupportedOperationException expectedMalformedArchive) {
            // Malformed data, configured limits, and unsupported stored methods are normal fuzz outcomes.
        }
    }

    /// Fuzzes flattened UDIF layout and run decoding independently of the contained file system.
    ///
    /// @param data arbitrary possible DMG bytes
    @MethodSource("dmgImageSeeds")
    @FuzzTest(maxDuration = "1m")
    void fuzzDMGImage(byte @Unmodifiable [] data) {
        if (data.length > FuzzSupport.MAX_PARSER_INPUT_SIZE) {
            return;
        }

        try (DMGImage image = DMGImage.open(
                new ReadOnlyByteArrayChannel(data),
                FuzzSupport.ARCHIVE_READ_OPTIONS
        ); SeekableByteChannel disk = image.openChannel()) {
            ByteBuffer buffer = ByteBuffer.allocate(4096);
            int total = 0;
            while (true) {
                int count = disk.read(buffer);
                if (count < 0) {
                    break;
                }
                if (count == 0) {
                    throw new AssertionError("DMG decoded channel made no progress");
                }
                total = Math.addExact(total, count);
                if (total > FuzzSupport.MAX_DECODED_OUTPUT_SIZE) {
                    throw new AssertionError("DMG decoded-output limit was not enforced");
                }
                buffer.clear();
            }
        } catch (IOException | UnsupportedOperationException expectedMalformedImage) {
            // Malformed layouts, configured limits, and unsupported run encodings are normal fuzz outcomes.
        }
    }

    /// Traverses a bounded archive file system without following symbolic links.
    ///
    /// @param fileSystem the open archive file system
    /// @param bufferSize the positive entry read buffer size
    /// @throws IOException if metadata or content cannot be read
    private static void inspectFileSystem(ArkivoFileSystem fileSystem, int bufferSize) throws IOException {
        Deque<Path> pending = new ArrayDeque<>();
        pending.add(fileSystem.getPath("/"));
        int visited = 0;
        while (!pending.isEmpty()) {
            Path path = pending.removeFirst();
            visited++;
            if (visited > MAXIMUM_VISITED_PATHS) {
                throw new AssertionError("Archive traversal exceeded its independent path bound");
            }

            BasicFileAttributes attributes = Files.readAttributes(
                    path,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            );
            if (attributes.isDirectory()) {
                try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                    for (Path child : children) {
                        if (visited + pending.size() >= MAXIMUM_VISITED_PATHS) {
                            throw new AssertionError("Archive directory exposed more paths than its entry limit");
                        }
                        pending.addLast(child);
                    }
                }
            } else if (attributes.isRegularFile()) {
                try (InputStream body = Files.newInputStream(path)) {
                    drain(body, bufferSize);
                }
            }
        }
    }

    /// Drains one entry body while independently checking decoded-output limits and progress.
    ///
    /// @param input the entry body
    /// @param bufferSize the positive temporary buffer size
    /// @throws IOException if the body cannot be read
    private static void drain(InputStream input, int bufferSize) throws IOException {
        byte[] buffer = new byte[bufferSize];
        int total = 0;
        while (true) {
            int count = input.read(buffer);
            if (count < 0) {
                return;
            }
            if (count == 0) {
                throw new AssertionError("Archive entry stream made no progress");
            }
            total = Math.addExact(total, count);
            if (total > FuzzSupport.MAX_DECODED_OUTPUT_SIZE) {
                throw new AssertionError("Archive decoded-output limit was not enforced");
            }
        }
    }

    /// Supplies explicit and detected seeds for every forward-only archive implementation.
    ///
    /// @return deterministic streaming-reader seed arguments
    /// @throws IOException if a seed archive or outer compression layer cannot be encoded
    private static Stream<Arguments> streamingArchiveSeeds() throws IOException {
        List<Arguments> seeds = new ArrayList<>();
        byte[] tarArchive = new byte[0];
        int tarIndex = -1;
        for (int index = 0; index < FuzzSupport.STREAMING_ARCHIVE_FORMATS.size(); index++) {
            String formatName = FuzzSupport.STREAMING_ARCHIVE_FORMATS.get(index);
            byte[] archive = FuzzSupport.createArchiveSeed(formatName);
            seeds.add(seed(index, false, archive));
            seeds.add(seed(index, true, archive));
            if ("tar".equals(formatName)) {
                tarArchive = archive;
                tarIndex = index;
            }
        }
        seeds.add(seed(tarIndex, true, FuzzSupport.compressArchive("gzip", tarArchive)));
        seeds.add(seed(tarIndex, true, FuzzSupport.compressArchive("zstd", tarArchive)));
        return seeds.stream();
    }

    /// Supplies explicit and detected seeds for every random-access archive implementation.
    ///
    /// @return deterministic file-system seed arguments
    /// @throws IOException if a seed archive cannot be encoded
    private static Stream<Arguments> fileSystemArchiveSeeds() throws IOException {
        List<Arguments> seeds = new ArrayList<>();
        for (int index = 0; index < FuzzSupport.FILE_SYSTEM_ARCHIVE_FORMATS.size(); index++) {
            byte[] archive = FuzzSupport.createArchiveSeed(
                    FuzzSupport.FILE_SYSTEM_ARCHIVE_FORMATS.get(index)
            );
            seeds.add(seed(index, false, archive));
            seeds.add(seed(index, true, archive));
        }
        return seeds.stream();
    }

    /// Supplies one valid flattened UDIF seed.
    ///
    /// @return the deterministic DMG-image seed arguments
    private static Stream<Arguments> dmgImageSeeds() {
        return Stream.of(Arguments.of((Object) FuzzSupport.createDMGSeed()));
    }

    /// Creates one archive seed argument with explicit or detected format selection.
    ///
    /// @param formatIndex the format-list index
    /// @param detectFormat whether the target should perform format detection
    /// @param archive the complete archive bytes
    /// @return one JUnit seed argument
    private static Arguments seed(
            int formatIndex,
            boolean detectFormat,
            byte @Unmodifiable [] archive
    ) {
        int formatControl = formatIndex | (detectFormat ? 0x80 : 0);
        return Arguments.of((Object) FuzzSupport.prefix(
                new byte[]{(byte) formatControl, 31},
                archive
        ));
    }
}
