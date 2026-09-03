// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies built-in commit-target publication, rollback, and terminal-state contracts.
@NotNullByDefault
final class ArkivoCommitTargetTest {
    /// Directory containing commit targets and staging files.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies direct replacement writes the source path and remains committed after rollback or close.
    @Test
    void replacesOriginalDirectlyAndFinishesOutput() throws IOException {
        Path source = temporaryDirectory.resolve("archive.zip");
        Files.write(source, new byte[]{1, 2});
        ArkivoCommitOutput output = ArkivoCommitTarget.replaceOriginal().openOutput(source);

        assertEquals(source, output.path());
        write(output, new byte[]{3, 4, 5});
        output.commit();
        assertArrayEquals(new byte[]{3, 4, 5}, Files.readAllBytes(source));

        output.rollback();
        output.close();
        assertArrayEquals(new byte[]{3, 4, 5}, Files.readAllBytes(source));
        assertClosed(output);
    }

    /// Verifies rolling back direct fixed-path output leaves already written bytes but closes the transaction.
    @Test
    void retainsFixedPathBytesAfterRollback() throws IOException {
        Path target = temporaryDirectory.resolve("derived.tar");
        ArkivoCommitOutput output = ArkivoCommitTarget.writeTo(target).openOutput(null);

        write(output, new byte[]{6, 7});
        output.rollback();
        output.rollback();
        output.close();

        assertArrayEquals(new byte[]{6, 7}, Files.readAllBytes(target));
        assertClosed(output);
    }

    /// Verifies atomic commit replaces the original and removes the staging path.
    @Test
    void atomicallyReplacesOriginal() throws IOException {
        Path source = temporaryDirectory.resolve("archive.7z");
        Path stagingDirectory = temporaryDirectory.resolve("staging");
        Files.write(source, new byte[]{1});
        ArkivoCommitOutput output = ArkivoCommitTarget.atomicReplace(stagingDirectory).openOutput(source);
        Path stagingPath = output.path();

        assertTrue(Files.isDirectory(stagingDirectory));
        assertTrue(Files.exists(stagingPath));
        write(output, new byte[]{8, 9, 10});
        output.commit();

        assertArrayEquals(new byte[]{8, 9, 10}, Files.readAllBytes(source));
        assertFalse(Files.exists(stagingPath));
        output.rollback();
        output.close();
        assertClosed(output);
    }

    /// Verifies atomic rollback deletes staging bytes and preserves the original archive.
    @Test
    void rollsBackAtomicOutput() throws IOException {
        Path source = temporaryDirectory.resolve("archive.rar");
        Files.write(source, new byte[]{11, 12});
        ArkivoCommitOutput output = ArkivoCommitTarget.atomicReplace(
                temporaryDirectory.resolve("staging")
        ).openOutput(source);
        Path stagingPath = output.path();
        write(output, new byte[]{13, 14});

        output.rollback();
        output.rollback();
        output.close();

        assertFalse(Files.exists(stagingPath));
        assertArrayEquals(new byte[]{11, 12}, Files.readAllBytes(source));
        assertClosed(output);
    }

    /// Verifies factory validation and the shared direct-replacement target identity.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void validatesCommitTargetArguments() {
        assertSame(ArkivoCommitTarget.replaceOriginal(), ArkivoCommitTarget.replaceOriginal());
        assertThrows(NullPointerException.class, () -> ArkivoCommitTarget.writeTo(null));
        assertThrows(NullPointerException.class, () -> ArkivoCommitTarget.atomicReplace(null));
        assertThrows(IllegalArgumentException.class, () -> ArkivoCommitTarget.replaceOriginal().openOutput(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> ArkivoCommitTarget.atomicReplace(temporaryDirectory).openOutput(null)
        );
    }

    /// Replaces an output path with the given bytes through its channel factory.
    private static void write(ArkivoCommitOutput output, byte @Unmodifiable [] bytes) throws IOException {
        try (SeekableByteChannel channel = output.openChannel(Set.of(
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        ))) {
            ByteBuffer source = ByteBuffer.wrap(bytes);
            while (source.hasRemaining()) {
                int written = channel.write(source);
                if (written == 0) {
                    throw new IOException("Test output channel made no write progress");
                }
            }
        }
    }

    /// Verifies every operation that requires an open output reports its terminal state.
    private static void assertClosed(ArkivoCommitOutput output) {
        IOException openFailure = assertThrows(
                IOException.class,
                () -> output.openChannel(Set.of(StandardOpenOption.READ))
        );
        assertEquals("Commit output is closed", openFailure.getMessage());
        IOException commitFailure = assertThrows(IOException.class, output::commit);
        assertEquals("Commit output is closed", commitFailure.getMessage());
    }
}
