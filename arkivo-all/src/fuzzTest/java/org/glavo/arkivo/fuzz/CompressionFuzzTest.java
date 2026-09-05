// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.EncodingOptions;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/// Fuzzes malformed-input handling and round-trip invariants across every installed compression codec.
@NotNullByDefault
public final class CompressionFuzzTest {
    /// The number of control bytes preceding a decoder payload.
    private static final int DECODER_HEADER_SIZE = 6;

    /// The number of control bytes preceding an encoder state-machine payload.
    private static final int ENCODER_STATE_HEADER_SIZE = 6;

    /// Creates a compression fuzz-test instance for JUnit.
    public CompressionFuzzTest() {
    }

    /// Verifies that every generated compressed seed completes incremental decoding with its original content.
    @Test
    void generatedDecoderSeedsReachIncrementalCompletion() throws IOException {
        for (int index = 0; index < FuzzSupport.COMPRESSION_FORMATS.size(); index++) {
            byte @Unmodifiable [] seed = compressionDecoderSeed(index);
            CompressionCodec<?> codec = FuzzSupport.boundedCodec(
                    FuzzSupport.COMPRESSION_FORMATS.get(index).defaultCodec(),
                    FuzzSupport.SEED_CONTENT.length
            );
            byte @Unmodifiable [] decoded = runDecoder(
                    codec,
                    seed,
                    1 + (Byte.toUnsignedInt(seed[3]) & 0x3f),
                    1 + (Byte.toUnsignedInt(seed[4]) & 0x3f),
                    (seed[5] & 1) != 0,
                    (seed[5] & 2) != 0
            );
            if (!Arrays.equals(FuzzSupport.SEED_CONTENT, decoded)) {
                throw new AssertionError(
                        "Generated " + FuzzSupport.COMPRESSION_FORMATS.get(index).name()
                                + " decoder seed changed incremental output"
                );
            }
        }
    }

    /// Fuzzes incremental decoder behavior under arbitrary input and output chunk boundaries.
    ///
    /// Checked decoding failures are expected for malformed data. Unchecked failures, hangs, invalid outcomes, and
    /// violations of the progress contract remain Jazzer findings.
    ///
    /// @param data control bytes followed by an arbitrary compressed payload
    @MethodSource("compressionDecoderSeeds")
    @FuzzTest(maxDuration = "1m")
    void fuzzCompressionDecoder(byte @Unmodifiable [] data) {
        if (data.length < DECODER_HEADER_SIZE
                || data.length > DECODER_HEADER_SIZE + FuzzSupport.MAX_PARSER_INPUT_SIZE) {
            return;
        }

        int selector = Byte.toUnsignedInt(data[0]);
        int decodedSize = Byte.toUnsignedInt(data[1]) | Byte.toUnsignedInt(data[2]) << Byte.SIZE;
        int sourceChunkSize = 1 + (Byte.toUnsignedInt(data[3]) & 0x3f);
        int targetChunkSize = 1 + (Byte.toUnsignedInt(data[4]) & 0x3f);
        boolean directSource = (data[5] & 1) != 0;
        boolean directTarget = (data[5] & 2) != 0;
        CompressionCodec<?> codec = FuzzSupport.boundedCodec(
                FuzzSupport.compressionFormat(selector).defaultCodec(),
                decodedSize
        );

        try {
            runDecoder(
                    codec,
                    data,
                    sourceChunkSize,
                    targetChunkSize,
                    directSource,
                    directTarget
            );
        } catch (IOException expectedMalformedInput) {
            // Invalid, truncated, over-limit, and unsupported dictionary inputs are normal fuzz outcomes.
        }
    }

