// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import org.glavo.arkivo.archive.ArchiveEntryAttributes;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.archive.ArkivoStreamingReader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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

/// Fuzzes split-volume archive parsing, discovery, traversal, and resource ownership.
@NotNullByDefault
public final class ArchiveVolumeFuzzTest {
    /// Formats that expose public multi-volume parsing.
    private static final @Unmodifiable List<String> FORMATS = List.of("7z", "rar", "zip");

    /// The fixed control bytes preceding optional volume-boundary lengths.
    private static final int FIXED_HEADER_SIZE = 2;

    /// The maximum physical volume count represented by one fuzz input.
    private static final int MAXIMUM_VOLUME_COUNT = 4;

    /// The independent traversal bound applied after public read limits.
    private static final int MAXIMUM_VISITED_PATHS = 64;

    /// Creates a multi-volume fuzz-test instance for JUnit.
    public ArchiveVolumeFuzzTest() {
    }

    /// Verifies that every generated split seed reaches all supported named and detected access paths without rejection.
    @Test
    void generatedSeedsReachEverySupportedAccessPath() throws IOException {
        for (String formatName : FORMATS) {
            List<byte[]> volumes = switch (formatName) {
                case "7z", "zip" -> FuzzSupport.createSplitArchiveSeed(formatName, 64L * 1024L);
                case "rar" -> FuzzSupport.createSplitRARSeed();
                default -> throw new AssertionError("Unexpected multi-volume format: " + formatName);
            };
            verifyValidSeed(formatName, volumes, false, false);
            verifyValidSeed(formatName, volumes, true, false);
            if (!"7z".equals(formatName)) {
                verifyValidSeed(formatName, volumes, false, true);
                verifyValidSeed(formatName, volumes, true, true);
            }
        }
    }

    /// Parses arbitrary physical-volume boundaries through named or detected streaming and indexed readers.
    ///
    /// Checked rejection is expected for malformed volumes. Every returned physical channel and the owned volume source
    /// must still be closed on successful traversal and checked failure.
    ///
    /// @param data access controls, little-endian volume lengths, and arbitrary physical volume bytes
    @MethodSource("archiveVolumeSeeds")
    @FuzzTest(maxDuration = "1m")
    void fuzzArchiveVolumes(byte @Unmodifiable [] data) {
        if (data.length < FIXED_HEADER_SIZE
                || data.length > FIXED_HEADER_SIZE
                + (MAXIMUM_VOLUME_COUNT - 1) * Integer.BYTES
                + FuzzSupport.MAX_PARSER_INPUT_SIZE) {
            return;
        }

        int control = Byte.toUnsignedInt(data[0]);
        String formatName = FORMATS.get((control & 0x3f) % FORMATS.size());
        boolean detectFormat = (control & 0x40) != 0;
        boolean streaming = (control & 0x80) != 0;
        int volumeCount = 1 + Byte.toUnsignedInt(data[1]) % MAXIMUM_VOLUME_COUNT;
        List<byte[]> volumes = decodeVolumes(data, volumeCount);
        if (volumes.isEmpty()) {
            return;
        }

        TrackingVolumeSource source = new TrackingVolumeSource(volumes);
        try {
            if (streaming) {
                inspectStreaming(source, formatName, detectFormat);
            } else {
                inspectFileSystem(source, formatName, detectFormat);
            }
        } catch (DirectoryIteratorException expectedLazyFailure) {
            if (expectedLazyFailure.getCause() == null) {
                throw expectedLazyFailure;
            }
        } catch (IOException | UnsupportedOperationException expectedMalformedArchive) {
            // Malformed data, absent volumes, configured limits, and unsupported access paths are normal outcomes.
        } finally {
            if (!source.isClosed()) {
                throw new AssertionError("Archive consumer did not close its owned volume source");
            }
            if (!source.allOpenedChannelsClosed()) {
                throw new AssertionError("Archive consumer leaked a physical volume channel");
            }
        }
    }

    /// Decodes length-prefixed volume boundaries while assigning all remaining bytes to the final volume.
    private static List<byte[]> decodeVolumes(byte @Unmodifiable [] data, int volumeCount) {
        int headerSize = FIXED_HEADER_SIZE + (volumeCount - 1) * Integer.BYTES;
        if (data.length < headerSize) {
            return List.of();
        }

        ByteBuffer input = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        input.position(FIXED_HEADER_SIZE);
        int[] lengths = new int[volumeCount - 1];
        long declaredSize = 0L;
        for (int index = 0; index < lengths.length; index++) {
            int length = input.getInt();
            if (length < 0) {
                return List.of();
            }
            lengths[index] = length;
            declaredSize += length;
        }
        if (declaredSize > input.remaining()) {
            return List.of();
        }

        List<byte[]> volumes = new ArrayList<>(volumeCount);
        for (int length : lengths) {
            byte[] volume = new byte[length];
            input.get(volume);
            volumes.add(volume);
        }
        byte[] finalVolume = new byte[input.remaining()];
        input.get(finalVolume);
        volumes.add(finalVolume);
        return volumes;
    }

    /// Verifies one generated seed and the ownership cleanup of its selected access path.
    private static void verifyValidSeed(
            String formatName,
            List<byte[]> volumes,
            boolean detectFormat,
            boolean streaming
    ) throws IOException {
        TrackingVolumeSource source = new TrackingVolumeSource(volumes);
        if (streaming) {
            inspectStreaming(source, formatName, detectFormat);
        } else {
            inspectFileSystem(source, formatName, detectFormat);
        }
        if (!source.isClosed() || !source.allOpenedChannelsClosed()) {
            throw new AssertionError("Valid split seed did not release owned volume resources");
        }
    }

