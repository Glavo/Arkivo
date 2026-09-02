// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/// Fuzzes writable archive file systems through creation, NIO mutation, volume update, and reopen validation.
@NotNullByDefault
public final class ArchiveFileSystemMutationFuzzTest {
    /// Writable archive formats that expose transactional in-memory volume endpoints.
    private static final @Unmodifiable List<String> FORMATS = List.of("7z", "zip");

    /// The control bytes preceding generated file content.
    private static final int HEADER_SIZE = 6;

    /// The smallest split size accepted by the ZIP implementation.
    private static final long SPLIT_SIZE = 64L * 1024L;

    /// Timestamp used to exercise mutable metadata during complete-rewrite updates.
    private static final FileTime MUTATED_TIME = FileTime.fromMillis(1_700_000_000_000L);

    /// Creates an archive-file-system mutation fuzz-test instance for JUnit.
    public ArchiveFileSystemMutationFuzzTest() {
    }

    /// Applies a valid generated mutation program and verifies both the initial and updated committed archives.
    ///
    /// The generated program covers directory creation, stream and channel writes, copy, move, positional overwrite,
    /// append, truncate, deletion, metadata updates, named and detected volume updates, and resource ownership. Every
    /// operation is valid by construction, so an I/O or runtime failure is a fuzz finding.
    ///
    /// @param data format and mutation controls followed by arbitrary file content
    /// @throws IOException if a generated valid operation or archive publication fails
    @MethodSource("mutationSeeds")
    @FuzzTest(maxDuration = "1m")
    void fuzzArchiveFileSystemMutations(byte @Unmodifiable [] data) throws IOException {
        if (data.length < HEADER_SIZE
                || data.length > HEADER_SIZE + FuzzSupport.MAX_ROUND_TRIP_INPUT_SIZE) {
            return;
        }

        String formatName = FORMATS.get(Byte.toUnsignedInt(data[0]) % FORMATS.size());
        int chunkSize = 1 + (Byte.toUnsignedInt(data[3]) & 0x1f);
        byte[] payload = Arrays.copyOfRange(data, HEADER_SIZE, data.length);

        InMemoryVolumeTarget createTarget = new InMemoryVolumeTarget();
        MutationModel model;
        try (ArkivoFileSystem fileSystem = ArkivoFormats.createFileSystem(formatName, createTarget, SPLIT_SIZE)) {
            model = createInitialState(fileSystem, payload, data, chunkSize);
        }

        List<byte[]> createdVolumes = createTarget.volumes();
        verifyArchive(formatName, createdVolumes, model.files(), (data[4] & 0x02) != 0);

        TrackingVolumeSource updateSource = new TrackingVolumeSource(createdVolumes);
        InMemoryVolumeTarget updateTarget = new InMemoryVolumeTarget();
        try {
            try (ArkivoFileSystem fileSystem = (data[4] & 0x01) == 0
                    ? ArkivoFormats.updateFileSystem(formatName, updateSource, updateTarget, SPLIT_SIZE)
                    : ArkivoFormats.updateFileSystem(updateSource, updateTarget, SPLIT_SIZE)) {
                applyUpdate(fileSystem, model, payload, data, chunkSize);
            }
        } finally {
            assertReleased(updateSource, "update source");
        }

        verifyArchive(formatName, updateTarget.volumes(), model.files(), (data[4] & 0x04) != 0);
    }

    /// Creates the initial directory tree and returns its expected committed file model.
    private static MutationModel createInitialState(
            ArkivoFileSystem fileSystem,
            byte @Unmodifiable [] payload,
            byte @Unmodifiable [] controls,
            int chunkSize
    ) throws IOException {
        Path root = fileSystem.getPath("/");
        Files.createDirectories(root.resolve("alpha/nested"));
        Files.createDirectories(root.resolve("beta"));

        int split = payload.length / 2;
        byte[] primaryContent = Arrays.copyOfRange(payload, 0, split);
        byte[] secondaryContent = Arrays.copyOfRange(payload, split, payload.length);
        writeFile(root.resolve("alpha/original.bin"), primaryContent, chunkSize, (controls[5] & 0x01) != 0);
        writeFile(root.resolve("alpha/nested/secondary.bin"), secondaryContent, chunkSize, (controls[5] & 0x02) != 0);
        writeFile(root.resolve("beta/empty.bin"), new byte[0], chunkSize, (controls[5] & 0x04) != 0);
        Files.copy(new ByteArrayInputStream(secondaryContent), root.resolve("beta/mirror.bin"));

        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("alpha/original.bin", primaryContent);
        files.put("alpha/nested/secondary.bin", secondaryContent);
        files.put("beta/mirror.bin", secondaryContent.clone());
        files.put("beta/empty.bin", new byte[0]);
        return new MutationModel(files, "alpha/original.bin");
    }