    /// Fuzzes the invariant that every codec decodes its own complete output back to the original bytes.
    ///
    /// @param data a codec selector followed by the uncompressed payload
    /// @throws IOException if a valid round trip unexpectedly fails
    @MethodSource("compressionRoundTripSeeds")
    @FuzzTest(maxDuration = "1m")
    void fuzzCompressionRoundTrip(byte @Unmodifiable [] data) throws IOException {
        if (data.length == 0 || data.length > 1 + FuzzSupport.MAX_ROUND_TRIP_INPUT_SIZE) {
            return;
        }

        byte[] expected = Arrays.copyOfRange(data, 1, data.length);
        CompressionCodec<?> codec = FuzzSupport.boundedCodec(
                FuzzSupport.compressionFormat(Byte.toUnsignedInt(data[0])).defaultCodec(),
                expected.length
        );
        ByteBuffer compressed = codec.compress(ByteBuffer.wrap(expected));
        ByteBuffer decoded = codec.decompress(compressed);

        if (compressed.hasRemaining()) {
            throw new AssertionError("Round-trip decoder left compressed bytes unconsumed");
        }
        if (decoded.remaining() != expected.length) {
            throw new AssertionError(
                    "Round-trip decoded " + decoded.remaining() + " bytes instead of " + expected.length
            );
        }
        for (byte expectedByte : expected) {
            if (decoded.get() != expectedByte) {
                throw new AssertionError("Round-trip output differs from its source");
            }
        }
    }

    /// Fuzzes legal incremental encoder transitions, flushing, frame boundaries, terminal finish, and reset.
    ///
    /// The generated encoding is decoded through the same immutable codec and must reproduce the complete source.
    /// Framed encoders always terminate each generated frame separately before terminal finish, including empty frames.
    ///
    /// @param data state controls followed by the uncompressed payload
    /// @throws IOException if a valid generated transition sequence or its round trip unexpectedly fails
    @MethodSource("compressionEncoderStateSeeds")
    @FuzzTest(maxDuration = "1m")
    void fuzzCompressionEncoderState(byte @Unmodifiable [] data) throws IOException {
        if (data.length < ENCODER_STATE_HEADER_SIZE
                || data.length > ENCODER_STATE_HEADER_SIZE + FuzzSupport.MAX_ROUND_TRIP_INPUT_SIZE) {
            return;
        }

        byte[] expected = Arrays.copyOfRange(data, ENCODER_STATE_HEADER_SIZE, data.length);
        int sourceChunkSize = 1 + (Byte.toUnsignedInt(data[1]) & 0x3f);
        int targetChunkSize = 1 + (Byte.toUnsignedInt(data[2]) & 0x3f);
        boolean directSource = (data[3] & 1) != 0;
        boolean directTarget = (data[3] & 2) != 0;
        boolean flushBetweenChunks = (data[3] & 4) != 0;
        boolean resetBeforeMainEncoding = (data[3] & 8) != 0;
        boolean verifyReuseAfterFinish = (data[3] & 16) != 0;
        int requestedFrameCount = 1 + (Byte.toUnsignedInt(data[4]) & 3);
        CompressionCodec<?> codec = FuzzSupport.boundedCodec(
                FuzzSupport.compressionFormat(Byte.toUnsignedInt(data[0])).defaultCodec(),
                expected.length
        );

        int initialFrameSize = codec instanceof CompressionCodec.Framed<?>
                ? frameSize(expected.length, requestedFrameCount, 0)
                : expected.length;
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (CompressionEncoder encoder = codec.newEncoder(EncodingOptions.ofSourceSize(initialFrameSize))) {
            if (resetBeforeMainEncoding && initialFrameSize > 0) {
                int abandonedSize = 1 + Math.floorMod(Byte.toUnsignedInt(data[5]), initialFrameSize);
                encodeRange(
                        encoder,
                        expected,
                        0,
                        abandonedSize,
                        sourceChunkSize,
                        targetChunkSize,
                        directSource,
                        directTarget,
                        false,
                        compressed
                );
                encoder.reset();
                compressed.reset();
            }

            encodeCompleteSession(
                    encoder,
                    expected,
                    requestedFrameCount,
                    sourceChunkSize,
                    targetChunkSize,
                    directSource,
                    directTarget,
                    flushBetweenChunks,
                    compressed
            );
            assertRoundTrip(codec, expected, compressed.toByteArray());

            if (verifyReuseAfterFinish) {
                encoder.reset();
                compressed.reset();
                byte[] reusedExpected = codec instanceof CompressionCodec.Framed<?>
                        ? Arrays.copyOf(expected, initialFrameSize)
                        : expected;
                encodeSingleStream(
                        encoder,
                        reusedExpected,
                        sourceChunkSize,
                        targetChunkSize,
                        directSource,
                        directTarget,
                        compressed
                );
                assertRoundTrip(codec, reusedExpected, compressed.toByteArray());
            }
        }
    }

