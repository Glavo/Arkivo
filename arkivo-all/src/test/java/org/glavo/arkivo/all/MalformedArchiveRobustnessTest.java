// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.archive.ArkivoStreamingReader;
import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies archive parsers fail predictably for deterministically damaged input.
@NotNullByDefault
final class MalformedArchiveRobustnessTest {
    /// Stable archive formats that support both file-system and streaming reads.
    private static final @Unmodifiable List<String> FORMATS = List.of("ar", "tar", "zip", "7z");

    /// Formats whose readers can operate without random access.
    private static final @Unmodifiable Set<String> STREAMING_FORMATS = Set.of("ar", "tar", "zip");

    /// Deterministic content stored in every generated archive.
    private static final byte @Unmodifiable [] CONTENT = (
            "Arkivo malformed archive robustness fixture\n" + "0123456789abcdef".repeat(128)
    ).getBytes(java.nio.charset.StandardCharsets.UTF_8);

    /// Common limits that bound every malformed archive attempt.
    private static final @Unmodifiable Map<String, Object> READ_ENVIRONMENT = Map.of(
            ArkivoFileSystem.MAX_ENTRY_COUNT.key(), 16L,
            ArkivoFileSystem.MAX_ENTRY_SIZE.key(), 1L << 20,
            ArkivoFileSystem.MAX_TOTAL_ENTRY_SIZE.key(), 1L << 20,
            ArkivoFileSystem.MAX_METADATA_SIZE.key(), 1L << 20
    );

    /// Maximum bytes consumed defensively even when a parser accepts a mutation.
    private static final long MAX_CONSUMED_BYTES = 1L << 20;

    /// Number of deterministic mutations applied to each valid archive.
    private static final int MUTATION_COUNT = 32;

    /// Reproducible seed for archive mutations.
    private static final long MUTATION_SEED = 0x41524b49564f4d41L;

    /// Exercises representative truncations and mutations through every public archive read path.
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void boundsAndNormalizesMalformedArchiveFailures() throws IOException {
        for (String format : FORMATS) {
            byte[] validArchive = createArchive(format);
            Path archivePath = Files.createTempFile("arkivo-malformed-" + format + "-", ".bin");
            try {
                Files.write(archivePath, validArchive, StandardOpenOption.TRUNCATE_EXISTING);
                assertEquals(CONTENT.length, readFileSystem(format, archivePath), format);
                if (STREAMING_FORMATS.contains(format)) {
                    assertEquals(CONTENT.length, readStreaming(format, validArchive), format);
                }

                for (int length : truncationLengths(validArchive.length)) {
                    byte[] truncated = Arrays.copyOf(validArchive, length);
                    exerciseMalformedArchive(format, archivePath, truncated, "truncation@" + length);
                }

                SplittableRandom random = new SplittableRandom(MUTATION_SEED ^ format.hashCode());
                for (int index = 0; index < MUTATION_COUNT; index++) {
                    byte[] mutated = mutate(validArchive, random);
                    exerciseMalformedArchive(format, archivePath, mutated, "mutation#" + index);
                }
            } finally {
                Files.deleteIfExists(archivePath);
            }
        }
    }