    /// Applies a second valid mutation phase to an archive opened through its volume update API.
    private static void applyUpdate(
            ArkivoFileSystem fileSystem,
            MutationModel model,
            byte @Unmodifiable [] payload,
            byte @Unmodifiable [] controls,
            int chunkSize
    ) throws IOException {
        Path root = fileSystem.getPath("/");
        Files.createDirectories(root.resolve("final"));

        byte[] primaryContent = Objects.requireNonNull(
                model.files().remove(model.primaryPath()),
                "Mutation model lost its primary file"
        );
        String finalPath = "final/result.bin";
        Files.move(root.resolve(model.primaryPath()), root.resolve(finalPath));
        byte[] patch = transformedPayload(payload, Byte.toUnsignedInt(controls[2]));
        primaryContent = patchFile(
                root.resolve(finalPath),
                primaryContent,
                patch,
                Byte.toUnsignedInt(controls[1]),
                chunkSize
        );
        model.files().put(finalPath, primaryContent);

        byte[] addedContent = transformedPayload(payload, Byte.toUnsignedInt(controls[2]) ^ 0xa5);
        String addedPath = (controls[1] & 0x02) == 0 ? "final/added.bin" : "final/unicode-\u00e9.bin";
        writeFile(root.resolve(addedPath), addedContent, chunkSize, (controls[5] & 0x08) != 0);
        model.files().put(addedPath, addedContent);

        String secondaryPath = "alpha/nested/secondary.bin";
        byte[] secondaryContent = Objects.requireNonNull(
                model.files().get(secondaryPath),
                "Mutation model lost its secondary file"
        );
        Files.copy(root.resolve(secondaryPath), root.resolve("final/secondary-copy.bin"));
        model.files().put("final/secondary-copy.bin", secondaryContent.clone());
        if ((controls[1] & 0x10) != 0) {
            Files.delete(root.resolve(secondaryPath));
            model.files().remove(secondaryPath);
        }

        byte[] replacement = transformedPayload(payload, Byte.toUnsignedInt(controls[4]) ^ 0x5a);
        writeFile(root.resolve("beta/mirror.bin"), replacement, chunkSize, (controls[5] & 0x10) != 0);
        model.files().put("beta/mirror.bin", replacement);
        if (model.files().remove("beta/empty.bin") != null) {
            Files.delete(root.resolve("beta/empty.bin"));
        }

        Files.setLastModifiedTime(root.resolve(finalPath), MUTATED_TIME);
        if (!MUTATED_TIME.equals(Files.getLastModifiedTime(root.resolve(finalPath)))) {
            throw new AssertionError("Updated archive file system did not retain active metadata changes");
        }
    }

    /// Writes a complete new file through either an output stream or a seekable channel in bounded chunks.
    private static void writeFile(
            Path path,
            byte @Unmodifiable [] content,
            int chunkSize,
            boolean useChannel
    ) throws IOException {
        if (useChannel) {
            try (SeekableByteChannel output = Files.newByteChannel(
                    path,
                    Set.of(
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE
                    )
            )) {
                write(output, content, chunkSize);
            }
        } else {
            try (OutputStream output = Files.newOutputStream(
                    path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            )) {
                int offset = 0;
                while (offset < content.length) {
                    int count = Math.min(chunkSize, content.length - offset);
                    output.write(content, offset, count);
                    offset += count;
                }
            }
        }
    }

    /// Applies positional writing and optional truncation while returning the resulting expected bytes.
    private static byte @Unmodifiable [] patchFile(
            Path path,
            byte @Unmodifiable [] original,
            byte @Unmodifiable [] patch,
            int control,
            int chunkSize
    ) throws IOException {
        int position = (control & 0x02) == 0
                ? original.length
                : Math.floorMod(control >>> 2, original.length + 1);
        byte[] result = Arrays.copyOf(original, Math.max(original.length, position + patch.length));
        System.arraycopy(patch, 0, result, position, patch.length);
        try (SeekableByteChannel channel = Files.newByteChannel(path, Set.of(StandardOpenOption.WRITE))) {
            channel.position(position);
            write(channel, patch, chunkSize);
            if ((control & 0x04) != 0) {
                int truncatedSize = Math.floorMod(control * 31, result.length + 1);
                channel.truncate(truncatedSize);
                result = Arrays.copyOf(result, truncatedSize);
            }
        }
        return result;
    }

