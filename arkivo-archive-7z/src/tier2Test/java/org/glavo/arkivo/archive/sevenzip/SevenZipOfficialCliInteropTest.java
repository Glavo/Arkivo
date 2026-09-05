// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies Arkivo 7z output with an explicitly configured official 7-Zip command-line tool.
@NotNullByDefault
public final class SevenZipOfficialCliInteropTest {
    /// The isolated output directory for the generated archive.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies that the official CLI can fully test encrypted Arkivo BCJ2 output when configured.
    @Test
    public void officialSevenZipReadsBcj2OutputWhenAvailable() throws IOException {
        @Nullable String configuredExecutable = System.getenv("ARKIVO_7Z_EXECUTABLE");
        Assumptions.assumeTrue(
                configuredExecutable != null && Files.isRegularFile(Path.of(configuredExecutable)),
                "ARKIVO_7Z_EXECUTABLE does not name an official 7-Zip CLI"
        );
        String executable = Objects.requireNonNull(configuredExecutable);

        String passwordText = "arkivo-bcj2-interop";
        byte[] password = passwordText.getBytes(StandardCharsets.UTF_16LE);
        try {
            byte[] content = bcj2FriendlyContent();
            Path archivePath = temporaryDirectory.resolve("bcj2-official.7z");
            try (SevenZipArkivoStreamingWriter writer = SevenZipArkivoStreamingWriter.create(
                    archivePath,
                    SevenZipArchiveOptions.CREATE_DEFAULTS
                            .withCompression(SevenZipCompression.lzma2(64 * 1024))
                            .withFilters(SevenZipFilterChain.of(SevenZipFilter.bcj2()))
                            .withPasswordProvider(ArkivoPasswordProvider.fixed(password))
                            .withEncryptHeaders(true)
            )) {
                var entry = writer.beginFile("content.bin");
                try (OutputStream output = entry.openOutputStream()) {
                    output.write(content);
                }
            }

            Process process = new ProcessBuilder(
                    executable,
                    "t",
                    "-bb0",
                    "-p" + passwordText,
                    archivePath.toAbsolutePath().toString()
            ).redirectErrorStream(true).start();
            boolean completed;
            try {
                completed = process.waitFor(30L, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while testing BCJ2 output with 7-Zip", exception);
            }
            if (!completed) {
                process.destroyForcibly();
                try {
                    process.waitFor();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while stopping timed-out 7-Zip", exception);
                }
                throw new IOException("Timed out while testing BCJ2 output with 7-Zip");
            }

            String output;
            try (InputStream input = process.getInputStream()) {
                output = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            assertEquals(0, process.exitValue(), output);
        } finally {
            Arrays.fill(password, (byte) 0);
        }
    }

    /// Returns deterministic x86-like bytes that exercise all BCJ2 side streams.
    private static byte[] bcj2FriendlyContent() {
        byte[] content = new byte[16 * 1024];
        for (int index = 0; index < content.length; index++) {
            content[index] = (byte) (index * 43 + 7);
        }
        for (int position = 48; position + 5 < content.length; position += 211) {
            content[position] = (byte) 0xe8;
            ByteBuffer.wrap(content, position + 1, Integer.BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(12 - position - 5);
        }
        return content;
    }
}