    /// Exercises one multi-volume forward-only reader and every regular-file body it exposes.
    private static void inspectStreaming(
            TrackingVolumeSource source,
            String formatName,
            boolean detectFormat
    ) throws IOException {
        try (ArkivoStreamingReader reader = detectFormat
                ? ArkivoFormats.openStreamingReader(source, FuzzSupport.ARCHIVE_READ_OPTIONS)
                : ArkivoFormats.openStreamingReader(formatName, source, FuzzSupport.ARCHIVE_READ_OPTIONS)) {
            int visited = 0;
            while (reader.next()) {
                if (++visited > MAXIMUM_VISITED_PATHS) {
                    throw new AssertionError("Multi-volume reader exceeded its entry limit");
                }
                ArchiveEntryAttributes attributes = reader.readAttributes();
                if (attributes.isRegularFile()) {
                    try (InputStream body = reader.openInputStream()) {
                        drain(body);
                    }
                }
            }
        }
    }

    /// Exercises one multi-volume file system, including lazy metadata and body reads.
    private static void inspectFileSystem(
            TrackingVolumeSource source,
            String formatName,
            boolean detectFormat
    ) throws IOException {
        try (ArkivoFileSystem fileSystem = detectFormat
                ? ArkivoFormats.openFileSystem(source, FuzzSupport.ARCHIVE_READ_OPTIONS)
                : ArkivoFormats.openFileSystem(formatName, source, FuzzSupport.ARCHIVE_READ_OPTIONS)) {
            Deque<Path> pending = new ArrayDeque<>();
            pending.add(fileSystem.getPath("/"));
            int visited = 0;
            while (!pending.isEmpty()) {
                Path path = pending.removeFirst();
                if (++visited > MAXIMUM_VISITED_PATHS) {
                    throw new AssertionError("Multi-volume file system exceeded its path limit");
                }
                BasicFileAttributes attributes = Files.readAttributes(
                        path,
                        BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS
                );
                if (attributes.isDirectory()) {
                    try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                        for (Path child : children) {
                            pending.addLast(child);
                        }
                    }
                } else if (attributes.isRegularFile()) {
                    try (InputStream body = Files.newInputStream(path)) {
                        drain(body);
                    }
                }
            }
        }
    }

    /// Drains one bounded entry body while rejecting zero-progress reads.
    private static void drain(InputStream input) throws IOException {
        byte[] buffer = new byte[257];
        int total = 0;
        while (true) {
            int count = input.read(buffer);
            if (count < 0) {
                return;
            }
            if (count == 0) {
                throw new AssertionError("Multi-volume entry stream made no progress");
            }
            total = Math.addExact(total, count);
            if (total > FuzzSupport.MAX_DECODED_OUTPUT_SIZE) {
                throw new AssertionError("Multi-volume decoded-output limit was not enforced");
            }
        }
    }

    /// Supplies valid split 7z, RAR4, and ZIP inputs for both detection and supported access paths.
    ///
    /// @return deterministic multi-volume seed arguments
    /// @throws IOException if a split seed cannot be encoded
    private static Stream<Arguments> archiveVolumeSeeds() throws IOException {
        List<Arguments> seeds = new ArrayList<>();
        for (int index = 0; index < FORMATS.size(); index++) {
            String formatName = FORMATS.get(index);
            List<byte[]> volumes = switch (formatName) {
                case "7z", "zip" -> FuzzSupport.createSplitArchiveSeed(formatName, 64L * 1024L);
                case "rar" -> FuzzSupport.createSplitRARSeed();
                default -> throw new AssertionError("Unexpected multi-volume format: " + formatName);
            };
            seeds.add(seed(index, false, false, volumes));
            seeds.add(seed(index, true, false, volumes));
            if (!"7z".equals(formatName)) {
                seeds.add(seed(index, false, true, volumes));
                seeds.add(seed(index, true, true, volumes));
            }
        }
        return seeds.stream();
    }

    /// Encodes one valid multi-volume seed with explicit physical boundaries.
    private static Arguments seed(
            int formatIndex,
            boolean detectFormat,
            boolean streaming,
            List<byte[]> volumes
    ) throws IOException {
        if (volumes.isEmpty() || volumes.size() > MAXIMUM_VOLUME_COUNT) {
            throw new IOException("Unsupported seed volume count: " + volumes.size());
        }
        int payloadSize = 0;
        for (byte[] volume : volumes) {
            payloadSize = Math.addExact(payloadSize, volume.length);
        }
        ByteBuffer seed = ByteBuffer.allocate(
                FIXED_HEADER_SIZE + (volumes.size() - 1) * Integer.BYTES + payloadSize
        ).order(ByteOrder.LITTLE_ENDIAN);
        int control = formatIndex | (detectFormat ? 0x40 : 0) | (streaming ? 0x80 : 0);
        seed.put((byte) control);
        seed.put((byte) (volumes.size() - 1));
        for (int index = 0; index + 1 < volumes.size(); index++) {
            seed.putInt(volumes.get(index).length);
        }
        for (byte[] volume : volumes) {
            seed.put(volume);
        }
        return Arguments.of((Object) seed.array());
    }
}
