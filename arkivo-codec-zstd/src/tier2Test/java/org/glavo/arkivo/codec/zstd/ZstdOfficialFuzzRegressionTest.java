// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import com.github.luben.zstd.ZstdException;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.zstd.internal.ZstdFrameHeader;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Replays bounded inputs through assertions adapted from Zstandard 1.5.7's fuzz targets.
///
/// Input tails encode parameters using fuzz_data_producer.c's byte order and range reduction. These finite
/// regressions do not replace coverage-guided fuzzing or require agreement on native error codes.
@NotNullByDefault
@Timeout(120)
final class ZstdOfficialFuzzRegressionTest {
    /// Limits malformed headers independently of the fuzz input's output-capacity parameter.
    private static final ZstdCodec CODEC = ZstdCodec.DEFAULT.withMaximumWindowSize(8L << 20)
            .withMaximumMemorySize(64L << 20).withMaximumOutputSize(2L << 20);

    /// Verifies tail consumption, unsigned range reduction, exhaustion and prefix partitioning.
    @Test
    void inputParameterLayout() {
        ZstdFuzzDataProducer input = new ZstdFuzzDataProducer(new byte[]{1, 2, 3, 4});
        assertEquals(0x04030201L, input.range(0, 0xffffffffL));
        assertEquals(17, input.range(17, 33));
        input = new ZstdFuzzDataProducer(new byte[]{7, 8, 9});
        assertEquals(5, input.range(5, 5));
        assertEquals(9, input.range(0, 255));
        assertEquals(8 % 3, input.range(0, 2));
        input = new ZstdFuzzDataProducer(new byte[]{11, 12, 13, 14, 2});
        assertEquals(2, input.reservePrefix());
        assertEquals(14, input.range(0, 255));
        assertEquals(13, input.range(0, 255));
        assertEquals(0, input.range(0, 255));
        input = new ZstdFuzzDataProducer(new byte[0]);
        assertEquals(0, input.reservePrefix());
        assertEquals(0, input.range(0, 0xffffffffL));
    }