    /// Writes all bytes to a channel while rejecting zero-progress implementations.
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
                    throw new AssertionError("Writable archive channel made no progress");
                }
            }
            offset += count;
        }
    }

    /// Returns nonempty deterministic mutation bytes derived from arbitrary payload content.
    private static byte @Unmodifiable [] transformedPayload(byte @Unmodifiable [] payload, int salt) {
        int length = Math.max(1, Math.min(payload.length, 64));
        byte[] transformed = new byte[length];
        for (int index = 0; index < length; index++) {
            int value = payload.length == 0 ? index : Byte.toUnsignedInt(payload[(index + salt) % payload.length]);
            transformed[index] = (byte) (value ^ salt ^ index * 17);
        }
        return transformed;
    }

    /// Reopens one committed archive and compares every regular file with the expected model.
    private static void verifyArchive(
            String formatName,
            List<byte[]> volumes,
            Map<String, byte[]> expected,
            boolean detectFormat
    ) throws IOException {
        TrackingVolumeSource source = new TrackingVolumeSource(volumes);
        try {
            try (ArkivoFileSystem fileSystem = detectFormat
                    ? ArkivoFormats.openFileSystem(source, FuzzSupport.ARCHIVE_READ_OPTIONS)
                    : ArkivoFormats.openFileSystem(formatName, source, FuzzSupport.ARCHIVE_READ_OPTIONS)) {
                Path root = fileSystem.getPath("/");
                for (Map.Entry<String, byte[]> entry : expected.entrySet()) {
                    byte[] actual = Files.readAllBytes(root.resolve(entry.getKey()));
                    if (!Arrays.equals(entry.getValue(), actual)) {
                        throw new AssertionError("Committed archive changed file content: " + entry.getKey());
                    }
                }

                Set<String> actualPaths = new LinkedHashSet<>();
                try (Stream<Path> paths = Files.walk(root)) {
                    for (Path path : paths.toList()) {
                        if (Files.isRegularFile(path)) {
                            actualPaths.add(root.relativize(path).toString().replace('\\', '/'));
                        }
                    }
                }
                if (!actualPaths.equals(expected.keySet())) {
                    throw new AssertionError(
                            "Committed archive file set differs from its mutation model: expected="
                                    + expected.keySet() + ", actual=" + actualPaths
                    );
                }
            }
        } finally {
            assertReleased(source, "verification source");
        }
    }

    /// Verifies that an owning archive operation closed its volume source and every opened physical channel.
    private static void assertReleased(TrackingVolumeSource source, String context) {
        if (!source.isClosed()) {
            throw new AssertionError("Archive operation did not close its " + context);
        }
        if (!source.allOpenedChannelsClosed()) {
            throw new AssertionError("Archive operation leaked a channel from its " + context);
        }
    }

    /// Supplies two distinct valid mutation programs for each writable volume format.
    ///
    /// @return deterministic archive mutation seeds
    private static Stream<Arguments> mutationSeeds() {
        return IntStream.range(0, FORMATS.size())
                .boxed()
                .flatMap(formatIndex -> Stream.of(
                        Arguments.of((Object) FuzzSupport.prefix(
                                new byte[]{formatIndex.byteValue(), 0, 7, 5, 0, 0x15},
                                FuzzSupport.SEED_CONTENT
                        )),
                        Arguments.of((Object) FuzzSupport.prefix(
                                new byte[]{formatIndex.byteValue(), 0x1f, (byte) 0xa5, 17, 7, 0x0a},
                                FuzzSupport.SEED_CONTENT
                        ))
                ));
    }

    /// Stores the mutable expected file set and the path selected for the primary file.
    ///
    /// @param files mutable expected regular-file contents keyed by archive-relative path
    /// @param primaryPath the current path of the primary file before the update phase
    @NotNullByDefault
    private record MutationModel(Map<String, byte[]> files, String primaryPath) {
    }
}
