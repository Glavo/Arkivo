// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.deflate.DeflateStrategy;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/// Checks encoded bytes and synchronization boundaries against deterministic reference fingerprints.
@NotNullByDefault
final class DeflateEncodingDeterminismTest {
    /// Covers all levels and strategies, both window profiles, dictionaries, flushes, and reused engines.
    @Test
    void preservesEncodedBytesAndFlushBoundaries() throws Exception {
        MessageDigest aggregate = MessageDigest.getInstance("SHA-256");
        byte[] body = new byte[70_013];
        byte[] dictionary = new byte[32_771];
        new Random(0x4c5a3737L).nextBytes(dictionary);
        for (int profile = 0; profile < 3; profile++) {
            new Random(0x4445464cL).nextBytes(body);
            if (profile == 1) {
                for (int i = 0; i < body.length; i++) body[i] &= 7;
            } else if (profile == 2) {
                for (int i = 0; i < body.length; i++) body[i] = dictionary[i % dictionary.length];
                Arrays.fill(body, 35_000, 40_000, (byte) 'a');
            }
            for (DeflateEncoderEngine.Format format : DeflateEncoderEngine.Format.values()) {
                for (int level = 0; level <= 9; level++) {
                    for (DeflateStrategy strategy : DeflateStrategy.values()) {
                        try (var encoder = new DeflateEncoderEngine(format, level,
                                format == DeflateEncoderEngine.Format.DEFLATE && profile == 2 ? dictionary : null,
                                strategy)) {
                            for (boolean flush : new boolean[]{false, true}) {
                                encoder.reset();
                                byte[] first = encode(encoder, body, aggregate, flush);
                                encoder.reset();
                                assertArrayEquals(first, encode(encoder, body, aggregate, flush));
                            }
                        }
                    }
                }
            }
        }
        assertEquals("35740ed49925e1b65bfa4bbd837de97ad7f61900e3a65974dbdc3c6a300debb1",
                HexFormat.of().formatHex(aggregate.digest()));
    }

    /// Records output lengths and byte digests at each synchronization boundary.
    private static byte @Unmodifiable [] encode(
            DeflateEncoderEngine encoder, byte[] body, MessageDigest aggregate, boolean flush)
            throws Exception {
        ByteBuffer source = ByteBuffer.wrap(body).asReadOnlyBuffer();
        ByteBuffer target = ByteBuffer.allocate(257);
        var bytes = new ByteArrayOutputStream();
        for (int boundary : new int[]{17, 65_537, body.length}) {
            source.limit(boundary);
            CodecOutcome outcome;
            do {
                target.clear();
                outcome = encoder.encode(source, target);
                bytes.write(target.array(), 0, target.position());
            } while (outcome == CodecOutcome.NEEDS_OUTPUT);
            assertEquals(CodecOutcome.NEEDS_INPUT, outcome);
            if (flush) {
                do {
                    target.clear();
                    outcome = encoder.flush(target);
                    bytes.write(target.array(), 0, target.position());
                } while (outcome == CodecOutcome.NEEDS_OUTPUT);
                assertEquals(CodecOutcome.FLUSHED, outcome);
            }
            record(bytes, aggregate);
        }
        CodecOutcome outcome;
        do {
            target.clear();
            outcome = encoder.finish(target);
            bytes.write(target.array(), 0, target.position());
        } while (outcome == CodecOutcome.NEEDS_OUTPUT);
        assertEquals(CodecOutcome.FINISHED, outcome);
        record(bytes, aggregate);
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
    }

    /// Adds an unambiguous length and digest for the accumulated encoding.
    private static void record(ByteArrayOutputStream bytes, MessageDigest aggregate) throws Exception {
        int size = bytes.size();
        for (int shift = 0; shift < 32; shift += 8) aggregate.update((byte) (size >>> shift));
        aggregate.update(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    }
}
