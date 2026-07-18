// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.all;

import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.CompressionFormats;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.DecodingOptions;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/// Verifies every installed decoder handles deterministically damaged frames predictably.
@NotNullByDefault
final class MalformedCodecRobustnessTest {
    /// Deterministic plaintext compressed by every bidirectional codec.
    private static final byte @Unmodifiable [] CONTENT = (
            "Arkivo malformed codec robustness fixture\n" + "0123456789abcdef".repeat(512)
    ).getBytes(java.nio.charset.StandardCharsets.UTF_8);

    /// Maximum decoded output allowed for a damaged frame.
    private static final long MAX_OUTPUT_SIZE = 1L << 20;

    /// Maximum history window accepted from a damaged frame.
    private static final long MAX_WINDOW_SIZE = 64L << 20;

    /// Number of deterministic mutations applied to each valid frame.
    private static final int MUTATION_COUNT = 128;

    /// Reproducible seed for codec mutations.
    private static final long MUTATION_SEED = 0x434f4445434d5554L;

    /// Exercises representative truncations and mutations through named and detected decoder factories.
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void boundsAndNormalizesMalformedCodecFailures() throws IOException {
        for (CompressionFormat format : CompressionFormats.installed()) {
            CompressionCodec<?> codec = format.defaultCodec();
            byte[] validFrame = compress(codec);
            CompressionCodec<?> decoderCodec =
                    CodecContractConfigurations.decoderCodec(codec, CONTENT.length);
            DecodingOptions limits = boundedLimits();
            assertArrayEquals(CONTENT, decodeNamed(decoderCodec, validFrame, limits), codec.format().name());
            boolean detectable = codec.format().matches(ByteBuffer.wrap(validFrame).asReadOnlyBuffer());
            if (detectable) {
                assertArrayEquals(CONTENT, decodeDetected(validFrame, limits), codec.format().name());
            }

            for (int length : truncationLengths(validFrame.length)) {
                byte[] truncated = Arrays.copyOf(validFrame, length);
                exerciseMalformedFrame(codec, decoderCodec, truncated, limits, detectable, "truncation@" + length);
            }

            SplittableRandom random = new SplittableRandom(MUTATION_SEED ^ codec.format().name().hashCode());
            for (int index = 0; index < MUTATION_COUNT; index++) {
                byte[] mutated = mutate(validFrame, random);
                exerciseMalformedFrame(codec, decoderCodec, mutated, limits, detectable, "mutation#" + index);
            }
        }
    }

    /// Compresses the shared plaintext with one codec's default encoder.
    private static byte[] compress(CompressionCodec<?> codec) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        codec.compress(
                Channels.newChannel(new ByteArrayInputStream(CONTENT)),
                Channels.newChannel(output)
        );
        return output.toByteArray();
    }

    /// Returns the defensive limits applied to every damaged-frame decoder.
    private static DecodingOptions boundedLimits() {
        return new DecodingOptions(MAX_OUTPUT_SIZE, MAX_WINDOW_SIZE, MAX_WINDOW_SIZE);
    }

    /// Exercises signature detection and every applicable decoder factory for one damaged frame.
    private static void exerciseMalformedFrame(
            CompressionCodec<?> codec,
            CompressionCodec<?> decoderCodec,
            byte[] frame,
            DecodingOptions limits,
            boolean detectable,
            String variant
    ) {
        String context = codec.format().name() + " " + variant;
        tolerateMalformedFailure(
                () -> CompressionFormats.detect(ByteBuffer.wrap(frame).asReadOnlyBuffer()),
                context + " detection"
        );
        tolerateMalformedFailure(
                () -> decodeNamed(decoderCodec, frame, limits),
                context + " named decoder"
        );
        if (detectable) {
            tolerateMalformedFailure(
                    () -> decodeDetected(frame, limits),
                    context + " detected decoder"
            );
        }
    }

    /// Decodes a frame through its explicit provider.
    private static byte[] decodeNamed(
            CompressionCodec<?> codec,
            byte[] frame,
            DecodingOptions limits
    ) throws IOException {
        try (DecompressingReadableByteChannel decoder = codec.newReadableByteChannel(
                Channels.newChannel(new ByteArrayInputStream(frame)),
                limits,
                ResourceOwnership.BORROWED
        )) {
            return consumeDecoder(decoder);
        }
    }

    /// Decodes a frame through generic signature detection.
    private static byte[] decodeDetected(byte[] frame, DecodingOptions limits) throws IOException {
        try (DecompressingReadableByteChannel decoder = CompressionFormats.newReadableByteChannel(
                Channels.newChannel(new ByteArrayInputStream(frame)),
                limits,
                ResourceOwnership.BORROWED
        )) {
            return consumeDecoder(decoder);
        }
    }

    /// Fully consumes one decoder while independently enforcing output progress and size.
    private static byte[] consumeDecoder(DecompressingReadableByteChannel decoder) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteBuffer target = ByteBuffer.allocate(4096);
        while (true) {
            target.clear();
            int read = decoder.read(target);
            if (read < 0) {
                return output.toByteArray();
            }
            if (read == 0) {
                throw new IOException("Compression decoder made no progress");
            }
            output.write(target.array(), 0, read);
            if (output.size() > MAX_OUTPUT_SIZE) {
                throw new IOException("Malformed frame exceeded the defensive output bound");
            }
        }
    }

    /// Returns representative prefix lengths including frame header and trailer boundaries.
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

    /// Applies one to four deterministic non-zero bit flips to a cloned frame.
    private static byte[] mutate(byte[] validFrame, SplittableRandom random) {
        byte[] mutated = validFrame.clone();
        int changes = random.nextInt(1, 5);
        for (int change = 0; change < changes; change++) {
            int offset = random.nextInt(mutated.length);
            mutated[offset] ^= (byte) (1 << random.nextInt(Byte.SIZE));
        }
        return mutated;
    }

    /// Accepts malformed-input I/O failures while rejecting leaked runtime exceptions and errors.
    private static void tolerateMalformedFailure(Executable operation, String context) {
        assertDoesNotThrow(() -> {
            try {
                operation.execute();
            } catch (IOException expected) {
                // A damaged frame may be rejected during setup, decoding, validation, or close.
            }
        }, context);
    }
}
