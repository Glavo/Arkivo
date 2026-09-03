// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import org.glavo.arkivo.archive.ArchiveEntryAttributes;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.archive.ArkivoStreamingReader;
import org.glavo.arkivo.archive.dmg.DMGImage;
import org.glavo.arkivo.codec.CompressionFormat;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/// Fuzzes forward-only and random-access archive parsing with finite operation limits.
@NotNullByDefault
public final class ArchiveFuzzTest {
    /// The number of control bytes preceding an archive payload.
    private static final int HEADER_SIZE = 2;

    /// The independent traversal bound applied in addition to public archive limits.
    private static final int MAXIMUM_VISITED_PATHS = 64;

    /// Fully consumes each regular-file body.
    private static final int BODY_MODE_FULL = 0;

    /// Leaves each regular-file body unopened before advancing.
    private static final int BODY_MODE_SKIPPED = 1;

    /// Opens each regular-file body, performs one read, and leaves it for reader-driven cleanup.
    private static final int BODY_MODE_PARTIAL = 2;

    /// Number of concrete body-consumption modes.
    private static final int BODY_MODE_COUNT = 3;

    /// Cycles through full, skipped, and partial body handling across successive entries.
    private static final int BODY_MODE_ALTERNATING = 3;

    /// Bits of the streaming control byte used for the positive body-buffer size minus one.
    private static final int STREAMING_BUFFER_SIZE_MASK = 0x3f;

    /// Creates an archive fuzz-test instance for JUnit.
    public ArchiveFuzzTest() {
    }

    /// Verifies that the source-generated RAR seed reaches entry and body parsing through both public reader models.
    @Test
    void generatedRARSeedContainsStoredFile() throws IOException {
        byte @Unmodifiable [] archive = FuzzSupport.createArchiveSeed("rar");

        ReadOnlyByteArrayChannel streamingSource = new ReadOnlyByteArrayChannel(archive);
        try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(
                "rar",
                (ReadableByteChannel) streamingSource,
                FuzzSupport.ARCHIVE_READ_OPTIONS
        )) {
            if (!reader.next()) {
                throw new AssertionError("Generated RAR seed omitted its stored file");
            }
            ArchiveEntryAttributes attributes = reader.readAttributes();
            if (!"seed.txt".equals(attributes.path()) || !attributes.isRegularFile()) {
                throw new AssertionError("Generated RAR seed changed its stored-file metadata");
            }
            try (InputStream body = reader.openInputStream()) {
                if (!Arrays.equals(FuzzSupport.SEED_CONTENT, body.readAllBytes())) {
                    throw new AssertionError("Generated RAR seed changed its stored-file content");
                }
            }
            if (reader.next()) {
                throw new AssertionError("Generated RAR seed contains an unexpected entry");
            }
        }
        if (streamingSource.isOpen()) {
            throw new AssertionError("RAR streaming reader did not close its owned source");
        }

