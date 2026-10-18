// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.archive.ArkivoVolumeOutput;
import org.glavo.arkivo.archive.ArkivoPathVolumeTarget;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies 7z-specific path mapping around the shared staged volume transaction.
@NotNullByDefault
final class SevenZipVolumePathLayoutTest {
    /// Temporary output directory for each test invocation.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies traversal components and suffix width are preserved after first-volume validation.
    @Test
    void preservesAndValidatesConfiguration() {
        Path first = temporaryDirectory.resolve("nested").resolve("..").resolve("sample.7z.001");
        SevenZipVolumePathLayout layout = new SevenZipVolumePathLayout(first);

        assertEquals(first.toAbsolutePath(), layout.firstVolumePath());
        assertEquals(first.toAbsolutePath().getParent(), layout.outputDirectory());
        assertEquals(first.toAbsolutePath(), layout.volumePath(0L, 1L));
        assertEquals(first.toAbsolutePath().resolveSibling("sample.7z.002"), layout.volumePath(1L, 1L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SevenZipVolumePathLayout(temporaryDirectory.resolve("sample.7z"))
        );
    }

    /// Verifies established suffix width is preserved and stale higher-numbered volumes are removed.
    @Test
    void publishesVolumesWithEstablishedWidthAndRemovesStaleOutput() throws IOException {
        Path first = temporaryDirectory.resolve("sample.7z.0001");
        Path second = temporaryDirectory.resolve("sample.7z.0002");
        Path third = temporaryDirectory.resolve("sample.7z.0003");
        Files.write(first, bytes("old-first"));
        Files.write(second, bytes("old-second"));
        Files.write(third, bytes("old-third"));

        ArkivoPathVolumeTarget target = replacingTarget(first);
        try (ArkivoVolumeOutput output = target.openOutput()) {
            writeVolume(output, 0L, "new-first");
            writeVolume(output, 1L, "new-second");
            output.commit(1L);
        }

        assertArrayEquals(bytes("new-first"), Files.readAllBytes(first));
        assertArrayEquals(bytes("new-second"), Files.readAllBytes(second));
        assertFalse(Files.exists(third));
        assertNoStagingDirectories();
    }

    /// Rejects indexes outside the requested sequence or the representable volume-number range.
    @ParameterizedTest
    @CsvSource({"-1, 0", "0, -1", "2, 1", "2147483647, 2147483647"})
    void rejectsInvalidVolumeIndex(long index, long finalVolumeIndex) {
        SevenZipVolumePathLayout layout = new SevenZipVolumePathLayout(
                temporaryDirectory.resolve("sample.7z.001"));
        assertThrows(IllegalArgumentException.class, () -> layout.volumePath(index, finalVolumeIndex));
    }

    /// Verifies Unicode digit lookalikes are neither publication conflicts nor owned stale volumes.
    @Test
    void preservesNonAsciiDigitLookalikes() throws IOException {
        Path first = temporaryDirectory.resolve("sample.7z.001");
        Path lookalike = temporaryDirectory.resolve("sample.7z.００２");
        Files.write(lookalike, bytes("unrelated"));
        ArkivoPathVolumeTarget target = new ArkivoPathVolumeTarget(
                new SevenZipVolumePathLayout(first),
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        );

        target.validate();
        try (ArkivoVolumeOutput output = target.openOutput()) {
            writeVolume(output, 0L, "archive");
            output.commit(0L);
        }

        assertArrayEquals(bytes("archive"), Files.readAllBytes(first));
        assertArrayEquals(bytes("unrelated"), Files.readAllBytes(lookalike));
        assertNoStagingDirectories();
    }

    /// Verifies create-new validation recognizes an existing higher-numbered ASCII volume.
    @Test
    void createNewRejectsExistingHigherNumberedVolume() throws IOException {
        Path first = temporaryDirectory.resolve("sample.7z.001");
        Path second = temporaryDirectory.resolve("sample.7z.002");
        Files.write(second, bytes("existing"));
        ArkivoPathVolumeTarget target = new ArkivoPathVolumeTarget(
                new SevenZipVolumePathLayout(first),
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        );

        assertThrows(FileAlreadyExistsException.class, target::validate);
        assertArrayEquals(bytes("existing"), Files.readAllBytes(second));
        assertFalse(Files.exists(first));
        assertNoStagingDirectories();
    }

    /// Creates a target that replaces an existing numbered volume sequence.
    private static ArkivoPathVolumeTarget replacingTarget(Path firstVolumePath) {
        return new ArkivoPathVolumeTarget(
                new SevenZipVolumePathLayout(firstVolumePath),
                Set.of(
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                )
        );
    }

    /// Writes and closes one complete staged volume.
    private static void writeVolume(ArkivoVolumeOutput output, long index, String value) throws IOException {
        try (WritableByteChannel channel = output.openVolume(index)) {
            ByteBuffer source = ByteBuffer.wrap(bytes(value));
            while (source.hasRemaining()) {
                channel.write(source);
            }
        }
    }

    /// Encodes one test value as UTF-8 bytes.
    private static byte @Unmodifiable [] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /// Verifies no shared volume transaction staging directory remains.
    private void assertNoStagingDirectories() throws IOException {
        try (Stream<Path> paths = Files.list(temporaryDirectory)) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().startsWith(".arkivo-volumes-")));
        }
    }
}
