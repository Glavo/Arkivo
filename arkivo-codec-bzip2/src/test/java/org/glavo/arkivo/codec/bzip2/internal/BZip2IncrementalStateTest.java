// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bzip2.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.bzip2.BZip2Codec;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

/// Exercises resumable parsing across blocks and arbitrary compressed-input boundaries.
@NotNullByDefault
final class BZip2IncrementalStateTest {
    /// Verifies direct, heap, and read-only fragments, small outputs, trailing input, and reset reuse.
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 7, 8192, 65536, 1000000})
    void resumesWithoutReplayingFragments(int chunk) throws IOException {
        byte[] content = new byte[120_000];
        new Random(793L).nextBytes(content);
        ByteBuffer encoded = BZip2Codec.DEFAULT.withCompressionLevel(1).compress(ByteBuffer.wrap(content));
        byte[] frame = new byte[encoded.remaining()];
        encoded.get(frame);
        try (var decoder = new BZip2Decoder()) {
            for (int variant = 0; variant < 3; variant++) {
                decoder.reset();
                ByteBuffer source = variant == 1 ? ByteBuffer.allocateDirect(frame.length + 3)
                        : ByteBuffer.allocate(frame.length + 3);
                source.put(frame).put(new byte[]{17, 18, 19}).flip();
                if (variant == 2) source = source.asReadOnlyBuffer();
                int finalLimit = source.limit();
                source.limit(Math.min(chunk, finalLimit));
                ByteBuffer target = ByteBuffer.allocate(content.length + 1);
                int outputLimit = Math.min(7, target.capacity());
                target.limit(outputLimit);
                for (int calls = 0; ; calls++) {
                    assertTrue(calls < frame.length * 3 + content.length, "Decoder stalled");
                    int inputLimit = source.limit();
                    CodecOutcome result = decoder.decode(source, target);
                    assertEquals(inputLimit, source.limit());
                    assertEquals(outputLimit, target.limit());
                    if (result == CodecOutcome.FINISHED) break;
                    if (result == CodecOutcome.NEEDS_INPUT) {
                        assertTrue(source.limit() < finalLimit);
                        source.limit(Math.min(source.limit() + chunk, finalLimit));
                    } else {
                        assertEquals(CodecOutcome.NEEDS_OUTPUT, result);
                        outputLimit = Math.min(target.capacity(), outputLimit + 257);
                        target.limit(outputLimit);
                    }
                }
                assertEquals(frame.length, source.position());
                assertEquals(content.length, target.position());
                assertArrayEquals(content, java.util.Arrays.copyOf(target.array(), content.length));
            }
        }
    }
}