    /// Encodes one complete session using every capability exposed by the selected encoder.
    private static void encodeCompleteSession(
            CompressionEncoder encoder,
            byte @Unmodifiable [] source,
            int requestedFrameCount,
            int sourceChunkSize,
            int targetChunkSize,
            boolean directSource,
            boolean directTarget,
            boolean flushBetweenChunks,
            ByteArrayOutputStream compressed
    ) throws IOException {
        if (!(encoder instanceof CompressionEncoder.Framed framedEncoder)) {
            encodeRange(
                    encoder,
                    source,
                    0,
                    source.length,
                    sourceChunkSize,
                    targetChunkSize,
                    directSource,
                    directTarget,
                    flushBetweenChunks,
                    compressed
            );
            finishEncoder(encoder, targetChunkSize, directTarget, compressed);
            return;
        }

        int offset = 0;
        for (int frameIndex = 0; frameIndex < requestedFrameCount; frameIndex++) {
            int size = frameSize(source.length, requestedFrameCount, frameIndex);
            if (frameIndex > 0) {
                framedEncoder.startFrame(EncodingOptions.ofSourceSize(size));
            }
            encodeRange(
                    encoder,
                    source,
                    offset,
                    size,
                    sourceChunkSize,
                    targetChunkSize,
                    directSource,
                    directTarget,
                    flushBetweenChunks,
                    compressed
            );
            finishFrame(framedEncoder, targetChunkSize, directTarget, compressed);
            offset += size;
        }
        finishEncoder(encoder, targetChunkSize, directTarget, compressed);
    }

    /// Encodes and terminally finishes one stream after an encoder reset.
    private static void encodeSingleStream(
            CompressionEncoder encoder,
            byte @Unmodifiable [] source,
            int sourceChunkSize,
            int targetChunkSize,
            boolean directSource,
            boolean directTarget,
            ByteArrayOutputStream compressed
    ) throws IOException {
        encodeRange(
                encoder,
                source,
                0,
                source.length,
                sourceChunkSize,
                targetChunkSize,
                directSource,
                directTarget,
                false,
                compressed
        );
        finishEncoder(encoder, targetChunkSize, directTarget, compressed);
    }

    /// Returns one balanced frame size whose sum across all frame indices equals the source size.
    private static int frameSize(int sourceSize, int frameCount, int frameIndex) {
        int quotient = sourceSize / frameCount;
        return quotient + (frameIndex < sourceSize % frameCount ? 1 : 0);
    }

    /// Encodes one source range through independently allocated input chunks.
    private static void encodeRange(
            CompressionEncoder encoder,
            byte @Unmodifiable [] source,
            int offset,
            int length,
            int sourceChunkSize,
            int targetChunkSize,
            boolean directSource,
            boolean directTarget,
            boolean flushBetweenChunks,
            ByteArrayOutputStream compressed
    ) throws IOException {
        int end = offset + length;
        while (offset < end) {
            int chunkSize = Math.min(sourceChunkSize, end - offset);
            ByteBuffer input = newBuffer(chunkSize + 4, directSource);
            input.position(2);
            input.put(source, offset, chunkSize);
            input.flip();
            input.position(2);
            ByteBuffer output = newBuffer(targetChunkSize, directTarget);
            while (input.hasRemaining()) {
                CodecOutcome outcome = encoder.encode(input, output);
                validateEncoderProgress(outcome, input, output);
                drain(output, compressed);
            }
            offset += chunkSize;
            if (flushBetweenChunks && encoder instanceof CompressionEncoder.Flushable flushableEncoder) {
                flushEncoder(flushableEncoder, targetChunkSize, directTarget, compressed);
            }
        }
    }

    /// Validates the buffer-state meaning of one incremental encoder outcome.
    private static void validateEncoderProgress(
            CodecOutcome outcome,
            ByteBuffer source,
            ByteBuffer target
    ) {
        switch (outcome) {
            case NEEDS_INPUT -> {
                if (source.hasRemaining()) {
                    throw new AssertionError("Encoder requested input while source bytes remain");
                }
            }
            case NEEDS_OUTPUT -> {
                if (target.hasRemaining()) {
                    throw new AssertionError("Encoder requested output space without filling its target");
                }
            }
            default -> throw new AssertionError("Unexpected encoder outcome: " + outcome);
        }
    }

