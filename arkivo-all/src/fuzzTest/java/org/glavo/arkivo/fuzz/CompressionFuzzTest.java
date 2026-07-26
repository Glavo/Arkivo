// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionFormat;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

    /// Creates a compression fuzz-test instance for JUnit.
    public CompressionFuzzTest() {
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

    /// Runs one bounded decoder with caller-selected heap or direct buffer chunking.
    ///
    /// @param codec the bounded decoder configuration
    /// @param data control bytes followed by compressed bytes
    /// @param sourceChunkSize the positive input exposure size
    /// @param targetChunkSize the positive output buffer size
    /// @param directSource whether to use a direct input buffer
    /// @param directTarget whether to use a direct output buffer
    /// @throws IOException if decoding rejects the input
    private static void runDecoder(
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

        try (CompressionDecoder decoder = codec.newDecoder()) {
            while (true) {
                boolean finalInput = source.limit() == finalSourceLimit;
                CodecOutcome outcome = finalInput
                        ? decoder.finish(source, target)
                        : decoder.decode(source, target);
                switch (outcome) {
                    case FINISHED, NEEDS_DICTIONARY -> {
                        return;
                    }
                    case NEEDS_OUTPUT -> {
                        if (target.position() == 0 || target.hasRemaining()) {
                            throw new AssertionError("Decoder requested output space without filling its target");
                        }
                        target.clear();
                    }
                    case NEEDS_INPUT -> {
                        if (source.hasRemaining()) {
                            throw new AssertionError("Decoder requested input while source bytes remain visible");
                        }
                        if (finalInput) {
                            throw new AssertionError("Decoder finish returned NEEDS_INPUT");
                        }
                        source.limit(Math.min(finalSourceLimit, source.position() + sourceChunkSize));
                        target.clear();
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
            seeds[index] = Arguments.of((Object) FuzzSupport.prefix(controls, compressed));
        }
        return Arrays.stream(seeds);
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
}