    /// Compares standard and magicless acceptance and output for valid, corrupt and capacity-limited frames.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void crossFormatAcceptance(int bufferKind) throws IOException {
        Random random = new Random(0xc2055L);
        int exercised = 0;
        for (int size : new int[]{0, 1, 39, 40, 41, 4096, 131073}) {
            for (boolean checksum : new boolean[]{false, true}) {
                byte[] plain = new byte[size];
                random.nextBytes(plain);
                for (int offset = 64; offset < size; offset++) plain[offset] = plain[offset % 64];
                byte[] frame;
                try (var encoder = new ZstdCompressCtx()) {
                    frame = encoder.setChecksum(checksum).setContentSize(true).compress(plain);
                }
                byte[] magicless = Arrays.copyOfRange(frame, 4, frame.length);
                for (int capacity : new int[]{0, Math.max(0, size - 1), size, size + 1}) {
                    assertTrue(replayCrossFormat(inputFor(magicless, capacity), bufferKind));
                    exercised++;
                }
                // A second frame must not change this target's first-frame-only comparison.
                byte[] concatenated = Arrays.copyOf(magicless, magicless.length * 2);
                System.arraycopy(magicless, 0, concatenated, magicless.length, magicless.length);
                assertTrue(replayCrossFormat(inputFor(concatenated, size + 1), bufferKind));
                for (int index = 0; index < Math.min(magicless.length, 64); index++) {
                    byte[] mutated = magicless.clone();
                    mutated[index] ^= (byte) (1 << (index & 7));
                    if (replayCrossFormat(inputFor(mutated, size + 1), bufferKind)) exercised++;
                }
                assertFalse(replayCrossFormat(inputFor(Arrays.copyOf(magicless, magicless.length - 1), size), bufferKind));
                ByteBuffer output = ByteBuffer.allocate(size);
                CODEC.withFrameFormat(ZstdFrameFormat.MAGICLESS).decompress(ByteBuffer.wrap(magicless), output);
                assertArrayEquals(plain, output.array());
            }
        }
        for (int size = 0; size < 256; size++) {
            byte[] input = new byte[size];
            random.nextBytes(input);
            if (replayCrossFormat(input, bufferKind)) exercised++;
        }
        assertTrue(exercised > 100, "Too few structurally complete frames reached the decoder comparison");
    }

    /// Maps decompress_dstSize_tooSmall.c's exact input layout to buffer overflow and resumable engine output.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void insufficientOutputCapacity(int bufferKind) throws IOException {
        Random random = new Random(0xd57512eL);
        try (var nativeEncoder = new ZstdCompressCtx(); var nativeDecoder = new ZstdDecompressCtx()) {
            nativeEncoder.setLevel(1);
            for (int size : new int[]{0, 1, 2, 3, 4, 7, 8, 9, 255, 256, 257, 4096, 131073}) {
                for (int pattern = 0; pattern < 3; pattern++) {
                    byte[] data = new byte[size];
                    if (pattern == 0) random.nextBytes(data);
                    if (pattern == 2) Arrays.fill(data, (byte) 0xff);
                    ZstdFuzzDataProducer input = new ZstdFuzzDataProducer(data);
                    int requested = Math.toIntExact(input.range(0, size));
                    byte[] plain = Arrays.copyOf(data, input.remainingBytes());
                    int capacity = requested >= plain.length ? Math.max(0, plain.length - 1) : requested;
                    byte[] frame = nativeEncoder.compress(plain);
                    ByteBuffer source = source(frame, bufferKind);
                    ByteBuffer target = bufferKind == 1 ? ByteBuffer.allocateDirect(capacity) : ByteBuffer.allocate(capacity);
                    if (plain.length == 0) {
                        assertArrayEquals(plain, nativeDecoder.decompress(frame, capacity));
                        CODEC.decompress(source, target);
                        assertEquals(0, target.position());
                    } else {
                        ZstdException failure = assertThrows(ZstdException.class,
                                () -> nativeDecoder.decompress(frame, capacity));
                        assertEquals(Zstd.errDstSizeTooSmall(), failure.getErrorCode());
                        assertThrows(BufferOverflowException.class, () -> CODEC.decompress(source, target));
                        assertEquals(capacity, target.limit());
                        assertEquals(frame.length, source.limit() - 3);
                        try (var decoder = CODEC.newDecoder()) {
                            ByteBuffer resumedSource = source(frame, bufferKind);
                            target.clear();
                            assertEquals(CodecOutcome.NEEDS_OUTPUT, decoder.decode(resumedSource, target));
                            ByteBuffer remainder = ByteBuffer.allocate(plain.length - target.position());
                            assertEquals(CodecOutcome.FINISHED, decoder.finish(resumedSource, remainder));
                            byte[] actual = new byte[plain.length];
                            int first = target.position();
                            target.flip().get(actual, 0, first);
                            remainder.flip().get(actual, first, remainder.remaining());
                            assertArrayEquals(plain, actual);
                            assertFalse(resumedSource.hasRemaining());
                        }
                    }
                }
            }
        }
    }

    /// Exercises the applicable zstd_frame_info.c helpers with complete, truncated and mutated frame structures.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void frameInformationOnArbitraryInput(int bufferKind) throws IOException {
        for (byte[] frame : decoderFrames()) {
            inspectFrame(frame, bufferKind, true);
            for (int end = 0; end < Math.min(frame.length, 22); end++) {
                inspectFrame(Arrays.copyOf(frame, end), bufferKind, false);
            }
            inspectFrame(Arrays.copyOf(frame, frame.length - 1), bufferKind, false);
            for (int offset = 0; offset < Math.min(frame.length, 22); offset++) {
                for (int bit = 0; bit < 8; bit++) {
                    byte[] changed = frame.clone();
                    changed[offset] ^= (byte) (1 << bit);
                    inspectFrame(changed, bufferKind, false);
                }
            }
        }
        Random random = new Random(0xf2a6e1f0L);
        for (int length = 0; length < 256; length++) {
            byte[] bytes = new byte[length];
            random.nextBytes(bytes);
            inspectFrame(bytes, bufferKind, false);
        }
    }

    /// Preserves simple_decompress.c's input partition and successful-output size invariant, including concatenation.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void simpleDecompressionAndDeclaredSizes(int bufferKind) throws IOException {
        int successful = 0;
        for (byte[] frame : decoderFrames()) {
            int capacity = Math.min(2 << 20, frame.length * 10);
            if (replaySimpleDecompression(simpleInputFor(frame, capacity), bufferKind)) successful++;
            replaySimpleDecompression(simpleInputFor(frame, 0), bufferKind);
            replaySimpleDecompression(simpleInputFor(frame, capacity / 2), bufferKind);
            for (int end = 0; end < Math.min(frame.length, 22); end++) {
                byte[] prefix = Arrays.copyOf(frame, end);
                replaySimpleDecompression(simpleInputFor(prefix, prefix.length * 10), bufferKind);
            }
            byte[] truncated = Arrays.copyOf(frame, frame.length - 1);
            assertFalse(replaySimpleDecompression(simpleInputFor(truncated, Math.min(capacity, truncated.length * 10)), bufferKind));
            for (int offset = 0; offset < Math.min(frame.length, 22); offset++) {
                byte[] changed = frame.clone();
                changed[offset] ^= (byte) (1 << (offset & 7));
                replaySimpleDecompression(simpleInputFor(changed, capacity), bufferKind);
            }
        }
        Random random = new Random(0x51deL);
        for (int length = 0; length < 256; length++) {
            byte[] input = new byte[length];
            random.nextBytes(input);
            replaySimpleDecompression(input, bufferKind);
        }
        assertTrue(successful >= 24, "Valid seeds did not reach successful decompression");
    }

    /// Replays stream_decompress.c's variable input/output partitions and fixed-output mode across session resets.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void streamingDecompressionSequences(int bufferKind) throws IOException {
        Random random = new Random(0x57deaL);
        try (var decoder = CODEC.newDecoder(); var nativeDecoder = new ZstdDecompressCtx()) {
            for (byte[] frame : decoderFrames()) {
                byte[] expected = nativeDecoder.decompress(frame, 2 << 20);
                for (boolean stable : new boolean[]{false, true}) {
                    for (int seed = 0; seed < 4; seed++) {
                        byte[] input = streamingInputFor(frame, stable, seed);
                        StreamResult result = replayStreamDecompression(decoder, input, bufferKind);
                        assertFalse(result.failed());
                        assertTrue(result.output().length <= expected.length);
                        assertArrayEquals(Arrays.copyOf(expected, result.output().length), result.output());
                        if (result.finished()) assertArrayEquals(expected, result.output());
                        // The zero-seed case exhausts parameters immediately and supplies full input and output.
                        if (seed == 0 && expected.length < Math.max(131072, frame.length * 10)) {
                            assertTrue(result.finished(), "Complete input and adequate output must finish");
                        }
                    }
                }
                for (int offset = 0; offset < Math.min(frame.length, 16); offset++) {
                    byte[] changed = frame.clone();
                    changed[offset] ^= (byte) (1 << (offset & 7));
                    replayStreamDecompression(decoder, streamingInputFor(changed, false, 3), bufferKind);
                }
            }
            for (int size = 0; size < 256; size++) {
                byte[] input = new byte[size];
                random.nextBytes(input);
                replayStreamDecompression(decoder, input, bufferKind);
            }
        }
    }

    /// Adapts the native streaming loop without adding finalization when the supplied input fragments run out.
    private static StreamResult replayStreamDecompression(CompressionDecoder decoder, byte[] data, int kind)
            throws IOException {
        decoder.reset();
        ZstdFuzzDataProducer parameters = new ZstdFuzzDataProducer(data);
        int payloadSize = parameters.reservePrefix();
        int capacity = Math.max(131072, payloadSize * 10);
        boolean stable = parameters.range(0, 10) == 5;
        ByteBuffer output = kind == 1 ? ByteBuffer.allocateDirect(capacity) : ByteBuffer.allocate(capacity);
        output.limit(stable ? capacity : nextBufferSize(parameters, capacity));
        ByteBuffer source = source(Arrays.copyOf(data, payloadSize), kind);
        ByteArrayOutputStream produced = new ByteArrayOutputStream();
        boolean finished = payloadSize == 0;
        boolean failed = false;
        int offset = 0;
        int attempts = 0;
        outer: while (offset < payloadSize) {
            int chunk = nextBufferSize(parameters, payloadSize - offset);
            source.limit(3 + offset + chunk);
            offset += chunk;
            do {
                assertTrue(++attempts <= 4L * data.length + (2 << 20), "Streaming decoder made no progress");
                CodecOutcome outcome;
                try {
                    outcome = decoder.decode(source, output);
                } catch (IOException malformed) {
                    failed = true;
                    break outer;
                }
                if (outcome == CodecOutcome.NEEDS_DICTIONARY) {
                    failed = true;
                    break outer;
                }
                finished = outcome == CodecOutcome.FINISHED && source.position() == payloadSize + 3;
                if (outcome == CodecOutcome.FINISHED) decoder.reset();
                if (!output.hasRemaining()) {
                    if (stable) break outer;
                    appendOutput(output, produced);
                    if (produced.size() >= (2 << 20)) break outer;
                    output.clear().limit(nextBufferSize(parameters, capacity));
                }
            } while (source.hasRemaining());
        }
        appendOutput(output, produced);
        assertTrue(source.position() >= 3 && source.position() <= payloadSize + 3);
        byte[] unchanged = new byte[payloadSize];
        source.duplicate().limit(payloadSize + 3).position(3).get(unchanged);
        assertArrayEquals(Arrays.copyOf(data, payloadSize), unchanged);
        return new StreamResult(failed, finished, produced.toByteArray());
    }

    /// Selects a possibly empty fragment, using the complete remaining capacity once parameters are exhausted.
    private static int nextBufferSize(ZstdFuzzDataProducer parameters, int maximum) {
        return parameters.remainingBytes() == 0 ? maximum : Math.toIntExact(parameters.range(0, maximum));
    }

    /// Copies completed output before reusing its storage and clears its consumed position.
    private static void appendOutput(ByteBuffer output, ByteArrayOutputStream produced) {
        byte[] bytes = new byte[output.position()];
        output.flip().get(bytes);
        produced.writeBytes(bytes);
        output.clear();
    }

    /// Encodes a fixed or rotating-output mode with either exhausted or randomized subsequent parameters.
    private static byte[] streamingInputFor(byte[] payload, boolean stable, int seed) {
        int parameterSize = seed == 0 ? 1 : 64;
        int total = payload.length + parameterSize + 4;
        int width;
        do {
            width = byteWidth(total);
            int next = payload.length + parameterSize + width;
            if (next == total) break;
            total = next;
        } while (true);
        byte[] input = new byte[total];
        new Random(seed).nextBytes(input);
        System.arraycopy(payload, 0, input, 0, payload.length);
        int end = writeTail(input, input.length, parameterSize, width);
        end = writeTail(input, end, stable ? 5 : 0, 1);
        if (seed != 0 && !stable) {
            // Exercise an empty initial output without changing how later fragments consume the parameter tail.
            writeTail(input, end, seed == 1 ? 0 : 7, byteWidth(Math.max(131072, 10L * payload.length)));
        }
        ZstdFuzzDataProducer check = new ZstdFuzzDataProducer(input);
        assertEquals(payload.length, check.reservePrefix());
        assertEquals(stable ? 5 : 0, check.range(0, 10));
        return input;
    }

    /// Runs each exposed frame helper independently and requires valid seed metadata to match the native oracle.
    private static void inspectFrame(byte[] bytes, int kind, boolean valid) throws IOException {
        ByteBuffer input = source(bytes, kind);
        int limit = input.limit();
        input.mark();
        boolean matches = ZstdFormat.instance().matches(input);
        if (valid) assertTrue(matches);
        assertEquals(3, input.position());
        assertEquals(limit, input.limit());
        try {
            ZstdFrameInfo info = CODEC.frameInfo(input);
            assertTrue(info.headerSize() <= bytes.length);
            if (valid && info instanceof ZstdStandardFrameInfo standard) {
                assertEquals(Zstd.getFrameContentSize(bytes), standard.contentSize());
                assertEquals(Zstd.getDictIdFromFrame(bytes), standard.dictionaryId());
                assertEquals(standard.windowSize(), ZstdFrameHeader.requiredWindowSize(input));
                byte[] magicless = Arrays.copyOfRange(bytes, 4, bytes.length);
                ZstdStandardFrameInfo withoutMagic = (ZstdStandardFrameInfo) ZstdFrameFormat.MAGICLESS
                        .frameInfo(source(magicless, kind));
                assertEquals(standard.headerSize() - 4, withoutMagic.headerSize());
                assertEquals(standard.contentSize(), withoutMagic.contentSize());
                assertEquals(standard.dictionaryId(), withoutMagic.dictionaryId());
                assertEquals(standard.windowSize(), withoutMagic.windowSize());
                assertEquals(standard.checksum(), withoutMagic.checksum());
            }
        } catch (IOException malformed) {
            if (valid) throw malformed;
        }
        assertEquals(3, input.position());
        assertEquals(limit, input.limit());
        try {
            long compressedSize = CODEC.frameCompressedSize(input);
            assertTrue(compressedSize > 0 && compressedSize <= bytes.length);
            if (valid) assertEquals(Zstd.findFrameCompressedSize(bytes), compressedSize);
        } catch (IOException malformed) {
            if (valid) throw malformed;
        }
        long window = ZstdFrameHeader.requiredWindowSize(input);
        assertTrue(window >= ZstdFrameHeader.NEED_MORE_INPUT);
        assertEquals(3, input.position());
        assertEquals(limit, input.limit());
        input.reset();
        byte[] unchanged = new byte[bytes.length];
        input.get(unchanged);
        assertArrayEquals(bytes, unchanged);
    }

    /// Checks a one-shot decode without treating unchecked exceptions as valid malformed-input outcomes.
    private static boolean replaySimpleDecompression(byte[] data, int kind) throws IOException {
        ZstdFuzzDataProducer input = new ZstdFuzzDataProducer(data);
        int payloadSize = input.reservePrefix();
        int capacity = Math.toIntExact(input.range(0, 10L * payloadSize));
        byte[] frame = Arrays.copyOf(data, payloadSize);
        ByteBuffer source = source(frame, kind);
        ByteBuffer target = kind == 1 ? ByteBuffer.allocateDirect(capacity + 6) : ByteBuffer.allocate(capacity + 6);
        target.put(0, (byte) 0x5a).put(capacity + 5, (byte) 0xa5);
        target.position(3).limit(capacity + 3);
        boolean accepted;
        try {
            CODEC.decompress(source, target);
            accepted = true;
        } catch (IOException | BufferOverflowException malformedOrTooSmall) {
            accepted = false;
        }
        assertEquals(frame.length + 3, source.limit());
        assertTrue(source.position() >= 3 && source.position() <= source.limit());
        assertEquals(capacity + 3, target.limit());
        assertTrue(target.position() >= 3 && target.position() <= target.limit());
        ByteBuffer guards = target.duplicate().clear();
        assertEquals((byte) 0x5a, guards.get(0));
        assertEquals((byte) 0xa5, guards.get(capacity + 5));
        assertEquals(0, guards.get(1));
        assertEquals(0, guards.get(2));
        assertEquals(0, guards.get(capacity + 3));
        assertEquals(0, guards.get(capacity + 4));
        byte[] unchanged = new byte[frame.length];
        source.duplicate().position(3).get(unchanged);
        assertArrayEquals(frame, unchanged);
        if (!accepted) return false;
        assertFalse(source.hasRemaining());
        byte[] output = new byte[target.position() - 3];
        target.flip().position(3).get(output);
        long declaredSize = 0;
        boolean known = true;
        for (int offset = 0; offset < frame.length;) {
            int compressedSize = Math.toIntExact(Zstd.findFrameCompressedSize(frame, offset, frame.length - offset));
            assertTrue(compressedSize > 0);
            long contentSize = Zstd.getFrameContentSize(frame, offset, compressedSize);
            assertTrue(contentSize >= CompressionCodec.UNKNOWN_SIZE, "A successfully decoded frame has an invalid header");
            known &= contentSize != CompressionCodec.UNKNOWN_SIZE;
            if (contentSize >= 0) declaredSize = Math.addExact(declaredSize, contentSize);
            offset += compressedSize;
        }
        if (known) assertEquals(declaredSize, output.length);
        try (var nativeDecoder = new ZstdDecompressCtx()) {
            assertArrayEquals(output, nativeDecoder.decompress(frame, capacity));
        }
        return true;
    }

    /// Provides native frames, all official golden decode frames, every skippable ID and concatenated frame layouts.
    private static @Unmodifiable List<byte @Unmodifiable []> decoderFrames() throws IOException {
        List<byte[]> frames = new ArrayList<>();
        Random random = new Random(0xdec0deL);
        try (var encoder = new ZstdCompressCtx()) {
            for (int size : new int[]{0, 1, 255, 256, 4096, 131073}) {
                byte[] plain = new byte[size];
                random.nextBytes(plain);
                for (boolean contentSize : new boolean[]{false, true}) {
                    for (boolean checksum : new boolean[]{false, true}) {
                        frames.add(encoder.setContentSize(contentSize).setChecksum(checksum).compress(plain));
                    }
                }
            }
        }
        Path root = Path.of(Objects.requireNonNull(System.getProperty("arkivo.zstd.testDataDirectory")));
        for (String name : List.of("block-128k.zst", "empty-block.zst", "rle-first-block.zst", "zeroSeq_2B.zst")) {
            frames.add(Files.readAllBytes(root.resolve("tests/golden-decompression").resolve(name)));
        }
        for (int id = 0; id < 16; id++) {
            byte[] skippable = new byte[8 + id];
            random.nextBytes(skippable);
            ByteArrayAccess.writeIntLittleEndian(skippable, 0, 0x184d2a50 + id);
            ByteArrayAccess.writeIntLittleEndian(skippable, 4, id);
            frames.add(skippable);
        }
        // Include known/unknown sizes and skippable data between ordinary frames.
        byte[] first = frames.get(7);
        byte[] second = frames.get(8);
        byte[] skip = frames.get(frames.size() - 1);
        byte[] joined = new byte[first.length + skip.length + second.length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(skip, 0, joined, first.length, skip.length);
        System.arraycopy(second, 0, joined, first.length + skip.length, second.length);
        frames.add(joined);
        return List.copyOf(frames);
    }

    /// Encodes simple_decompress.c's capacity parameter, whose range depends on payload rather than whole-input size.
    private static byte[] simpleInputFor(byte[] payload, int capacity) {
        assertTrue(capacity <= 10L * payload.length);
        byte[] input = inputFor(payload, capacity);
        writeTail(input, input.length - byteWidth(input.length), capacity, byteWidth(10L * payload.length));
        ZstdFuzzDataProducer check = new ZstdFuzzDataProducer(input);
        assertEquals(payload.length, check.reservePrefix());
        assertEquals(capacity, check.range(0, 10L * payload.length));
        return input;
    }

    /// Applies decompress_cross_format.c's partitioning and first-frame scan before both decoding paths.
    private static boolean replayCrossFormat(byte[] data, int bufferKind) throws IOException {
        ZstdFuzzDataProducer input = new ZstdFuzzDataProducer(data);
        int payloadSize = input.reservePrefix();
        int capacity = Math.toIntExact(input.range(0, 10L * data.length));
        byte[] standard = new byte[4 + payloadSize];
        ByteArrayAccess.writeIntLittleEndian(standard, 0, 0xfd2fb528);
        System.arraycopy(data, 0, standard, 4, payloadSize);
        long scanned;
        try {
            scanned = Zstd.findFrameCompressedSize(standard);
        } catch (ZstdException invalidFrame) {
            return false;
        }
        if (Zstd.isError(scanned)) return false;
        standard = Arrays.copyOf(standard, Math.toIntExact(scanned));
        byte[] magicless = Arrays.copyOfRange(standard, 4, standard.length);
        for (boolean streaming : new boolean[]{false, true}) {
            Result a = decode(standard, capacity, bufferKind, streaming, ZstdFrameFormat.STANDARD);
            Result b = decode(magicless, capacity, bufferKind, streaming, ZstdFrameFormat.MAGICLESS);
            assertEquals(a.accepted(), b.accepted(), "Physical format changed acceptance");
            if (a.accepted()) assertArrayEquals(a.output(), b.output(), "Physical format changed output");
        }
        return true;
    }

    /// Captures one-shot success or one incremental call's completed-frame result without hiding runtime failures.
    private static Result decode(byte[] frame, int capacity, int kind, boolean streaming, ZstdFrameFormat format)
            throws IOException {
        ByteBuffer source = source(frame, kind);
        ByteBuffer output = kind == 1 ? ByteBuffer.allocateDirect(capacity) : ByteBuffer.allocate(capacity);
        ZstdCodec codec = CODEC.withFrameFormat(format);
        boolean accepted;
        try {
            if (streaming) {
                try (var decoder = codec.newDecoder()) {
                    accepted = decoder.decode(source, output) == CodecOutcome.FINISHED;
                }
            } else {
                codec.decompress(source, output);
                accepted = true;
            }
        } catch (IOException expected) {
            accepted = false;
        } catch (BufferOverflowException expected) {
            if (streaming) throw expected;
            accepted = false;
        }
        byte[] bytes = new byte[output.position()];
        output.flip().get(bytes);
        return new Result(accepted, bytes);
    }

    /// Places a complete frame at a nonzero position in a heap, direct or read-only buffer.
    private static ByteBuffer source(byte[] frame, int kind) {
        ByteBuffer buffer = kind == 1 ? ByteBuffer.allocateDirect(frame.length + 6) : ByteBuffer.allocate(frame.length + 6);
        buffer.position(3).put(frame).flip().position(3);
        return kind == 2 ? buffer.asReadOnlyBuffer() : buffer;
    }

    /// Encodes a payload with a parameter tail selecting its complete length and the requested capacity.
    private static byte[] inputFor(byte[] payload, int capacity) {
        // Keep enough parameter bytes for the target's output bound of ten times the complete input size.
        byte[] input = Arrays.copyOf(payload, Math.max(payload.length + 8, (capacity + 9) / 10));
        int cursor = input.length;
        int prefixWidth = byteWidth(input.length);
        cursor = writeTail(input, cursor, input.length - payload.length - prefixWidth, prefixWidth);
        writeTail(input, cursor, capacity, byteWidth(10L * input.length));
        ZstdFuzzDataProducer check = new ZstdFuzzDataProducer(input);
        assertEquals(payload.length, check.reservePrefix());
        assertEquals(capacity, check.range(0, 10L * input.length));
        return input;
    }

    /// Writes the inverse of the producer's tail-first, most-significant-byte-first integer read.
    private static int writeTail(byte[] target, int end, long value, int width) {
        for (int index = 0; index < width; index++) {
            target[end - width + index] = (byte) (value >>> (8 * index));
        }
        return end - width;
    }

    /// Returns the number of bytes consumed for an inclusive range starting at zero.
    private static int byteWidth(long range) {
        int width = 0;
        while (range != 0) {
            width++;
            range >>>= 8;
        }
        return width;
    }

    /// Stores the completion flag and bytes produced by one decoding path.
    ///
    /// @param accepted whether decoding completed without an error
    /// @param output the produced bytes
    @NotNullByDefault
    private record Result(boolean accepted, byte @Unmodifiable [] output) {
    }

    /// Stores the streaming target's stop state and all bytes produced before it stopped.
    ///
    /// @param failed whether malformed input or an unavailable dictionary ended the loop
    /// @param finished whether the final supplied frame finished
    /// @param output the produced prefix
    @NotNullByDefault
    private record StreamResult(boolean failed, boolean finished, byte @Unmodifiable [] output) {
    }

}