    /// Repeats a flush operation until the encoder reports a completed boundary.
    private static void flushEncoder(
            CompressionEncoder.Flushable encoder,
            int targetChunkSize,
            boolean directTarget,
            ByteArrayOutputStream compressed
    ) throws IOException {
        while (true) {
            ByteBuffer output = newBuffer(targetChunkSize, directTarget);
            CodecOutcome outcome = encoder.flush(output);
            if (outcome == CodecOutcome.NEEDS_OUTPUT && output.hasRemaining()) {
                throw new AssertionError("Encoder flush requested output space without filling its target");
            }
            drain(output, compressed);
            if (outcome == CodecOutcome.FLUSHED) {
                return;
            }
            if (outcome != CodecOutcome.NEEDS_OUTPUT) {
                throw new AssertionError("Unexpected encoder flush outcome: " + outcome);
            }
        }
    }

    /// Repeats frame finalization until the encoder reports a completed frame boundary.
    private static void finishFrame(
            CompressionEncoder.Framed encoder,
            int targetChunkSize,
            boolean directTarget,
            ByteArrayOutputStream compressed
    ) throws IOException {
        while (true) {
            ByteBuffer output = newBuffer(targetChunkSize, directTarget);
            CodecOutcome outcome = encoder.finishFrame(output);
            if (outcome == CodecOutcome.NEEDS_OUTPUT && output.hasRemaining()) {
                throw new AssertionError("Frame finish requested output space without filling its target");
            }
            drain(output, compressed);
            if (outcome == CodecOutcome.BOUNDARY_REACHED) {
                return;
            }
            if (outcome != CodecOutcome.NEEDS_OUTPUT) {
                throw new AssertionError("Unexpected frame finish outcome: " + outcome);
            }
        }
    }

    /// Repeats terminal finalization until the encoder reports completion.
    private static void finishEncoder(
            CompressionEncoder encoder,
            int targetChunkSize,
            boolean directTarget,
            ByteArrayOutputStream compressed
    ) throws IOException {
        while (true) {
            ByteBuffer output = newBuffer(targetChunkSize, directTarget);
            CodecOutcome outcome = encoder.finish(output);
            if (outcome == CodecOutcome.NEEDS_OUTPUT && output.hasRemaining()) {
                throw new AssertionError("Encoder finish requested output space without filling its target");
            }
            drain(output, compressed);
            if (outcome == CodecOutcome.FINISHED) {
                return;
            }
            if (outcome != CodecOutcome.NEEDS_OUTPUT) {
                throw new AssertionError("Unexpected encoder finish outcome: " + outcome);
            }
        }
    }

    /// Appends all produced bytes to an encoding and restores the target for reuse.
    private static void drain(ByteBuffer target, ByteArrayOutputStream compressed) {
        target.flip();
        while (target.hasRemaining()) {
            compressed.write(target.get());
        }
        target.clear();
    }

    /// Verifies that one complete generated encoding reproduces its source and is fully consumed.
    private static void assertRoundTrip(
            CompressionCodec<?> codec,
            byte @Unmodifiable [] expected,
            byte @Unmodifiable [] compressed
    ) throws IOException {
        ByteBuffer source = ByteBuffer.wrap(compressed);
        ByteBuffer decoded = codec.decompress(source);
        if (source.hasRemaining()) {
            throw new AssertionError("State-machine decoder left compressed bytes unconsumed");
        }
        if (!Arrays.equals(expected, FuzzSupport.remainingBytes(decoded))) {
            throw new AssertionError("State-machine encoding did not reproduce its source");
        }
    }

