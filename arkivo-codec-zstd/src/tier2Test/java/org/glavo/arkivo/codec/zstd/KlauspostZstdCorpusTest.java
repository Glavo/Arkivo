// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import com.github.luben.zstd.ZstdInputStream;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Checks pinned klauspost Zstandard fixtures against their plaintext and the independent native decoder.
@NotNullByDefault
final class KlauspostZstdCorpusTest {
    /// Maximum accepted decoded fixture size, independent of untrusted frame size declarations.
    private static final int MAXIMUM_OUTPUT_SIZE = 16 * 1024 * 1024;

    /// Limits both sliding history and decoded output during corpus validation.
    private static final ZstdCodec CODEC = new ZstdCodec().withMaximumOutputSize(MAXIMUM_OUTPUT_SIZE)
            .withMaximumWindowSize(64L * 1024 * 1024).withMaximumMemorySize(128L * 1024 * 1024);

    /// Bytes following the last valid frame that must remain available to the caller.
    private static final byte @Unmodifiable [] TRAILER = {0x12, 0x34, 0x56};

    /// An upstream good.zip seed whose compressed block exceeds its zero-byte single-segment window.
    private static final Fixture ZERO_WINDOW_FRAME =
            new Fixture("good.zip", "2274d31e0d569fe9e31bedc4f9fddd9c9f114c2f.zst");

    /// A decoder regression seed stored without a filename extension.
    private static final Fixture REGRESSION_FRAME =
            new Fixture("decode-regression.zip", "002135096346d2e9c82e7b0b5ef85bacd61ddc17");

    /// Decodes every paired fixture through streams and resettable engines with distinct buffer layouts.
    @ParameterizedTest(name = "{0}")
    @MethodSource("plainFrames")
    void readsPlainFrames(Fixture fixture) throws IOException {
        byte[] encoded = read(fixture);
        byte[] expected = nativeDecode(encoded, null);
        try (var archive = new ZipFile(resource(fixture.archive()).toFile())) {
            @Nullable var plain = archive.getEntry(fixture.entry().substring(0, fixture.entry().length() - 4));
            assertNotNull(plain, "valid frame must have upstream plaintext: " + fixture);
            try (var input = archive.getInputStream(plain)) {
                assertArrayEquals(readBounded(input), expected, "upstream plaintext");
            }
        }
        try (var input = CODEC.newInputStream(new ByteArrayInputStream(encoded))) {
            assertArrayEquals(expected, readBounded(input));
        }
        try (var decoder = CODEC.newDecoder()) {
            assertArrayEquals(expected, decode(decoder, encoded, null, false, false));
            decoder.reset();
            assertArrayEquals(expected, decode(decoder, encoded, null, true, true));
        }
    }

    /// Rejects malformed compressed frames rather than accepting their partial decoded output.
    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedFrames")
    void rejectsMalformedFrames(Fixture fixture) throws IOException {
        byte[] encoded = read(fixture);
        assertThrows(IOException.class, () -> nativeDecode(encoded, null), "native reference");
        assertThrows(IOException.class, () -> {
            try (var input = CODEC.newInputStream(new ByteArrayInputStream(encoded))) {
                readBounded(input);
            }
        });
        try (var decoder = CODEC.newDecoder()) {
            assertThrows(IOException.class, () -> decode(decoder, encoded, null, false, false));
            decoder.reset();
            assertThrows(IOException.class, () -> decode(decoder, encoded, null, true, true));
            decoder.reset();
            byte[] empty = read(new Fixture("good.zip", "empty.zst"));
            assertArrayEquals(new byte[0], decode(decoder, empty, null, true, true));
        }
    }

    /// Resolves dictionary requests from independently trained dictionaries and checks every decoded byte.
    @ParameterizedTest(name = "{0}")
    @MethodSource("dictionaryFrames")
    void readsDictionaryFrames(Fixture fixture) throws IOException {
        byte[] encoded = read(fixture);
        int separator = fixture.entry().indexOf('/');
        // The top-level dictplain frame also requires d0; its name does not mean dictionary-free.
        String dictionaryName = separator < 0 ? "d0" : fixture.entry().substring(0, separator);
        byte[] dictionaryBytes = read(new Fixture(fixture.archive(), dictionaryName + ".dict"));
        ZstdDictionary dictionary = ZstdDictionary.fullDictionary(dictionaryBytes);
        byte[] expected = nativeDecode(encoded, dictionaryBytes);
        ZstdCodec configured = CODEC.withDictionary(dictionary);
        try (var input = configured.newInputStream(new ByteArrayInputStream(encoded))) {
            assertArrayEquals(expected, readBounded(input));
        }
        try (var decoder = CODEC.newDecoder()) {
            assertArrayEquals(expected, decode(decoder, encoded, dictionary, true, true));
            decoder.reset();
            assertArrayEquals(expected, decode(decoder, encoded, dictionary, false, false));
        }
    }

