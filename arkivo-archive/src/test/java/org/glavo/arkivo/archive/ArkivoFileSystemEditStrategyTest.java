// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests built-in archive file system edit strategies.
@NotNullByDefault
public final class ArkivoFileSystemEditStrategyTest {
    /// Verifies that memory edit storage can stage and reopen content.
    @Test
    public void memoryEditStorage() throws IOException {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);

        try (ArkivoEditStorage storage = ArkivoEditStorage.memory();
             ArkivoStoredContent stored = storage.createContent("hello.txt", ArkivoEditStorage.UNKNOWN_SIZE)) {
            try (SeekableByteChannel channel = stored.openChannel(Set.of(
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            ))) {
                assertEquals(content.length, channel.write(ByteBuffer.wrap(content)));
            }

            assertEquals(content.length, stored.size());
            ByteBuffer buffer = ByteBuffer.allocate(content.length);
            try (SeekableByteChannel channel = stored.openChannel(Set.of(StandardOpenOption.READ))) {
                assertEquals(content.length, channel.read(buffer));
            }
            assertArrayEquals(content, buffer.array());
        }
    }

    /// Verifies that a fixed commit target writes assembled archive bytes to its target path.
    @Test
    public void fixedCommitTarget() throws IOException {
        Path directory = Files.createTempDirectory(Path.of("build", "tmp"), "arkivo-fs-target-");
        Path sourcePath = directory.resolve("source.zip");
        Path targetPath = directory.resolve("target.zip");
        byte[] content = "zip".getBytes(StandardCharsets.UTF_8);

        try {
            ArkivoCommitTarget target = ArkivoCommitTarget.writeTo(targetPath);
            try (ArkivoCommitOutput output = target.openOutput(sourcePath)) {
                assertEquals(targetPath, output.path());
                try (SeekableByteChannel channel = output.openChannel(Set.of(
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                ))) {
                    assertEquals(content.length, channel.write(ByteBuffer.wrap(content)));
                }
                output.commit();
            }

            assertArrayEquals(content, Files.readAllBytes(targetPath));
        } finally {
            Files.deleteIfExists(targetPath);
            Files.deleteIfExists(sourcePath);
            Files.deleteIfExists(directory);
        }
    }

    /// Verifies that fixed targets support archive sources without a source path.
    @Test
    public void fixedCommitTargetWithoutSourcePath() throws IOException {
        Path directory = Files.createTempDirectory(Path.of("build", "tmp"), "arkivo-fs-channel-target-");
        Path targetPath = directory.resolve("target.ar");
        byte[] content = "ar".getBytes(StandardCharsets.UTF_8);

        try {
            try (ArkivoCommitOutput output = ArkivoCommitTarget.writeTo(targetPath).openOutput(null)) {
                try (SeekableByteChannel channel = output.openChannel(Set.of(
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                ))) {
                    assertEquals(content.length, channel.write(ByteBuffer.wrap(content)));
                }
                output.commit();
            }

            assertArrayEquals(content, Files.readAllBytes(targetPath));
            assertThrows(IllegalArgumentException.class, () -> ArkivoCommitTarget.replaceOriginal().openOutput(null));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ArkivoCommitTarget.atomicReplace(directory).openOutput(null)
            );
        } finally {
            Files.deleteIfExists(targetPath);
            Files.deleteIfExists(directory);
        }
    }

    /// Verifies that temporary stored content can retry cleanup after a failed delete.
    @Test
    public void temporaryStoredContentCloseCanRetryCleanup() throws Exception {
        Path directory = Files.createTempDirectory(Path.of("build", "tmp"), "arkivo-fs-storage-");
        Path contentPath = null;
        Path blocker = null;

        try (ArkivoEditStorage storage = ArkivoEditStorage.temporaryFiles(directory)) {
            ArkivoStoredContent stored = storage.createContent("hello.txt", ArkivoEditStorage.UNKNOWN_SIZE);
            contentPath = temporaryContentPath(stored);
            Files.delete(contentPath);
            Files.createDirectory(contentPath);
            blocker = contentPath.resolve("blocker");
            Files.writeString(blocker, "blocker", StandardCharsets.UTF_8);

            IOException exception = assertThrows(IOException.class, stored::close);
            assertEquals(DirectoryNotEmptyException.class, exception.getClass());
            assertThrows(IOException.class, () -> stored.openChannel(Set.of(StandardOpenOption.READ)));

            Files.delete(blocker);
            stored.close();

            assertEquals(false, Files.exists(contentPath));
            stored.close();
        } finally {
            if (blocker != null) {
                Files.deleteIfExists(blocker);
            }
            if (contentPath != null) {
                Files.deleteIfExists(contentPath);
            }
            Files.deleteIfExists(directory);
        }
    }

    /// Verifies that atomic commit output can retry rollback cleanup after a failed delete.
    @Test
    public void atomicCommitOutputCloseCanRetryRollbackCleanup() throws IOException {
        Path directory = Files.createTempDirectory(Path.of("build", "tmp"), "arkivo-fs-commit-");
        Path sourcePath = directory.resolve("source.zip");
        Path outputPath = null;
        Path blocker = null;

        try {
            ArkivoCommitOutput output = ArkivoCommitTarget.atomicReplace(directory).openOutput(sourcePath);
            outputPath = output.path();
            Files.delete(outputPath);
            Files.createDirectory(outputPath);
            blocker = outputPath.resolve("blocker");
            Files.writeString(blocker, "blocker", StandardCharsets.UTF_8);

            IOException exception = assertThrows(IOException.class, output::close);
            assertEquals(DirectoryNotEmptyException.class, exception.getClass());
            assertThrows(IOException.class, () -> output.openChannel(Set.of(StandardOpenOption.WRITE)));

            Files.delete(blocker);
            output.close();

            assertEquals(false, Files.exists(outputPath));
            output.close();
        } finally {
            if (blocker != null) {
                Files.deleteIfExists(blocker);
            }
            if (outputPath != null) {
                Files.deleteIfExists(outputPath);
            }
            Files.deleteIfExists(sourcePath);
            Files.deleteIfExists(directory);
        }
    }

    /// Returns the temporary file path owned by a stored content instance.
    private static Path temporaryContentPath(ArkivoStoredContent stored) throws ReflectiveOperationException {
        Field field = stored.getClass().getDeclaredField("path");
        field.setAccessible(true);
        return (Path) field.get(stored);
    }
}
