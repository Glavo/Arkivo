// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import com.github.luben.zstd.ZstdDictTrainer;
import com.github.luben.zstd.ZstdOutputStream;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.DecompressionWindowLimitException;
import org.glavo.arkivo.codec.EncodingOptions;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Adapts zstreamtest.c's dictionary lifetime, size/level, skippable-prefix and repeated-table regressions.
///
/// Initial codec dictionaries survive reset; dictionaries supplied in response to a request do not. Native
/// prefix-reference lifetimes are verified by a separately built reference, not imposed on configured Java dictionaries.
@NotNullByDefault
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(120)
final class ZstdOfficialDictionaryStreamingTest {
    /// Holds generated prefix frames and the native reference's file-backed diagnostics.
    @TempDir
    Path directory;

    /// Reproducible mixed literal and repeated input for the original 512-byte through 1-MiB size sweep.
    private final byte @Unmodifiable [] data = testData();

    /// A full dictionary trained independently by the reference implementation.
    private final byte @Unmodifiable [] dictionaryBytes = trainDictionary(data);

    /// Immutable full-dictionary configuration used by Java encoders and decoders.
    private final ZstdDictionary dictionary = ZstdDictionary.fullDictionary(dictionaryBytes);

    /// Preserves the upstream level 1..16 and power-of-two source-size matrix in both interoperability directions.
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16})
    void dictionarySourceSizeAndLevel(int level) throws IOException {
        ZstdCodec codec = ZstdCodec.DEFAULT.withDictionary(dictionary).withCompressionLevel(level);
        try (var nativeEncoder = new ZstdCompressCtx(); var nativeDecoder = new ZstdDecompressCtx()) {
            nativeEncoder.loadDict(dictionaryBytes).setLevel(level);
            nativeDecoder.loadDict(dictionaryBytes);
            for (int size = 512; size <= data.length; size <<= 1) {
                byte[] plain = Arrays.copyOf(data, size);
                byte[] frame = encode(codec, plain);
                assertArrayEquals(plain, nativeDecoder.decompress(frame, size), "level=" + level + ", size=" + size);
                assertEquals(dictionary.dictionaryId(), ((ZstdStandardFrameInfo) codec.frameInfo(ByteBuffer.wrap(frame))).dictionaryId());
                byte[] nativeFrame = nativeEncoder.compress(plain);
                try (var decoder = codec.newDecoder()) {
                    decodeFragments(decoder, ByteBuffer.wrap(nativeFrame), plain, 4093);
                }
            }
        }
        assertEquals(level, codec.compressionLevel());
        assertArrayEquals(dictionaryBytes, dictionary.bytes());
    }

    /// Maps loaded/DDict reuse and reset tests to persistent configuration and per-session dictionary requests.
    @Test
    void dictionaryLifetimeAcrossReset() throws IOException {
        byte[] plain = Arrays.copyOfRange(dictionaryBytes, dictionaryBytes.length - 1024, dictionaryBytes.length);
        byte[] frame;
        try (var nativeEncoder = new ZstdCompressCtx()) {
            frame = nativeEncoder.loadDict(dictionaryBytes).compress(plain);
        }
        byte[] wrongBytes = dictionaryBytes.clone();
        ByteArrayAccess.writeIntLittleEndian(wrongBytes, 4, (int) (dictionary.dictionaryId() ^ 1));
        ZstdDictionary wrong = ZstdDictionary.fullDictionary(wrongBytes);
        try (var decoder = ZstdCodec.DEFAULT.newDecoder()) {
            for (int round = 0; round < 2; round++) {
                decoder.reset();
                ByteBuffer source = ByteBuffer.wrap(frame);
                ByteBuffer target = ByteBuffer.allocate(plain.length + 1);
                assertEquals(CodecOutcome.NEEDS_DICTIONARY, decoder.decode(source, target));
                int stoppedAt = source.position();
                assertEquals(dictionary.dictionaryId(), decoder.dictionaryRequest().dictionaryId());
                assertThrows(IOException.class, () -> decoder.provideDictionary(wrong));
                assertEquals(CodecOutcome.NEEDS_DICTIONARY, decoder.decode(source, target));
                assertEquals(stoppedAt, source.position());
                assertEquals(0, target.position());
                decoder.provideDictionary(dictionary);
                assertEquals(CodecOutcome.FINISHED, decoder.finish(source, target));
                assertArrayEquals(plain, Arrays.copyOf(target.array(), target.position()));
            }
        }
        try (var decoder = ZstdCodec.DEFAULT.withDictionary(dictionary).newDecoder()) {
            for (int round = 0; round < 3; round++) {
                decoder.reset();
                decodeFragments(decoder, ByteBuffer.wrap(frame), plain, 7);
            }
        }
    }

    /// Verifies that omitting the dictionary ID does not remove the frame's dependency on dictionary contents.
    @Test
    void maskedDictionaryIdStillNeedsDictionary() throws IOException {
        byte[] plain = Arrays.copyOfRange(dictionaryBytes, dictionaryBytes.length - 1024, dictionaryBytes.length);
        ZstdCodec codec = ZstdCodec.builder().dictionary(dictionary).dictionaryId(false).frameChecksum(true).build();
        byte[] javaFrame = encode(codec, plain);
        byte[] nativeFrame;
        try (var nativeEncoder = new ZstdCompressCtx(); var nativeDecoder = new ZstdDecompressCtx()) {
            nativeFrame = nativeEncoder.loadDict(dictionaryBytes).setDictID(false).setChecksum(true).compress(plain);
            assertArrayEquals(plain, nativeDecoder.loadDict(dictionaryBytes).decompress(javaFrame, plain.length));
        }
        for (byte[] frame : new byte[][]{javaFrame, nativeFrame}) {
            assertEquals(0, ((ZstdStandardFrameInfo) codec.frameInfo(ByteBuffer.wrap(frame))).dictionaryId());
            try (var decoder = codec.newDecoder()) {
                decodeFragments(decoder, ByteBuffer.wrap(frame), plain, 7);
            }
            try (var decoder = ZstdCodec.DEFAULT.newDecoder()) {
                assertThrows(IOException.class, () -> decoder.finish(ByteBuffer.wrap(frame), ByteBuffer.allocate(plain.length + 1)));
            }
        }
    }

    /// Verifies raw-history interoperability and persistent configured history across explicit resets.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void rawDictionaryHistoryAcrossReset(int bufferKind) throws IOException {
        byte[] history = Arrays.copyOf(data, 4096);
        byte[] plain = Arrays.copyOfRange(history, 1024, history.length);
        ZstdCodec codec = ZstdCodec.DEFAULT.withDictionary(ZstdDictionary.rawContent(history)).withFrameChecksum(true);
        byte[] javaFrame = encode(codec, plain);
        byte[] nativeFrame;
        try (var nativeEncoder = new ZstdCompressCtx(); var nativeDecoder = new ZstdDecompressCtx()) {
            nativeFrame = nativeEncoder.loadDict(history).setChecksum(true).compress(plain);
            assertArrayEquals(plain, nativeDecoder.loadDict(history).decompress(javaFrame, plain.length));
        }
        for (byte[] frame : new byte[][]{javaFrame, nativeFrame}) {
            assertEquals(0, ((ZstdStandardFrameInfo) codec.frameInfo(ByteBuffer.wrap(frame))).dictionaryId());
            try (var decoder = ZstdCodec.DEFAULT.newDecoder()) {
                assertThrows(IOException.class, () -> decoder.finish(ByteBuffer.wrap(frame), ByteBuffer.allocate(plain.length)));
            }
            try (var decoder = codec.newDecoder()) {
                for (int repeat = 0; repeat < 3; repeat++) {
                    decoder.reset();
                    ByteBuffer input = bufferKind == 1 ? ByteBuffer.allocateDirect(frame.length + 5)
                            : ByteBuffer.allocate(frame.length + 5);
                    input.position(5).put(frame).flip().position(5);
                    if (bufferKind == 2) input = input.asReadOnlyBuffer();
                    decodeFragments(decoder, input, plain, 1 + 6 * repeat);
                }
            }
        }
    }

    /// Preserves the native single-frame prefix lifetime and contrasts it with persistent Java configuration.
    ///
    /// The native reference is required when `ARKIVO_REQUIRE_ZSTD_PREFIX=true`; otherwise this case needs
    /// `ARKIVO_ZSTD_PREFIX_EXECUTABLE`, built by `buildZstdPrefixReference`. Both modes use the same formatted
    /// dictionary bytes: raw refPrefix must ignore their magic and replace the previously loaded dictionary.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nativePrefixLifetime(boolean raw) throws IOException, InterruptedException {
        String executable = prefixExecutable();
        // Preserve zstreamtest.c's ten-MiB scale and force an initial dependency on dictionary history.
        byte[] plain = new byte[10 * 1024 * 1024];
        for (int offset = 0; offset < plain.length; offset += data.length) {
            System.arraycopy(data, 0, plain, offset, data.length);
        }
        byte[] prefixBytes = trainPrefixDictionary(plain);
        ZstdDictionary fullDictionary = ZstdDictionary.fullDictionary(prefixBytes);
        System.arraycopy(prefixBytes, prefixBytes.length - 1024, plain, 0, 1024);
        ZstdDictionary selected = raw ? ZstdDictionary.rawContent(prefixBytes) : fullDictionary;
        ZstdCodec codec = ZstdCodec.DEFAULT.withDictionary(selected).withCompressionLevel(1).withFrameChecksum(true);
        byte[] javaFrame = encode(codec, plain);
        Files.write(directory.resolve("dictionary.bin"), prefixBytes);
        Files.write(directory.resolve("plain.bin"), plain);
        Files.write(directory.resolve("java.zst"), javaFrame);
        runPrefixReference(executable, raw);
        assertArrayEquals(prefixBytes, Files.readAllBytes(directory.resolve("dictionary.bin")));
        assertArrayEquals(plain, Files.readAllBytes(directory.resolve("plain.bin")));
        assertArrayEquals(javaFrame, Files.readAllBytes(directory.resolve("java.zst")));
        byte[] prefixFrame = Files.readAllBytes(directory.resolve("prefix.zst"));
        byte[] independentFrame = Files.readAllBytes(directory.resolve("independent.zst"));
        assertEquals(raw ? 0 : fullDictionary.dictionaryId(),
                ((ZstdStandardFrameInfo) codec.frameInfo(ByteBuffer.wrap(prefixFrame))).dictionaryId());
        assertEquals(0, ((ZstdStandardFrameInfo) codec.frameInfo(ByteBuffer.wrap(independentFrame))).dictionaryId());
        for (int kind = 0; kind < 3; kind++) {
            try (var decoder = codec.newDecoder()) {
                for (byte[] frame : new byte[][]{prefixFrame, prefixFrame, independentFrame}) {
                    decoder.reset();
                    ByteBuffer source = kind == 1 ? ByteBuffer.allocateDirect(frame.length + 5)
                            : ByteBuffer.allocate(frame.length + 5);
                    source.position(5).put(frame).flip().position(5);
                    if (kind == 2) source = source.asReadOnlyBuffer();
                    decodeFragments(decoder, source, plain, 509 + 1024 * kind);
                }
            }
        }
        try (var decoder = ZstdCodec.DEFAULT.newDecoder()) {
            for (int repeat = 0; repeat < 2; repeat++) {
                decoder.reset();
                ByteBuffer source = ByteBuffer.wrap(prefixFrame);
                ByteBuffer output = ByteBuffer.allocate(plain.length + 1);
                if (raw) {
                    // Raw history has no ID: a missing prefix cannot trigger a dictionary request.
                    assertThrows(IOException.class, () -> decoder.finish(source, output));
                } else {
                    assertEquals(CodecOutcome.NEEDS_DICTIONARY, decoder.decode(source, output));
                    assertEquals(fullDictionary.dictionaryId(), decoder.dictionaryRequest().dictionaryId());
                    assertEquals(0, output.position());
                    decoder.provideDictionary(fullDictionary);
                    assertEquals(CodecOutcome.FINISHED, decoder.finish(source, output));
                    assertEquals(prefixFrame.length, source.position());
                    assertArrayEquals(plain, Arrays.copyOf(output.array(), output.position()));
                }
            }
            decoder.reset();
            decodeFragments(decoder, ByteBuffer.wrap(independentFrame), plain, 4093);
        }
    }

    /// Trains from the first third of the ten-MiB input using the upstream 16-KiB samples and 48-KiB capacity.
    private static byte[] trainPrefixDictionary(byte[] plain) {
        int sampleSize = plain.length / 3;
        ZstdDictTrainer trainer = new ZstdDictTrainer(sampleSize, 48 * 1024);
        for (int offset = 0; offset < sampleSize; offset += 16 * 1024) {
            assertTrue(trainer.addSample(Arrays.copyOfRange(plain, offset, Math.min(sampleSize, offset + 16 * 1024))));
        }
        return trainer.trainSamples();
    }

    /// Resolves the native prefix reference without silently skipping a required CI check.
    private static String prefixExecutable() {
        @Nullable String configured = System.getenv("ARKIVO_ZSTD_PREFIX_EXECUTABLE");
        boolean available = configured != null && Files.isRegularFile(Path.of(configured));
        if (Boolean.parseBoolean(System.getenv("ARKIVO_REQUIRE_ZSTD_PREFIX"))) {
            assertTrue(available, "Set ARKIVO_ZSTD_PREFIX_EXECUTABLE to the built official prefix reference");
        }
        assumeTrue(available, "Official prefix reference is not configured");
        return Path.of(Objects.requireNonNull(configured)).toAbsolutePath().toString();
    }

    /// Requires the native prefix assertions and both generated frames to complete within a bounded wait.
    private void runPrefixReference(String executable, boolean raw) throws IOException, InterruptedException {
        Path output = directory.resolve("prefix-reference.out");
        Path errors = directory.resolve("prefix-reference.err");
        Process process = new ProcessBuilder(executable, raw ? "raw" : "full").directory(directory.toFile())
                .redirectOutput(output.toFile()).redirectError(errors.toFile()).start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(60, TimeUnit.SECONDS)) fail("Official prefix reference timed out");
            assertEquals(0, process.exitValue(), Files.readString(errors));
            assertEquals("prefix-consumed independent-frame-verified", Files.readString(output).strip());
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Official prefix reference did not terminate");
            }
        }
    }

    /// Keeps skippable-frame boundaries exact while reusing a configured dictionary for the following frame.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void skippableThenDictionaryFrame(boolean raw) throws IOException {
        byte[] plain = Arrays.copyOf(data, 8192);
        byte[] selectedBytes = raw ? Arrays.copyOf(data, 4096) : dictionaryBytes;
        ZstdDictionary selected = raw ? ZstdDictionary.rawContent(selectedBytes) : dictionary;
        byte[] frame;
        try (var nativeEncoder = new ZstdCompressCtx()) {
            frame = nativeEncoder.loadDict(selectedBytes).compress(plain);
        }
        byte[] combined = new byte[21 + frame.length];
        ByteArrayAccess.writeIntLittleEndian(combined, 0, 0x184d2a50);
        ByteArrayAccess.writeIntLittleEndian(combined, 4, 13);
        System.arraycopy(frame, 0, combined, 21, frame.length);
        ZstdCodec codec = ZstdCodec.DEFAULT.withDictionary(selected);
        try (var decoder = codec.newDecoder()) {
            ByteBuffer source = ByteBuffer.wrap(combined);
            source.limit(0);
            CodecOutcome outcome;
            do {
                source.limit(Math.min(combined.length, source.limit() + 7));
                ByteBuffer target = ByteBuffer.allocate(1);
                outcome = decoder.decode(source, target);
                assertEquals(0, target.position());
            } while (outcome != CodecOutcome.FINISHED);
            assertEquals(21, source.position());
            decoder.reset();
            Random random = new Random(458);
            ByteArrayOutputStream decoded = new ByteArrayOutputStream();
            int attempts = 0;
            do {
                assertTrue(++attempts < 4 * (combined.length + plain.length), "Decoder made no progress");
                int inputSize = random.nextInt(16);
                int outputSize = random.nextInt(16) + (inputSize == 0 ? 1 : 0);
                source.limit(Math.min(combined.length, source.position() + inputSize));
                ByteBuffer output = ByteBuffer.allocate(outputSize);
                outcome = decoder.decode(source, output);
                decoded.write(output.array(), 0, output.position());
            } while (outcome != CodecOutcome.FINISHED);
            assertEquals(combined.length, source.position());
            assertArrayEquals(plain, decoded.toByteArray());
        }
        try (var decoder = codec.withMaximumWindowSize(1024).newDecoder()) {
            assertThrows(DecompressionWindowLimitException.class,
                    () -> decoder.finish(ByteBuffer.wrap(frame), ByteBuffer.allocate(plain.length + 1)));
        }
    }

    /// Preserves the official zero-size pledge distinction with and without a dictionary.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emptySourceSizePromise(boolean withDictionary) throws IOException {
        ZstdCodec codec = withDictionary ? ZstdCodec.DEFAULT.withDictionary(dictionary) : ZstdCodec.DEFAULT;
        for (long size : new long[]{0, -1}) {
            try (var encoder = codec.newEncoder(new EncodingOptions(size))) {
                ByteBuffer frame = ByteBuffer.allocate(128);
                assertEquals(CodecOutcome.NEEDS_INPUT, encoder.encode(ByteBuffer.allocate(0), frame));
                assertEquals(CodecOutcome.FINISHED, encoder.finish(frame));
                frame.flip();
                assertEquals(size, ((ZstdStandardFrameInfo) codec.frameInfo(frame)).contentSize());
                try (var decoder = codec.newDecoder()) {
                    decodeFragments(decoder, frame, new byte[0], 1);
                }
            }
        }
    }

    /// Replays the official six-byte flush sequence followed by long dictionary references.
    @Test
    void tinyBlocksThenDictionaryReferences() throws IOException {
        byte[] tiny = new byte[6];
        Arrays.fill(tiny, (byte) 0xaa);
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        ByteArrayOutputStream javaBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream nativeBytes = new ByteArrayOutputStream();
        ZstdCodec codec = ZstdCodec.DEFAULT.withDictionary(dictionary).withFrameChecksum(true);
        try (var javaStream = codec.newOutputStream(javaBytes);
             var nativeStream = new ZstdOutputStream(nativeBytes).setDict(dictionaryBytes).setChecksum(true)
                     .setCloseFrameOnFlush(false)) {
            for (int remaining = 256 * 1024; remaining > 0; remaining -= tiny.length) {
                plain.write(tiny);
                javaStream.write(tiny);
                javaStream.flush();
                nativeStream.write(tiny);
                nativeStream.flush();
            }
            for (int offset = 1024; offset >= 0; offset -= 128) {
                plain.write(dictionaryBytes, offset, 128);
                javaStream.write(dictionaryBytes, offset, 128);
                nativeStream.write(dictionaryBytes, offset, 128);
            }
        }
        byte[] expected = plain.toByteArray();
        try (var nativeDecoder = new ZstdDecompressCtx()) {
            assertArrayEquals(expected, nativeDecoder.loadDict(dictionaryBytes).decompress(javaBytes.toByteArray(), expected.length));
        }
        try (var decoder = codec.newDecoder()) {
            decodeFragments(decoder, ByteBuffer.wrap(nativeBytes.toByteArray()), expected, 8192);
        }
    }

    /// Preserves the raw-first-block and distant dictionary-reference layout of the offset-table regression.
    @Test
    void rawBlockThenDictionaryReferences() throws IOException {
        byte[] plain = new byte[2 * 128 * 1024];
        new Random(1831).nextBytes(plain);
        for (int offset = 131072 + 256; offset < plain.length - 256; offset += 256) {
            System.arraycopy(plain, 131072, plain, offset, Math.min(256, plain.length - 256 - offset));
        }
        System.arraycopy(dictionaryBytes, 256, plain, plain.length - 256, 128);
        System.arraycopy(dictionaryBytes, 128, plain, plain.length - 128, 128);
        ZstdCodec codec = ZstdCodec.DEFAULT.withDictionary(dictionary).withCompressionLevel(3);
        byte[] nativeFrame;
        try (var nativeEncoder = new ZstdCompressCtx(); var nativeDecoder = new ZstdDecompressCtx()) {
            nativeFrame = nativeEncoder.loadDict(dictionaryBytes).setLevel(3).compress(plain);
            assertArrayEquals(plain, nativeDecoder.loadDict(dictionaryBytes).decompress(encode(codec, plain), plain.length));
        }
        int header = codec.frameInfo(ByteBuffer.wrap(nativeFrame)).headerSize();
        assertEquals(0, (nativeFrame[header] >>> 1) & 3, "First reference block must be raw");
        assertEquals(2, (nativeFrame[header + 3 + 131072] >>> 1) & 3, "Second reference block must be compressed");
        try (var decoder = codec.newDecoder()) {
            decodeFragments(decoder, ByteBuffer.wrap(nativeFrame), plain, 1019);
        }
    }

    /// Encodes a complete pledged frame through the buffer API.
    private static byte[] encode(ZstdCodec codec, byte[] plain) throws IOException {
        try (var encoder = codec.newEncoder(EncodingOptions.ofSourceSize(plain.length))) {
            ByteBuffer input = ByteBuffer.wrap(plain);
            ByteBuffer output = ByteBuffer.allocate(Math.toIntExact(codec.maxCompressedSize(plain.length)) + 64);
            assertEquals(CodecOutcome.NEEDS_INPUT, encoder.encode(input, output));
            assertFalse(input.hasRemaining());
            assertEquals(CodecOutcome.FINISHED, encoder.finish(output));
            return Arrays.copyOf(output.array(), output.position());
        }
    }

    /// Decodes bounded fragments and compares complete output, requiring exact frame consumption.
    private static void decodeFragments(CompressionDecoder decoder, ByteBuffer source, byte[] plain, int chunk)
            throws IOException {
        int end = source.limit();
        source.limit(source.position());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CodecOutcome outcome = CodecOutcome.NEEDS_INPUT;
        int attempts = 0;
        while (outcome != CodecOutcome.FINISHED) {
            assertTrue(++attempts <= end + plain.length + 32, "Decoder made no progress");
            if (!source.hasRemaining()) source.limit(Math.min(end, source.limit() + chunk));
            ByteBuffer target = ByteBuffer.allocate(4093);
            outcome = source.limit() == end ? decoder.finish(source, target) : decoder.decode(source, target);
            assertTrue(outcome != CodecOutcome.NEEDS_DICTIONARY, "Configured dictionary was not retained");
            output.write(target.array(), 0, target.position());
        }
        assertEquals(end, source.position());
        assertArrayEquals(plain, output.toByteArray());
    }

    /// Creates mixed noise and repeated 4-KiB regions without platform-dependent randomness.
    private static byte[] testData() {
        byte[] bytes = new byte[1024 * 1024];
        new Random(0x87654321L).nextBytes(bytes);
        for (int offset = 4096; offset < bytes.length; offset += 8192) {
            System.arraycopy(bytes, offset - 4096, bytes, offset, 4096);
        }
        return bytes;
    }

    /// Trains a 4-KiB reference dictionary from deterministic 2-KiB samples.
    private static byte[] trainDictionary(byte[] data) {
        ZstdDictTrainer trainer = new ZstdDictTrainer(256 * 1024, 4096);
        for (int offset = 0; offset < 256 * 1024; offset += 2048) {
            assertTrue(trainer.addSample(Arrays.copyOfRange(data, offset, offset + 2048)));
        }
        return trainer.trainSamples();
    }
}
