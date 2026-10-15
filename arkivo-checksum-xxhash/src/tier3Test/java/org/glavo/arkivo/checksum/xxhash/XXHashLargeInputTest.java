// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.checksum.xxhash;

import net.jpountz.xxhash.StreamingXXHash32;
import net.jpountz.xxhash.StreamingXXHash64;
import net.jpountz.xxhash.XXHashFactory;
import org.glavo.arkivo.checksum.ChecksumAccumulator;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies XXHash length accumulation and single-update arithmetic for large inputs.
@NotNullByDefault
public final class XXHashLargeInputTest {
    /// Reusable source chunk size that bounds physical test memory.
    private static final int CHUNK_SIZE = 16 * 1024 * 1024;

    /// Logical input length required to cross both four-GiB and eight-GiB boundaries.
    private static final long TARGET_SIZE = (1L << 33) + CHUNK_SIZE;

    /// Processes a maximum-sized buffer after a partial stripe without overflowing the small-input guard.
    @Test
    public void hashesMaximumSizedBufferAfterPartialStripe(@TempDir Path directory) throws Exception {
        Path output = directory.resolve("probe.log");
        String classPath = Stream.of(XXHashLargeInputTest.class, XXHash32.class, ChecksumAccumulator.class,
                        ByteArrayAccess.class, XXHashFactory.class)
                .map(type -> {
                    try {
                        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
                    } catch (java.net.URISyntaxException exception) {
                        throw new IllegalStateException(exception);
                    }
                })
                .distinct().collect(Collectors.joining(File.pathSeparator));
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx128m", "-cp", classPath,
                XXHashLargeInputTest.class.getName(), directory.resolve("large-input.bin").toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(3, TimeUnit.MINUTES), "Large mapped input probe timed out");
            assertEquals(0, process.exitValue(), () -> {
                try {
                    return Files.readString(output);
                } catch (IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                }
            });
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor();
            }
        }
    }

    /// Runs in a separate JVM so its mapped buffer is released before the parent deletes the temporary file.
    ///
    /// @param arguments the sparse input file path
    /// @throws IOException if the temporary input cannot be created or mapped
    public static void main(String[] arguments) throws IOException {
        Path path = Path.of(arguments[0]);
        try (FileChannel file = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.SPARSE,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
             StreamingXXHash32 expected32 = XXHashFactory.safeInstance().newStreamingHash32(0);
             StreamingXXHash64 expected64 = XXHashFactory.safeInstance().newStreamingHash64(0)) {
            file.position(Integer.MAX_VALUE - 1L);
            file.write(ByteBuffer.wrap(new byte[]{0}));
            ByteBuffer input = file.map(FileChannel.MapMode.READ_ONLY, 0, Integer.MAX_VALUE);
            var actual32 = XXHash32.newAccumulator();
            var actual64 = XXHash64.newAccumulator();
            actual32.update((byte) 1);
            actual64.update((byte) 1);
            expected32.update(new byte[]{1}, 0, 1);
            expected64.update(new byte[]{1}, 0, 1);
            actual32.update(input.duplicate());
            actual64.update(input.duplicate());
            byte[] chunk = new byte[CHUNK_SIZE];
            while (input.hasRemaining()) {
                int count = Math.min(input.remaining(), chunk.length);
                input.get(chunk, 0, count);
                expected32.update(chunk, 0, count);
                expected64.update(chunk, 0, count);
            }
            if (expected32.getValue() != actual32.finishInt() || expected64.getValue() != actual64.finishLong()) {
                throw new AssertionError("Single-buffer checksums differ from chunked reference checksums");
            }
        }
    }

    /// Verifies both widths against lz4-java's pure Java streaming implementations.
    @Test
    public void hashesMoreThanEightGiBWithoutLengthTruncation() {
        byte[] chunk = new byte[CHUNK_SIZE];
        new Random(0x4c5a_3401L).nextBytes(chunk);
        int seed32 = 0x9747_b28c;
        long seed64 = 0x0123_4567_89ab_cdefL;
        ChecksumAccumulator.Width32 actual32 = XXHash32.newAccumulator(seed32);
        ChecksumAccumulator.Width64 actual64 = XXHash64.newAccumulator(seed64);

        XXHashFactory oracle = XXHashFactory.safeInstance();
        try (StreamingXXHash32 expected32 = oracle.newStreamingHash32(seed32);
             StreamingXXHash64 expected64 = oracle.newStreamingHash64(seed64)) {
            long totalSize = 0L;
            while (totalSize < TARGET_SIZE) {
                actual32.update(chunk);
                actual64.update(chunk);
                expected32.update(chunk, 0, chunk.length);
                expected64.update(chunk, 0, chunk.length);
                totalSize += chunk.length;
            }
            assertEquals(
                    Integer.toUnsignedLong(expected32.getValue()),
                    actual32.finishLong(),
                    "XXH32 logical input size " + totalSize
            );
            assertEquals(
                    expected64.getValue(),
                    actual64.finishLong(),
                    "XXH64 logical input size " + totalSize
            );
        }
    }
}
