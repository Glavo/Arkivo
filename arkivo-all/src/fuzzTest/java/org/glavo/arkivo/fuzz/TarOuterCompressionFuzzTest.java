// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import org.glavo.arkivo.archive.ArchiveEntryAttributes;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArchiveUpdateOptions;
import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoEditStorageFactory;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.archive.ArkivoStreamingReader;
import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.glavo.arkivo.archive.tar.TarArchiveOptions;
import org.glavo.arkivo.archive.tar.TarArkivoFileSystem;
import org.glavo.arkivo.archive.tar.TarArkivoFormat;
import org.glavo.arkivo.archive.tar.TarArkivoStreamingReader;
import org.glavo.arkivo.archive.tar.TarArkivoStreamingWriter;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.CompressionFormats;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
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
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/// Fuzzes TAR creation and reading through every reliably detectable outer compression format.
@NotNullByDefault
public final class TarOuterCompressionFuzzTest {
    /// The control-byte count preceding generated entry content.
    private static final int HEADER_SIZE = 5;

    /// The largest number of regular-file entries generated in one archive.
    private static final int MAXIMUM_ENTRY_COUNT = 4;

    /// Creates a TAR outer-compression fuzz-test instance for JUnit.
    public TarOuterCompressionFuzzTest() {
    }

