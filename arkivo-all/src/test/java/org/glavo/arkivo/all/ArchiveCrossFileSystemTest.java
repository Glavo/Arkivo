// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
// Scenarios: OpenJDK zipfs CopyMoveTests and ChannelTests (JDK-8231766, JDK-8242006),
// and JimfsUnixLikeFileSystemTest.testCopy_toDifferentFileSystem.

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArkivoFormats;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Checks transfers between independent archive sessions and between archive and native paths.
@NotNullByDefault
final class ArchiveCrossFileSystemTest {
    /// Crosses all writable providers, including distinct sessions using the same provider.
    private static Stream<Arguments> transfers() {
        return Stream.of("zip", "tar", "ar", "7z").flatMap(source ->
                Stream.of("zip", "tar", "ar", "7z").flatMap(target ->
                        Stream.of(false, true).map(move -> Arguments.of(source, target, move))));
    }

    /// Checks accepted transfers and the documented restrictions on archive-native moves and timestamp updates.
    @ParameterizedTest(name = "{0} -> {1}, move={2}")
    @MethodSource("transfers")
    void transfersBetweenArchives(String sourceFormat, String targetFormat, boolean move, @TempDir Path directory)
            throws IOException {
        Path sourceArchive = directory.resolve("source." + sourceFormat);
        Path targetArchive = directory.resolve("target." + targetFormat);
        byte[] content = content();
        boolean nativeMoveRejected = move && sourceFormat.equals(targetFormat);
        boolean attributesRejected = move && !nativeMoveRejected
                && (targetFormat.equals("ar") || targetFormat.equals("zip"));
        boolean sourceRetained = !move || nativeMoveRejected || attributesRejected;
        try (var fs = ArkivoFormats.createFileSystem(sourceFormat, sourceArchive)) {
            Files.write(fs.getPath("entry"), content);
            Files.writeString(fs.getPath("untouched"), "source");
        }
        try (var fs = ArkivoFormats.createFileSystem(targetFormat, targetArchive)) {
            Files.writeString(fs.getPath("entry"), "previous");
            Files.writeString(fs.getPath("untouched"), "target");
        }
        try (var source = ArkivoFormats.updateFileSystem(sourceFormat, sourceArchive);
             var target = ArkivoFormats.updateFileSystem(targetFormat, targetArchive)) {
            Path from = source.getPath("entry");
            Path to = target.getPath("entry");
            Class<? extends Exception> rejection = nativeMoveRejected
                    ? ProviderMismatchException.class : FileAlreadyExistsException.class;
            assertThrows(rejection, () -> {
                if (move) Files.move(from, to);
                else Files.copy(from, to);
            });
            assertArrayEquals(content, Files.readAllBytes(from));
            assertEquals("previous", Files.readString(to));
            if (nativeMoveRejected) {
                assertThrows(ProviderMismatchException.class,
                        () -> Files.move(from, to, StandardCopyOption.REPLACE_EXISTING));
                assertEquals("previous", Files.readString(to));
            } else if (attributesRejected) {
                // The JDK foreign-provider move copies all basic times. These formats cannot persist all of them.
                assertThrows(UnsupportedOperationException.class,
                        () -> Files.move(from, to, StandardCopyOption.REPLACE_EXISTING));
                assertFalse(Files.exists(to));
            } else {
                if (move) Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
                else Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
                assertArrayEquals(content, Files.readAllBytes(to));
            }
            assertEquals(sourceRetained, Files.exists(from));
            if (sourceRetained) assertArrayEquals(content, Files.readAllBytes(from));
            assertEquals("source", Files.readString(source.getPath("untouched")));
            assertEquals("target", Files.readString(target.getPath("untouched")));
        }
        try (var source = ArkivoFormats.openFileSystem(sourceFormat, sourceArchive);
             var target = ArkivoFormats.openFileSystem(targetFormat, targetArchive)) {
            assertEquals(sourceRetained, Files.exists(source.getPath("entry")));
            if (sourceRetained) assertArrayEquals(content, Files.readAllBytes(source.getPath("entry")));
            if (nativeMoveRejected) assertEquals("previous", Files.readString(target.getPath("entry")));
            else if (attributesRejected) assertFalse(Files.exists(target.getPath("entry")));
            else assertArrayEquals(content, Files.readAllBytes(target.getPath("entry")));
            assertEquals("source", Files.readString(source.getPath("untouched")));
            assertEquals("target", Files.readString(target.getPath("untouched")));
        }
    }

    /// Copies through seekable channels, then moves the committed entry back to a native file.
    @ParameterizedTest
    @ValueSource(strings = {"zip", "tar", "ar", "7z"})
    void transfersThroughNativeChannels(String format, @TempDir Path directory) throws IOException {
        Path archive = directory.resolve("channels." + format);
        byte[] content = content();
        Path nativeSource = Files.write(directory.resolve("source.bin"), content);
        try (var fs = ArkivoFormats.createFileSystem(format, archive)) {
            for (boolean direct : new boolean[]{false, true}) {
                Path entry = fs.getPath(direct ? "direct" : "heap");
                try (var input = Files.newByteChannel(nativeSource);
                     var output = Files.newByteChannel(entry, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    ByteBuffer buffer = direct ? ByteBuffer.allocateDirect(137) : ByteBuffer.allocate(137);
                    while (input.read(buffer) != -1) {
                        buffer.flip();
                        while (buffer.hasRemaining()) assertTrue(output.write(buffer) > 0);
                        buffer.clear();
                    }
                    assertEquals(content.length, output.position());
                    assertEquals(content.length, output.size());
                }
            }
        }
        Path extracted = directory.resolve("extracted.bin");
        Path moved = directory.resolve("moved.bin");
        try (var fs = ArkivoFormats.updateFileSystem(format, archive)) {
            Files.copy(fs.getPath("heap"), extracted);
            Files.move(fs.getPath("direct"), moved);
            assertTrue(Files.exists(fs.getPath("heap")));
            assertFalse(Files.exists(fs.getPath("direct")));
        }
        assertArrayEquals(content, Files.readAllBytes(extracted));
        assertArrayEquals(content, Files.readAllBytes(moved));
        try (var fs = ArkivoFormats.openFileSystem(format, archive)) {
            assertArrayEquals(content, Files.readAllBytes(fs.getPath("heap")));
            assertFalse(Files.exists(fs.getPath("direct")));
        }
    }

    /// Returns reproducible binary data that crosses several transfer-buffer boundaries.
    private static byte[] content() {
        byte[] bytes = new byte[4099];
        new Random(0x719be2L).nextBytes(bytes);
        return bytes;
    }
}