    /// Runs one bounded decoder with caller-selected heap or direct buffer chunking.
    ///
    /// @param codec the bounded decoder configuration
    /// @param data control bytes followed by compressed bytes
    /// @param sourceChunkSize the positive input exposure size
    /// @param targetChunkSize the positive output buffer size
    /// @param directSource whether to use a direct input buffer
    /// @param directTarget whether to use a direct output buffer
    /// @return all bytes produced before successful completion
    /// @throws IOException if decoding rejects the input
    private static byte @Unmodifiable [] runDecoder(
            CompressionCodec<?> codec,
            byte @Unmodifiable [] data,
            int sourceChunkSize,
            int targetChunkSize,
            boolean directSource,
            boolean directTarget
    ) throws IOException {
        int payloadSize = data.length - DECODER_HEADER_SIZE;
        ByteBuffer source = newBuffer(payloadSize + 4, directSource);
        source.position(2);
        source.put(data, DECODER_HEADER_SIZE, payloadSize);
        source.flip();
        source.position(2);
        int finalSourceLimit = source.limit();
        source.limit(Math.min(finalSourceLimit, source.position() + sourceChunkSize));
        ByteBuffer target = newBuffer(targetChunkSize, directTarget);
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();

        try (CompressionDecoder decoder = codec.newDecoder()) {
            while (true) {
                boolean finalInput = source.limit() == finalSourceLimit;
                CodecOutcome outcome = finalInput
                        ? decoder.finish(source, target)
                        : decoder.decode(source, target);
                switch (outcome) {
                    case FINISHED -> {
                        drain(target, decoded);
                        return decoded.toByteArray();
                    }
                    case NEEDS_DICTIONARY -> throw new IOException("Decoder requires an unavailable dictionary");
                    case NEEDS_OUTPUT -> {
                        if (target.position() == 0 || target.hasRemaining()) {
                            throw new AssertionError("Decoder requested output space without filling its target");
                        }
                        drain(target, decoded);
                    }
                    case NEEDS_INPUT -> {
                        if (source.hasRemaining()) {
                            throw new AssertionError("Decoder requested input while source bytes remain visible");
                        }
                        if (finalInput) {
                            throw new AssertionError("Decoder finish returned NEEDS_INPUT");
                        }
                        source.limit(Math.min(finalSourceLimit, source.position() + sourceChunkSize));
                        drain(target, decoded);
                    }
                    default -> throw new AssertionError("Unexpected decoder outcome: " + outcome);
                }
            }
        }
    }

    /// Allocates a heap or direct byte buffer.
    ///
    /// @param capacity the nonnegative capacity
    /// @param direct whether to allocate outside the Java heap
    /// @return a new writable buffer
    private static ByteBuffer newBuffer(int capacity, boolean direct) {
        return direct ? ByteBuffer.allocateDirect(capacity) : ByteBuffer.allocate(capacity);
    }

    /// Supplies one complete valid compressed encoding for each installed codec.
    ///
    /// @return deterministic decoder seed arguments
    /// @throws IOException if a seed cannot be encoded
    private static Stream<Arguments> compressionDecoderSeeds() throws IOException {
        Arguments[] seeds = new Arguments[FuzzSupport.COMPRESSION_FORMATS.size()];
        for (int index = 0; index < seeds.length; index++) {
            seeds[index] = Arguments.of((Object) compressionDecoderSeed(index));
        }
        return Arrays.stream(seeds);
    }

    /// Creates one complete incremental-decoder seed for the selected installed format.
    ///
    /// @param index the compression-format list index
    /// @return the control header followed by a complete valid encoding
    /// @throws IOException if the seed cannot be encoded
    private static byte @Unmodifiable [] compressionDecoderSeed(int index) throws IOException {
        CompressionFormat format = FuzzSupport.COMPRESSION_FORMATS.get(index);
        CompressionCodec<?> codec = FuzzSupport.boundedCodec(
                format.defaultCodec(),
                FuzzSupport.SEED_CONTENT.length
        );
        byte[] compressed = FuzzSupport.remainingBytes(
                codec.compress(ByteBuffer.wrap(FuzzSupport.SEED_CONTENT))
        );
        byte[] controls = {
                (byte) index,
                (byte) FuzzSupport.SEED_CONTENT.length,
                0,
                7,
                11,
                (byte) index
        };
        return FuzzSupport.prefix(controls, compressed);
    }

    /// Supplies representative payloads to every codec's round-trip target.
    ///
    /// @return deterministic round-trip seed arguments
    private static Stream<Arguments> compressionRoundTripSeeds() {
        return IntStream.range(0, FuzzSupport.COMPRESSION_FORMATS.size())
                .mapToObj(index -> Arguments.of((Object) FuzzSupport.prefix(
                        new byte[]{(byte) index},
                        FuzzSupport.SEED_CONTENT
                )));
    }

    /// Supplies state-machine controls and representative content to every installed encoder.
    ///
    /// @return deterministic encoder state-machine seed arguments
    private static Stream<Arguments> compressionEncoderStateSeeds() {
        return IntStream.range(0, FuzzSupport.COMPRESSION_FORMATS.size())
                .mapToObj(index -> Arguments.of((Object) FuzzSupport.prefix(
                        new byte[]{(byte) index, 7, 11, 0x1f, 3, 5},
                        FuzzSupport.SEED_CONTENT
                )));
    }
}