        ReadOnlyByteArrayChannel fileSystemSource = new ReadOnlyByteArrayChannel(archive);
        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(
                "rar",
                fileSystemSource,
                FuzzSupport.ARCHIVE_READ_OPTIONS
        )) {
            Path seedPath = fileSystem.getPath("/seed.txt");
            if (!Files.isRegularFile(seedPath, LinkOption.NOFOLLOW_LINKS)
                    || !Arrays.equals(FuzzSupport.SEED_CONTENT, Files.readAllBytes(seedPath))) {
                throw new AssertionError("Generated RAR seed did not round trip through its file system");
            }
        }
        if (fileSystemSource.isOpen()) {
            throw new AssertionError("RAR file system did not close its owned source");
        }
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
        int bodyControl = Byte.toUnsignedInt(data[1]);
        int bodyMode = bodyControl >>> 6;
        int bufferSize = 1 + (bodyControl & STREAMING_BUFFER_SIZE_MASK);
        ReadOnlyByteArrayChannel source = new ReadOnlyByteArrayChannel(
                Arrays.copyOfRange(data, HEADER_SIZE, data.length),
                bufferSize
        );

        @Nullable ReadableByteChannel retainedBody = null;
        @Nullable ArchiveEntryAttributes retainedAttributes = null;
        String retainedPath = "";
        long retainedSize = 0L;
        try (ArkivoStreamingReader reader = detectFormat
                ? ArkivoFormats.openStreamingReader(
                        (ReadableByteChannel) source,
                        FuzzSupport.ARCHIVE_READ_OPTIONS
                )
                : ArkivoFormats.openStreamingReader(
                        formatName,
                        (ReadableByteChannel) source,
                        FuzzSupport.ARCHIVE_READ_OPTIONS
                )) {
            int entryCount = 0;
            while (reader.next()) {
                requireClosed(retainedBody);
                retainedBody = null;
                verifyRetainedAttributes(retainedAttributes, retainedPath, retainedSize);

                entryCount++;
                if (entryCount > MAXIMUM_VISITED_PATHS) {
                    throw new AssertionError("Archive entry limit was not enforced");
                }
                ArchiveEntryAttributes attributes = reader.readAttributes();
                if (attributes.isRegularFile()) {
                    int currentBodyMode = bodyMode == BODY_MODE_ALTERNATING
                            ? (entryCount - 1) % BODY_MODE_COUNT
                            : bodyMode;
                    if (currentBodyMode == BODY_MODE_FULL) {
                        try (InputStream body = reader.openInputStream()) {
                            drain(body, bufferSize);
                        }
                    } else if (currentBodyMode == BODY_MODE_PARTIAL) {
                        retainedBody = reader.openChannel();
                        int count = retainedBody.read(ByteBuffer.allocate(bufferSize));
                        if (count == 0) {
                            throw new AssertionError("Archive entry channel made no progress");
                        }
                    }
                }

                retainedAttributes = attributes;
                retainedPath = attributes.path();
                retainedSize = attributes.size();
            }
            requireClosed(retainedBody);
            verifyRetainedAttributes(retainedAttributes, retainedPath, retainedSize);
        } catch (IOException | UnsupportedOperationException expectedMalformedArchive) {
            // Malformed data, configured limits, and unsupported stored methods are normal fuzz outcomes.
        }
        verifyRetainedAttributes(retainedAttributes, retainedPath, retainedSize);
        if (source.isOpen()) {
            throw new AssertionError("Archive streaming reader did not close its owned source");
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

        ReadOnlyByteArrayChannel source = new ReadOnlyByteArrayChannel(
                Arrays.copyOfRange(data, HEADER_SIZE, data.length),
                bufferSize
        );
        try (ArkivoFileSystem fileSystem = detectFormat
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
        if (source.isOpen()) {
            throw new AssertionError("Archive file system did not close its owned source");
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

    /// Requires a partially consumed body to have been closed by cursor advancement or reader cleanup.
    private static void requireClosed(@Nullable ReadableByteChannel body) {
        if (body != null && body.isOpen()) {
            throw new AssertionError("Archive streaming reader retained a previous entry body");
        }
    }

    /// Verifies a previously returned metadata snapshot still exposes its original stable values.
    private static void verifyRetainedAttributes(
            @Nullable ArchiveEntryAttributes attributes,
            String expectedPath,
            long expectedSize
    ) {
        if (attributes != null
                && (!Objects.equals(expectedPath, attributes.path()) || expectedSize != attributes.size())) {
            throw new AssertionError("Archive entry attributes changed after cursor advancement");
        }
    }

    /// Supplies raw, singly wrapped, and representative doubly wrapped seeds for every forward-only archive.
    ///
    /// @return deterministic streaming-reader seed arguments
    /// @throws IOException if a seed archive or outer compression layer cannot be encoded
    private static Stream<Arguments> streamingArchiveSeeds() throws IOException {
        List<Arguments> seeds = new ArrayList<>();
        List<byte @Unmodifiable []> archives = new ArrayList<>();
        for (int index = 0; index < FuzzSupport.STREAMING_ARCHIVE_FORMATS.size(); index++) {
            String formatName = FuzzSupport.STREAMING_ARCHIVE_FORMATS.get(index);
            byte @Unmodifiable [] archive = FuzzSupport.createArchiveSeed(formatName, BODY_MODE_COUNT);
            archives.add(archive);
            for (int bodyMode = BODY_MODE_FULL; bodyMode <= BODY_MODE_ALTERNATING; bodyMode++) {
                seeds.add(streamingSeed(index, false, bodyMode, archive));
                seeds.add(streamingSeed(index, true, bodyMode, archive));
            }
            int compressionIndex = 0;
            for (CompressionFormat compressionFormat : FuzzSupport.SIGNED_COMPRESSION_FORMATS) {
                seeds.add(streamingSeed(
                        index,
                        true,
                        compressionIndex++ % BODY_MODE_COUNT,
                        FuzzSupport.compressArchive(compressionFormat.name(), archive)
                ));
            }
        }

        for (int index = 0; index < FuzzSupport.SIGNED_COMPRESSION_FORMATS.size(); index++) {
            int archiveIndex = index % archives.size();
            CompressionFormat inner = FuzzSupport.SIGNED_COMPRESSION_FORMATS.get(index);
            CompressionFormat outer = FuzzSupport.SIGNED_COMPRESSION_FORMATS.get(
                    (index + 1) % FuzzSupport.SIGNED_COMPRESSION_FORMATS.size()
            );
            byte @Unmodifiable [] nested = FuzzSupport.compressArchive(
                    outer.name(),
                    FuzzSupport.compressArchive(inner.name(), archives.get(archiveIndex))
            );
            seeds.add(streamingSeed(
                    archiveIndex,
                    true,
                    index % BODY_MODE_COUNT,
                    nested
            ));
        }
        return seeds.stream();
    }

    /// Supplies raw, singly wrapped, and representative doubly wrapped seeds for every random-access archive.
    ///
    /// @return deterministic file-system seed arguments
    /// @throws IOException if a seed archive cannot be encoded
    private static Stream<Arguments> fileSystemArchiveSeeds() throws IOException {
        List<Arguments> seeds = new ArrayList<>();
        List<byte @Unmodifiable []> archives = new ArrayList<>();
        for (int index = 0; index < FuzzSupport.FILE_SYSTEM_ARCHIVE_FORMATS.size(); index++) {
            byte @Unmodifiable [] archive = FuzzSupport.createArchiveSeed(
                    FuzzSupport.FILE_SYSTEM_ARCHIVE_FORMATS.get(index)
            );
            archives.add(archive);
            seeds.add(seed(index, false, archive));
            seeds.add(seed(index, true, archive));
            for (CompressionFormat compressionFormat : FuzzSupport.SIGNED_COMPRESSION_FORMATS) {
                seeds.add(seed(
                        index,
                        true,
                        FuzzSupport.compressArchive(compressionFormat.name(), archive)
                ));
            }
        }

        for (int index = 0; index < FuzzSupport.SIGNED_COMPRESSION_FORMATS.size(); index++) {
            int archiveIndex = index % archives.size();
            CompressionFormat inner = FuzzSupport.SIGNED_COMPRESSION_FORMATS.get(index);
            CompressionFormat outer = FuzzSupport.SIGNED_COMPRESSION_FORMATS.get(
                    (index + 1) % FuzzSupport.SIGNED_COMPRESSION_FORMATS.size()
            );
            byte @Unmodifiable [] nested = FuzzSupport.compressArchive(
                    outer.name(),
                    FuzzSupport.compressArchive(inner.name(), archives.get(archiveIndex))
            );
            seeds.add(seed(archiveIndex, true, nested));
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

    /// Creates one streaming-reader seed with explicit body-lifecycle behavior.
    ///
    /// @param formatIndex the streaming format-list index
    /// @param detectFormat whether the target should perform format detection
    /// @param bodyMode the body consumption mode encoded in the high two control bits
    /// @param archive the complete archive bytes
    /// @return one JUnit seed argument
    private static Arguments streamingSeed(
            int formatIndex,
            boolean detectFormat,
            int bodyMode,
            byte @Unmodifiable [] archive
    ) {
        if (bodyMode < BODY_MODE_FULL || bodyMode > BODY_MODE_ALTERNATING) {
            throw new IllegalArgumentException("Invalid body mode: " + bodyMode);
        }
        int formatControl = formatIndex | (detectFormat ? 0x80 : 0);
        int bodyControl = (bodyMode << 6) | 31;
        return Arguments.of((Object) FuzzSupport.prefix(
                new byte[]{(byte) formatControl, (byte) bodyControl},
                archive
        ));
    }
}