    /// Decodes the upstream patch-from frame using raw history, not a formatted Zstandard dictionary.
    @Test
    void readsRawDictionaryDelta() throws IOException {
        byte[] dictionary = Files.readAllBytes(resource("delta/source.txt"));
        byte[] encoded = Files.readAllBytes(resource("delta/target.txt.zst"));
        byte[] expected = Files.readAllBytes(resource("delta/target.txt"));
        assertArrayEquals(expected, nativeDecode(encoded, dictionary));
        ZstdCodec configured = CODEC.withDictionary(ZstdDictionary.rawContent(dictionary));
        try (var decoder = configured.newDecoder()) {
            assertArrayEquals(expected, decode(decoder, encoded, null, true, true));
            decoder.reset();
            assertArrayEquals(expected, decode(decoder, encoded, null, false, false));
        }
    }

    /// Pins selected archive inventories so renamed, missing, or unclassified frames cannot disappear silently.
    @Test
    void accountsForSelectedFrames() throws IOException {
        assertEquals(94, frames("decoder.zip").count());
        assertEquals(12, frames("good.zip").count());
        assertEquals(32, frames("bad.zip").count());
        assertEquals(41, dictionaryFrames().count());
        assertEquals(105, plainFrames().count());
        assertEquals(34, malformedFrames().count());
        try (var archive = new ZipFile(resource(REGRESSION_FRAME.archive()).toFile())) {
            assertEquals(1, archive.size());
            assertNotNull(archive.getEntry(REGRESSION_FRAME.entry()));
        }
        try (var archive = new ZipFile(resource("dict-tests-small.zip").toFile())) {
            assertEquals(4, archive.stream().filter(entry -> entry.getName().endsWith(".dict")).count());
        }
    }

    /// Consumes fresh padded input chunks and checks frame stops, dictionary waits, and output boundaries.
    private static byte[] decode(
            CompressionDecoder.FramedDictionaryAware<ZstdDictionary, ZstdDictionaryRequest> decoder,
            byte[] encoded, @Nullable ZstdDictionary dictionary, boolean direct, boolean fragmented
    ) throws IOException {
        int inputChunkSize = fragmented ? encoded.length < 1024 ? 1 : 97 : encoded.length;
        int outputChunkSize = fragmented ? encoded.length < 1024 ? 7 : 4093 : 8192;
        ByteBuffer source = ByteBuffer.allocate(0).asReadOnlyBuffer();
        ByteBuffer target = direct ? ByteBuffer.allocateDirect(outputChunkSize + 6)
                : ByteBuffer.allocate(outputChunkSize + 6);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int supplied = 0;
        int consumed = 0;
        while (true) {
            if (!source.hasRemaining() && supplied < encoded.length) {
                int count = Math.min(inputChunkSize, encoded.length - supplied);
                int trailerSize = supplied + count == encoded.length ? TRAILER.length : 0;
                ByteBuffer storage = direct ? ByteBuffer.allocateDirect(count + trailerSize + 6)
                        : ByteBuffer.allocate(count + trailerSize + 6);
                storage.position(3).put(encoded, supplied, count);
                if (trailerSize != 0) {
                    storage.put(TRAILER);
                }
                source = storage.flip().position(3).asReadOnlyBuffer();
                supplied += count;
            }
            target.clear();
            for (int i = 0; i < target.capacity(); i++) {
                target.put(i, (byte) 0x5a);
            }
            target.position(3).limit(3 + outputChunkSize);
            int before = source.position();
            int inputLimit = source.limit();
            CodecOutcome outcome = supplied == encoded.length ? decoder.finish(source, target)
                    : decoder.decode(source, target);
            consumed += source.position() - before;
            assertEquals(inputLimit, source.limit());
            assertEquals(3 + outputChunkSize, target.limit());
            assertTrue(consumed <= encoded.length, "frame consumed trailing bytes");
            int produced = target.position() - 3;
            assertTrue(output.size() + produced <= MAXIMUM_OUTPUT_SIZE);
            byte[] bytes = new byte[produced];
            target.flip().position(3).get(bytes);
            output.writeBytes(bytes);
            target.clear();
            for (int i = 0; i < 3; i++) {
                assertEquals((byte) 0x5a, target.get(i));
                assertEquals((byte) 0x5a, target.get(3 + outputChunkSize + i));
            }
            if (outcome == CodecOutcome.FINISHED) {
                if (consumed == encoded.length) {
                    byte[] remaining = new byte[source.remaining()];
                    source.get(remaining);
                    assertArrayEquals(TRAILER, remaining);
                    return output.toByteArray();
                }
                decoder.reset();
            } else if (outcome == CodecOutcome.NEEDS_DICTIONARY) {
                assertNotNull(dictionary, "unexpected dictionary request");
                assertEquals(dictionary.dictionaryId(), decoder.dictionaryRequest().dictionaryId());
                int position = source.position();
                ByteBuffer waitingOutput = ByteBuffer.allocate(1);
                assertEquals(CodecOutcome.NEEDS_DICTIONARY, decoder.decode(source, waitingOutput));
                assertEquals(position, source.position());
                assertEquals(0, waitingOutput.position());
                decoder.provideDictionary(dictionary);
            } else if (outcome == CodecOutcome.NEEDS_INPUT) {
                assertEquals(source.limit(), source.position());
                assertTrue(supplied < encoded.length, "decoder requested input after EOF");
            } else {
                assertEquals(CodecOutcome.NEEDS_OUTPUT, outcome);
                assertEquals(outputChunkSize, produced);
            }
        }
    }