    /// Creates a compressed TAR and verifies direct and generic read paths against the generated entry model.
    ///
    /// Control bytes select the outer codec, entry count, body chunking, stream versus channel writes, explicit versus
    /// detected direct decoding, the indexed file-system entry point, and a nonzero physical channel origin. Generic
    /// streaming always performs archive and outer-compression detection. The generated archive is valid, so any
    /// encoding, detection, or round-trip failure is a finding.
    ///
    /// @param data state controls followed by arbitrary entry content
    /// @throws IOException if a valid TAR or compression operation unexpectedly fails
    @MethodSource("tarOuterCompressionSeeds")
    @FuzzTest(maxDuration = "1m")
    void fuzzTarOuterCompression(byte @Unmodifiable [] data) throws IOException {
        if (data.length < HEADER_SIZE
                || data.length > HEADER_SIZE + FuzzSupport.MAX_ROUND_TRIP_INPUT_SIZE) {
            return;
        }

        CompressionFormat format = FuzzSupport.SIGNED_COMPRESSION_FORMATS.get(
                Byte.toUnsignedInt(data[0]) % FuzzSupport.SIGNED_COMPRESSION_FORMATS.size()
        );
        String formatName = format.name();
        CompressionCodec<?> codec = format.defaultCodec();
        int entryCount = 1 + Byte.toUnsignedInt(data[1]) % MAXIMUM_ENTRY_COUNT;
        int chunkSize = 1 + (Byte.toUnsignedInt(data[2]) & 0x3f);
        int routeControl = Byte.toUnsignedInt(data[3]);
        @Unmodifiable List<ExpectedEntry> expectedEntries = expectedEntries(data, entryCount);

        byte @Unmodifiable [] archive = createArchive(codec, expectedEntries, chunkSize);
        @Nullable CompressionFormat detected = CompressionFormats.detect(ByteBuffer.wrap(archive));
        if (detected == null || !formatName.equals(detected.name())) {
            throw new AssertionError("Compressed TAR was not detected as " + formatName);
        }

        ArchiveReadOptions commonOptions = FuzzSupport.ARCHIVE_READ_OPTIONS.withEditStorageFactory(
                ArkivoEditStorageFactory.memory()
        );
        TarArchiveOptions.Read tarOptions = TarArchiveOptions.READ_DEFAULTS.withCommon(commonOptions);
        if ((routeControl & 1) != 0) {
            tarOptions = tarOptions.withCompression(codec);
        }

        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(
                new ByteArrayInputStream(archive),
                tarOptions
        )) {
            verifyStreaming(reader, expectedEntries);
        }
        try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(
                new ByteArrayInputStream(archive),
                commonOptions
        )) {
            verifyStreaming(reader, expectedEntries);
        }

        verifyFileSystem(archive, expectedEntries, commonOptions, tarOptions, routeControl);
    }

    /// Rewrites a compressed TAR while preserving, removing, or changing its outer compression.
    ///
    /// Every generated update uses a non-path source and a transactional in-memory commit target. Control bytes select
    /// automatic or explicit source decoding, target compression, entry replacement, move and deletion operations,
    /// direct or generic verification, and nonzero physical source origins. The source and replacement archives are
    /// both valid by construction, so an update, publication, detection, or round-trip failure is a finding.
    ///
    /// @param data update controls followed by arbitrary entry content
    /// @throws IOException if a generated valid update or its verification unexpectedly fails
    @MethodSource("tarOuterCompressionUpdateSeeds")
    @FuzzTest(maxDuration = "1m")
    void fuzzTarOuterCompressionUpdate(byte @Unmodifiable [] data) throws IOException {
        if (data.length < HEADER_SIZE
                || data.length > HEADER_SIZE + FuzzSupport.MAX_ROUND_TRIP_INPUT_SIZE) {
            return;
        }

        CompressionFormat sourceFormat = FuzzSupport.SIGNED_COMPRESSION_FORMATS.get(
                Byte.toUnsignedInt(data[0]) % FuzzSupport.SIGNED_COMPRESSION_FORMATS.size()
        );
        CompressionCodec<?> sourceCodec = sourceFormat.defaultCodec();
        int entryCount = 1 + Byte.toUnsignedInt(data[1]) % MAXIMUM_ENTRY_COUNT;
        int routeControl = Byte.toUnsignedInt(data[3]);
        @Unmodifiable List<ExpectedEntry> initialEntries = expectedEntries(data, entryCount);
        byte @Unmodifiable [] sourceArchive = createArchive(
                sourceCodec,
                initialEntries,
                1 + (Byte.toUnsignedInt(data[2]) & 0x3f)
        );
        Map<String, byte @Unmodifiable []> expectedEntries = new LinkedHashMap<>();
        for (ExpectedEntry entry : initialEntries) {
            expectedEntries.put(entry.path(), entry.content().clone());
        }

        InMemoryCommitTarget commitTarget = new InMemoryCommitTarget();
        ArchiveUpdateOptions commonOptions = ArchiveUpdateOptions.DEFAULT
                .withEditStorageFactory(ArkivoEditStorageFactory.memory())
                .withCommitTarget(commitTarget)
                .withLimits(FuzzSupport.ARCHIVE_READ_OPTIONS.limits());
        TarArchiveOptions.Update updateOptions = TarArchiveOptions.UPDATE_DEFAULTS.withCommon(commonOptions);
        if ((routeControl & 1) != 0) {
            updateOptions = updateOptions.withSourceCompression(sourceCodec);
        }

        @Nullable CompressionFormat expectedTargetFormat;
        int targetMode = Byte.toUnsignedInt(data[4]) % 3;
        if (targetMode == 0) {
            expectedTargetFormat = sourceFormat;
        } else if (targetMode == 1) {
            expectedTargetFormat = null;
            updateOptions = updateOptions.withUncompressedTarget();
        } else {
            expectedTargetFormat = FuzzSupport.SIGNED_COMPRESSION_FORMATS.get(
                    Byte.toUnsignedInt(data[2]) % FuzzSupport.SIGNED_COMPRESSION_FORMATS.size()
            );
            updateOptions = updateOptions.withTargetCompression(expectedTargetFormat.defaultCodec());
        }

        ReadOnlyByteArrayChannel source = embeddedSource(sourceArchive, routeControl >>> 4);
        try (source; TarArkivoFileSystem fileSystem = TarArkivoFileSystem.update(source, updateOptions)) {
            applyUpdate(fileSystem, expectedEntries, data, routeControl);
        }
        if (source.isOpen()) {
            throw new AssertionError("Compressed TAR update did not close its source");
        }

        byte @Unmodifiable [] updatedArchive = commitTarget.bytes();
        verifyUpdatedArchive(
                updatedArchive,
                expectedEntries,
                expectedTargetFormat,
                commonOptions.readOptions(),
                routeControl
        );
    }

    /// Creates the compressed TAR through the public writer and memory-backed entry staging.
    private static byte @Unmodifiable [] createArchive(
            CompressionCodec<?> codec,
            @Unmodifiable List<ExpectedEntry> expectedEntries,
            int chunkSize
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        TarArchiveOptions.Create options = TarArchiveOptions.CREATE_DEFAULTS.withCompression(codec);
        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(
                output,
                ArkivoEditStorage.memory(),
                options
        )) {
            for (ExpectedEntry expected : expectedEntries) {
                ArkivoStreamingWriter.Entry entry = writer.beginFile(expected.path());
                if (expected.useChannel()) {
                    try (WritableByteChannel body = entry.openChannel()) {
                        write(body, expected.content(), chunkSize);
                    }
                } else {
                    try (OutputStream body = entry.openOutputStream()) {
                        write(body, expected.content(), chunkSize);
                    }
                }
            }
        }
        return output.toByteArray();
    }

    /// Derives unique nested entry paths, body bytes, and write transports from one fuzz input.
    private static @Unmodifiable List<ExpectedEntry> expectedEntries(
            byte @Unmodifiable [] data,
            int entryCount
    ) {
        List<ExpectedEntry> result = new ArrayList<>(entryCount);
        int payloadSize = data.length - HEADER_SIZE;
        for (int index = 0; index < entryCount; index++) {
            int start = HEADER_SIZE + payloadSize * index / entryCount;
            int end = HEADER_SIZE + payloadSize * (index + 1) / entryCount;
            int entryControl = payloadSize == 0
                    ? Byte.toUnsignedInt(data[4]) + index
                    : Byte.toUnsignedInt(data[HEADER_SIZE + index % payloadSize]);
            result.add(new ExpectedEntry(
                    "group-" + (entryControl & 3) + "/entry-" + index + ".bin",
                    Arrays.copyOfRange(data, start, end),
                    (entryControl & 4) != 0
            ));
        }
        return List.copyOf(result);
    }

    /// Applies deterministic replacement, optional deletion and move, and file creation to an update file system.
    private static void applyUpdate(
            TarArkivoFileSystem fileSystem,
            Map<String, byte @Unmodifiable []> expectedEntries,
            byte @Unmodifiable [] data,
            int routeControl
    ) throws IOException {
        Path root = fileSystem.getPath("/");
        Files.createDirectories(root.resolve("updated"));

        String primaryPath = expectedEntries.keySet().iterator().next();
        if ((routeControl & 8) != 0 && expectedEntries.size() > 1) {
            @Nullable String deletedPath = null;
            for (String candidate : expectedEntries.keySet()) {
                if (!candidate.equals(primaryPath)) {
                    deletedPath = candidate;
                }
            }
            if (deletedPath == null) {
                throw new AssertionError("Compressed TAR mutation model has no deletable entry");
            }
            Files.delete(root.resolve(deletedPath));
            expectedEntries.remove(deletedPath);
        }

        byte @Unmodifiable [] replacement = mutationContent(data, Byte.toUnsignedInt(data[1]));
        Files.write(
                root.resolve(primaryPath),
                replacement,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
        expectedEntries.put(primaryPath, replacement);

        if ((routeControl & 4) != 0) {
            String movedPath = "updated/primary.bin";
            Files.move(root.resolve(primaryPath), root.resolve(movedPath));
            expectedEntries.remove(primaryPath);
            expectedEntries.put(movedPath, replacement);
        }

        String addedPath = "updated/added-" + (Byte.toUnsignedInt(data[2]) & 0xf) + ".bin";
        byte @Unmodifiable [] added = mutationContent(data, Byte.toUnsignedInt(data[2]) ^ 0xa5);
        Files.write(
                root.resolve(addedPath),
                added,
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE_NEW
        );
        expectedEntries.put(addedPath, added);
    }

    /// Returns nonempty update content derived from the fuzz payload and a control salt.
    private static byte @Unmodifiable [] mutationContent(byte @Unmodifiable [] data, int salt) {
        int payloadSize = data.length - HEADER_SIZE;
        byte[] result = new byte[Math.max(1, payloadSize)];
        for (int index = 0; index < result.length; index++) {
            int value = payloadSize == 0
                    ? salt + index
                    : Byte.toUnsignedInt(data[data.length - 1 - index]);
            result[index] = (byte) (value ^ salt ^ index * 31);
        }
        return result;
    }

    /// Writes a complete body to a channel in bounded chunks.
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
                if (output.write(source) == 0) {
                    throw new AssertionError("TAR entry channel made no write progress");
                }
            }
            offset += count;
        }
    }

    /// Writes a complete body to a stream in bounded chunks.
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

    /// Verifies entry order, metadata, and bodies through a forward-only reader.
    private static void verifyStreaming(
            ArkivoStreamingReader reader,
            @Unmodifiable List<ExpectedEntry> expectedEntries
    ) throws IOException {
        int index = 0;
        while (reader.next()) {
            if (index >= expectedEntries.size()) {
                throw new AssertionError("Compressed TAR emitted an unexpected entry");
            }
            ExpectedEntry expected = expectedEntries.get(index++);
            ArchiveEntryAttributes attributes = reader.readAttributes();
            if (!expected.path().equals(attributes.path())
                    || !attributes.isRegularFile()
                    || attributes.size() != expected.content().length) {
                throw new AssertionError("Compressed TAR changed generated entry metadata");
            }
            try (InputStream body = reader.openInputStream()) {
                if (!Arrays.equals(expected.content(), body.readAllBytes())) {
                    throw new AssertionError("Compressed TAR changed generated entry content");
                }
            }
        }
        if (index != expectedEntries.size()) {
            throw new AssertionError("Compressed TAR omitted generated entries");
        }
    }

    /// Verifies either the direct TAR or generic detected file-system path from a nonzero channel origin.
    private static void verifyFileSystem(
            byte @Unmodifiable [] archive,
            @Unmodifiable List<ExpectedEntry> expectedEntries,
            ArchiveReadOptions commonOptions,
            TarArchiveOptions.Read tarOptions,
            int routeControl
    ) throws IOException {
        ReadOnlyByteArrayChannel source = embeddedSource(archive, routeControl >>> 4);
        try (source;
             ArkivoFileSystem fileSystem = (routeControl & 2) == 0
                     ? TarArkivoFileSystem.open(source, tarOptions)
                     : ArkivoFormats.openFileSystem(source, commonOptions)) {
            for (ExpectedEntry expected : expectedEntries) {
                byte[] actual = Files.readAllBytes(fileSystem.getPath("/" + expected.path()));
                if (!Arrays.equals(expected.content(), actual)) {
                    throw new AssertionError("Compressed TAR file system changed generated entry content");
                }
            }
            try (Stream<Path> paths = Files.walk(fileSystem.getPath("/"))) {
                long actualEntryCount = paths.filter(Files::isRegularFile).count();
                if (actualEntryCount != expectedEntries.size()) {
                    throw new AssertionError("Compressed TAR file system exposed an unexpected entry count");
                }
            }
        }
        if (source.isOpen()) {
            throw new AssertionError("Compressed TAR file system did not close its source");
        }
    }

    /// Verifies target compression and every updated file through a detected archive file system.
    private static void verifyUpdatedArchive(
            byte @Unmodifiable [] archive,
            Map<String, byte @Unmodifiable []> expectedEntries,
            @Nullable CompressionFormat expectedCompression,
            ArchiveReadOptions commonOptions,
            int routeControl
    ) throws IOException {
        @Nullable CompressionFormat detected = CompressionFormats.detect(ByteBuffer.wrap(archive));
        if (expectedCompression == null) {
            if (detected != null || !TarArkivoFormat.instance().matches(ByteBuffer.wrap(archive))) {
                throw new AssertionError("TAR update did not remove outer compression");
            }
        } else if (detected == null || !expectedCompression.name().equals(detected.name())) {
            throw new AssertionError("TAR update produced unexpected outer compression");
        }

        ReadOnlyByteArrayChannel source = embeddedSource(archive, (routeControl >>> 5) + 1);
        try (source;
             ArkivoFileSystem fileSystem = (routeControl & 2) == 0
                     ? TarArkivoFileSystem.open(
                             source,
                             TarArchiveOptions.READ_DEFAULTS.withCommon(commonOptions)
                     )
                     : ArkivoFormats.openFileSystem(source, commonOptions)) {
            for (Map.Entry<String, byte @Unmodifiable []> expected : expectedEntries.entrySet()) {
                byte @Unmodifiable [] actual = Files.readAllBytes(fileSystem.getPath("/" + expected.getKey()));
                if (!Arrays.equals(expected.getValue(), actual)) {
                    throw new AssertionError("Updated compressed TAR changed entry content: " + expected.getKey());
                }
            }
            try (Stream<Path> paths = Files.walk(fileSystem.getPath("/"))) {
                long actualEntryCount = paths.filter(Files::isRegularFile).count();
                if (actualEntryCount != expectedEntries.size()) {
                    throw new AssertionError("Updated compressed TAR exposed an unexpected entry count");
                }
            }
        }
        if (source.isOpen()) {
            throw new AssertionError("Updated compressed TAR file system did not close its source");
        }
    }

    /// Returns a channel whose logical archive begins after bounded unrelated prefix bytes.
    private static ReadOnlyByteArrayChannel embeddedSource(
            byte @Unmodifiable [] archive,
            int originControl
    ) throws IOException {
        int origin = originControl & 7;
        byte[] embedded = new byte[origin + archive.length];
        Arrays.fill(embedded, 0, origin, (byte) 0xa5);
        System.arraycopy(archive, 0, embedded, origin, archive.length);
        ReadOnlyByteArrayChannel source = new ReadOnlyByteArrayChannel(embedded);
        source.position(origin);
        return source;
    }

    /// Supplies automatic and explicit direct-reader routes for every signed compression format.
    ///
    /// @return deterministic TAR outer-compression seed arguments
    private static Stream<Arguments> tarOuterCompressionSeeds() {
        return IntStream.range(0, FuzzSupport.SIGNED_COMPRESSION_FORMATS.size())
                .boxed()
                .flatMap(index -> Stream.of(
                        Arguments.of((Object) FuzzSupport.prefix(
                                new byte[]{index.byteValue(), 3, 7, 0x20, 0},
                                FuzzSupport.SEED_CONTENT
                        )),
                        Arguments.of((Object) FuzzSupport.prefix(
                                new byte[]{index.byteValue(), 2, 11, 0x13, 4},
                                FuzzSupport.SEED_CONTENT
                        ))
                ));
    }

    /// Supplies compression preservation, removal, and transcoding updates for every signed source format.
    ///
    /// @return deterministic TAR outer-compression update seed arguments
    private static Stream<Arguments> tarOuterCompressionUpdateSeeds() {
        return IntStream.range(0, FuzzSupport.SIGNED_COMPRESSION_FORMATS.size())
                .boxed()
                .flatMap(index -> Stream.of(
                        Arguments.of((Object) FuzzSupport.prefix(
                                new byte[]{index.byteValue(), 3, 7, 0x20, 0},
                                FuzzSupport.SEED_CONTENT
                        )),
                        Arguments.of((Object) FuzzSupport.prefix(
                                new byte[]{index.byteValue(), 2, 11, 0x33, 1},
                                FuzzSupport.SEED_CONTENT
                        )),
                        Arguments.of((Object) FuzzSupport.prefix(
                                new byte[]{index.byteValue(), 4, (byte) (index + 1), 0x5d, 2},
                                FuzzSupport.SEED_CONTENT
                        ))
                ));
    }

    /// Describes one generated regular-file entry and its body transport.
    ///
    /// @param path the unique archive-local path
    /// @param content the expected body bytes
    /// @param useChannel whether the writer body uses a channel rather than a stream
    @NotNullByDefault
    private record ExpectedEntry(
            String path,
            byte @Unmodifiable [] content,
            boolean useChannel
    ) {
    }
}