    /// Creates one valid single-entry archive entirely in memory.
    private static byte[] createArchive(String format) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ArkivoStreamingWriter writer = ArkivoFormats.openStreamingWriter(format, output)) {
            writer.beginFile("payload.bin");
            try (OutputStream body = writer.openOutputStream()) {
                body.write(CONTENT);
            }
        }
        return output.toByteArray();
    }

    /// Exercises detection, random-access reading, and forward-only reading for one damaged archive.
    private static void exerciseMalformedArchive(
            String format,
            Path archivePath,
            byte[] archive,
            String variant
    ) throws IOException {
        String context = format + " " + variant;
        tolerateMalformedFailure(
                () -> ArkivoFormats.detect(ByteBuffer.wrap(archive).asReadOnlyBuffer()),
                context + " detection"
        );

        Files.write(archivePath, archive, StandardOpenOption.TRUNCATE_EXISTING);
        tolerateMalformedFailure(
                () -> readFileSystem(format, archivePath),
                context + " file system"
        );
        tolerateMalformedFailure(
                () -> readStreaming(format, archive),
                context + " streaming reader"
        );
    }

    /// Reads every regular entry through the named archive file-system implementation.
    private static long readFileSystem(String format, Path archivePath) throws IOException {
        long[] total = {0L};
        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(
                format,
                archivePath,
                READ_ENVIRONMENT
        )) {
            consumeDirectory(fileSystem.getPath("/"), total);
        }
        return total[0];
    }

    /// Recursively consumes regular files without following symbolic links.
    private static void consumeDirectory(Path directory, long[] total) throws IOException {
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            try {
                for (Path child : children) {
                    BasicFileAttributes attributes = Files.readAttributes(
                            child,
                            BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS
                    );
                    if (attributes.isDirectory()) {
                        consumeDirectory(child, total);
                    } else if (attributes.isRegularFile()) {
                        try (ReadableByteChannel channel = Files.newByteChannel(child)) {
                            total[0] = Math.addExact(total[0], consumeChannel(channel));
                        }
                        requireBounded(total[0]);
                    }
                }
            } catch (DirectoryIteratorException exception) {
                throw exception.getCause();
            }
        }
    }

    /// Reads every regular entry through the named forward-only archive implementation.
    private static long readStreaming(String format, byte[] archive) throws IOException {
        long total = 0L;
        try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(
                format,
                Channels.newChannel(new ByteArrayInputStream(archive)),
                READ_ENVIRONMENT
        )) {
            while (reader.next()) {
                BasicFileAttributes attributes = reader.readAttributes(BasicFileAttributes.class);
                if (attributes.isRegularFile()) {
                    try (ReadableByteChannel channel = reader.openChannel()) {
                        total = Math.addExact(total, consumeChannel(channel));
                    }
                    requireBounded(total);
                }
            }
        }
        return total;
    }

    /// Fully consumes one channel while rejecting zero progress and excessive accepted output.
    private static long consumeChannel(ReadableByteChannel channel) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(4096);
        long total = 0L;
        while (true) {
            buffer.clear();
            int read = channel.read(buffer);
            if (read < 0) {
                return total;
            }
            if (read == 0) {
                throw new IOException("Archive entry channel made no progress");
            }
            total = Math.addExact(total, read);
            requireBounded(total);
        }
    }

    /// Rejects output beyond the independent defensive bound.
    private static void requireBounded(long total) throws IOException {
        if (total > MAX_CONSUMED_BYTES) {
            throw new IOException("Malformed archive exceeded the defensive output bound");
        }
    }

    /// Returns representative prefix lengths including header, midpoint, and tail boundaries.
    private static @Unmodifiable List<Integer> truncationLengths(int length) {
        TreeSet<Integer> lengths = new TreeSet<>();
        for (int value = 0; value <= Math.min(16, length - 1); value++) {
            lengths.add(value);
        }
        for (int offset = 1; offset <= Math.min(16, length); offset++) {
            lengths.add(length - offset);
        }
        for (int fraction = 1; fraction < 8; fraction++) {
            lengths.add((int) ((long) length * fraction / 8L));
        }
        lengths.remove(length);
        return List.copyOf(lengths);
    }

    /// Applies one to four deterministic non-zero bit flips to a cloned archive.
    private static byte[] mutate(byte[] validArchive, SplittableRandom random) {
        byte[] mutated = validArchive.clone();
        int changes = random.nextInt(1, 5);
        for (int change = 0; change < changes; change++) {
            int offset = random.nextInt(mutated.length);
            mutated[offset] ^= (byte) (1 << random.nextInt(Byte.SIZE));
        }
        return mutated;
    }

    /// Accepts documented malformed-input failures while rejecting leaked runtime exceptions and errors.
    private static void tolerateMalformedFailure(Executable operation, String context) {
        assertDoesNotThrow(() -> {
            try {
                operation.execute();
            } catch (IOException | UnsupportedOperationException expected) {
                // A damaged archive may be rejected during setup, iteration, body decoding, or close.
            }
        }, context);
    }
}