    /// Decodes all concatenated frames using the official native implementation with bounded history and output.
    private static byte[] nativeDecode(byte[] encoded, byte @Nullable [] dictionary) throws IOException {
        try (var input = new ZstdInputStream(new ByteArrayInputStream(encoded))) {
            input.setLongMax(26);
            if (dictionary != null) {
                input.setDict(dictionary);
            }
            return readBounded(input);
        }
    }

    /// Reads bounded output and fails if a fixture exceeds the suite's logical output budget.
    private static byte[] readBounded(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAXIMUM_OUTPUT_SIZE + 1);
        assertTrue(bytes.length <= MAXIMUM_OUTPUT_SIZE, "fixture exceeds output budget");
        return bytes;
    }

    /// Returns valid frames, excluding the explicitly classified zero-window seed.
    private static Stream<Fixture> plainFrames() throws IOException {
        return Stream.concat(frames("decoder.zip"), frames("good.zip"))
                .filter(fixture -> !fixture.equals(ZERO_WINDOW_FRAME));
    }

    /// Returns compressed malformed frames, excluding adjacent human-readable error descriptions.
    private static Stream<Fixture> malformedFrames() throws IOException {
        return Stream.concat(frames("bad.zip"), Stream.of(ZERO_WINDOW_FRAME, REGRESSION_FRAME));
    }

    /// Returns every dictionary frame, including the top-level frame using d0.
    private static Stream<Fixture> dictionaryFrames() throws IOException {
        return frames("dict-tests-small.zip");
    }

    /// Enumerates frame names without keeping the ZIP container open during parameterized execution.
    private static Stream<Fixture> frames(String name) throws IOException {
        try (var archive = new ZipFile(resource(name).toFile())) {
            return archive.stream().filter(entry -> entry.getName().endsWith(".zst"))
                    .map(entry -> new Fixture(name, entry.getName())).toList().stream();
        }
    }

    /// Reads one unmodified member from the verified upstream ZIP container.
    private static byte[] read(Fixture fixture) throws IOException {
        try (var archive = new ZipFile(resource(fixture.archive()).toFile())) {
            @Nullable var entry = archive.getEntry(fixture.entry());
            assertNotNull(entry, fixture.toString());
            try (var input = archive.getInputStream(entry)) {
                return readBounded(input);
            }
        }
    }

    /// Locates the Gradle-prepared Zstandard test-data directory.
    private static Path resource(String name) {
        return Path.of(Objects.requireNonNull(System.getProperty("arkivo.klauspost.testDataDirectory"),
                "klauspost test data directory is not configured")).resolve("zstd/testdata").resolve(name);
    }

    /// Identifies one compressed frame within an upstream ZIP container.
    ///
    /// @param archive the ZIP container name
    /// @param entry the unchanged member name
    @NotNullByDefault
    private record Fixture(String archive, String entry) {
    }
}
