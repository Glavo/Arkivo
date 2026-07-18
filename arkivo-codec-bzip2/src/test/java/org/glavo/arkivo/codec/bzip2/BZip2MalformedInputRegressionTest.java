// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bzip2;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies that malformed BZip2 inputs from Apache Commons Compress regressions fail with checked exceptions.
///
/// The byte sequences are the compressed-entry payloads extracted from the ZIP fuzzing reproducers for
/// COMPRESS-516 and COMPRESS-519 in Apache Commons Compress 1.28.0.
@NotNullByDefault
final class BZip2MalformedInputRegressionTest {
    /// Shared codec under test.
    private static final BZip2Codec CODEC = new BZip2Codec();

    /// Verifies malformed payloads through the stream/channel adapter path.
    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedStreams")
    void directDecodingThrowsIOException(String issue, byte @Unmodifiable [] compressed) {
        assertThrows(IOException.class, () -> decodeDirect(compressed), issue);
    }

    /// Verifies malformed payloads through fresh one-byte source buffers.
    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedStreams")
    void fragmentedDecodingThrowsIOException(String issue, byte @Unmodifiable [] compressed) {
        assertThrows(IOException.class, () -> decodeFragmented(compressed), issue);
    }

    /// Supplies the exact malformed BZip2 payloads from the upstream regression reproducers.
    private static Stream<Arguments> malformedStreams() {
        // The payload definitions below are adapted from Apache Commons Compress 1.28.0 test fixtures.
        // Copyright 2002-2025 The Apache Software Foundation; licensed under Apache-2.0.
        return Stream.of(
                Arguments.of(
                        "COMPRESS-516",
                        bytes("425a683431415926535962e44f5100000dd1800010400035f98b0020004889fa94f29e29e8d2118a4f53340f517aed8665d6ed61ee6889487d0771922a50600495613547733129c2dd5ec74a1514324cda000000000000000000")
                ),
                Arguments.of(
                        "COMPRESS-519",
                        bytes("425a683431415926535962e44f5180000dd1800010400035f98b0020004889fa94f29e29e8d2000022000000504b0304140008000800000000")
                )
        );
    }

    /// Parses one compact hexadecimal fixture into its caller-owned byte array.
    private static byte @Unmodifiable [] bytes(String hex) {
        return HexFormat.of().parseHex(hex);
    }

    /// Consumes one malformed payload through the public stream adapter.
    private static void decodeDirect(byte @Unmodifiable [] compressed) throws IOException {
        try (InputStream input = CODEC.newInputStream(new ByteArrayInputStream(compressed))) {
            byte[] buffer = new byte[8192];
            while (input.read(buffer) >= 0) {
                // Discard output until the malformed stream is rejected.
            }
        }
    }

    /// Consumes one malformed payload through fresh one-byte source buffers.
    private static void decodeFragmented(byte @Unmodifiable [] compressed) throws IOException {
        int offset = 0;
        try (CompressionDecoder decoder = CODEC.newDecoder()) {
            while (true) {
                int length = Math.min(1, compressed.length - offset);
                ByteBuffer source = ByteBuffer.wrap(compressed, offset, length).slice();
                ByteBuffer target = ByteBuffer.allocate(257);
                boolean endOfInput = offset + length == compressed.length;
                CodecOutcome outcome = endOfInput
                        ? decoder.finish(source, target)
                        : decoder.decode(source, target);
                offset += source.position();

                if (outcome == CodecOutcome.FINISHED) {
                    return;
                }
                if (outcome == CodecOutcome.NEEDS_OUTPUT) {
                    continue;
                }
                if (outcome == CodecOutcome.NEEDS_INPUT && !endOfInput) {
                    continue;
                }
                throw new AssertionError("Unexpected BZip2 decoder outcome: " + outcome);
            }
        }
    }
}
