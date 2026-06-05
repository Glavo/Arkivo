// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/// Tests built-in archive editor strategies.
@NotNullByDefault
public final class ArkivoEditorStrategyTest {
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
        Path directory = Files.createTempDirectory(Path.of("build", "tmp"), "arkivo-editor-target-");
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

    /// Verifies that source mutation policies return the expected decisions.
    @Test
    public void sourceMutationPolicies() throws IOException {
        ArkivoSourceMutationRequest fixedPatch = request(true, true);
        ArkivoSourceMutationRequest unsupportedPatch = request(false, false);

        assertEquals(ArkivoSourceMutationDecision.REWRITE, ArkivoSourceMutationPolicy.never().decide(fixedPatch));
        assertEquals(
                ArkivoSourceMutationDecision.ALLOW,
                ArkivoSourceMutationPolicy.patchWhenSafe().decide(fixedPatch)
        );
        assertEquals(
                ArkivoSourceMutationDecision.REWRITE,
                ArkivoSourceMutationPolicy.patchWhenSafe().decide(unsupportedPatch)
        );
        assertEquals(
                ArkivoSourceMutationDecision.ALLOW,
                ArkivoSourceMutationPolicy.directWhenPossible().decide(fixedPatch)
        );
    }

    /// Returns a source mutation request for tests.
    private static ArkivoSourceMutationRequest request(boolean fixedLengthPatch, boolean directMutationSupported) {
        return new ArkivoSourceMutationRequest() {
            /// Returns the test entry path.
            @Override
            public String path() {
                return "entry";
            }

            /// Returns whether this request is fixed length.
            @Override
            public boolean fixedLengthPatch() {
                return fixedLengthPatch;
            }

            /// Returns whether this request is directly supported.
            @Override
            public boolean directMutationSupported() {
                return directMutationSupported;
            }
        };
    }
}
