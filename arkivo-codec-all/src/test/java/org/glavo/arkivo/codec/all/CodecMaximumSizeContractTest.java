// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.all;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.CompressionFormats;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies compressed-size bounds exposed by every installed codec obey the common API contract.
@NotNullByDefault
final class CodecMaximumSizeContractTest {
    /// Practical source sizes covering empty, byte, code-width, block, and Zstandard margin boundaries.
    private static final int @Unmodifiable [] PRACTICAL_SOURCE_SIZES = {
            0, 1, 2, 15, 16, 254, 255, 256, 4_095, 65_535, 65_536, 131_071, 131_072
    };

    /// Large sizes used only to exercise overflow-safe bound arithmetic.
    private static final long @Unmodifiable [] LARGE_SOURCE_SIZES = {
            Integer.MAX_VALUE,
            (long) Integer.MAX_VALUE + 1L,
            Long.MAX_VALUE - 1L,
            Long.MAX_VALUE
    };

    /// Verifies finite reported bounds contain actual encodings and never use an invalid negative sentinel.
    @Test
    void reportedBoundsContainActualEncodings() throws IOException {
        for (CompressionFormat format : CompressionFormats.installed()) {
            CompressionCodec<?> codec = format.defaultCodec();
            for (int sourceSize : PRACTICAL_SOURCE_SIZES) {
                long maximum = codec.maxCompressedSize(sourceSize);
                assertValidBound(maximum, format.name() + " size=" + sourceSize);
                if (maximum == CompressionCodec.UNKNOWN_SIZE) {
                    continue;
                }

                byte[] content = content(sourceSize);
                ByteBuffer encoded = codec.compress(ByteBuffer.wrap(content));
                assertTrue(
                        encoded.remaining() <= maximum,
                        format.name() + " encoded=" + encoded.remaining() + " maximum=" + maximum
                );
            }
        }
    }

    /// Verifies all implementations reject negative sizes and saturate or report unknown on arithmetic overflow.
    @Test
    void validatesInputAndHandlesExtremeSizesWithoutOverflow() {
        for (CompressionFormat format : CompressionFormats.installed()) {
            CompressionCodec<?> codec = format.defaultCodec();
            assertThrows(IllegalArgumentException.class, () -> codec.maxCompressedSize(-1L), format.name());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> codec.maxCompressedSize(Long.MIN_VALUE),
                    format.name()
            );
            for (long sourceSize : LARGE_SOURCE_SIZES) {
                assertValidBound(
                        codec.maxCompressedSize(sourceSize),
                        format.name() + " size=" + sourceSize
                );
            }
        }
    }

    /// Creates deterministic incompressible-looking bytes of the requested length.
    private static byte @Unmodifiable [] content(int length) {
        byte[] result = new byte[length];
        int state = 0x6d2b79f5;
        for (int index = 0; index < result.length; index++) {
            state ^= state << 13;
            state ^= state >>> 17;
            state ^= state << 5;
            result[index] = (byte) state;
        }
        return result;
    }

    /// Verifies a value is either the documented unknown sentinel or a nonnegative bound.
    private static void assertValidBound(long maximum, String context) {
        assertTrue(maximum == CompressionCodec.UNKNOWN_SIZE || maximum >= 0L, context + " maximum=" + maximum);
    }
}
