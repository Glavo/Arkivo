// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4;

import net.jpountz.lz4.LZ4Factory;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/// Verifies legacy-frame block interoperability against the independent lz4-java implementation.
@NotNullByDefault
public final class LZ4LegacyFrameInteroperabilityTest {
    /// Fixed decoded block size required by the legacy representation.
    private static final int LEGACY_BLOCK_SIZE = 8 * 1024 * 1024;

    /// Verifies one full block followed by the only permitted partial final block.
    @Test
    public void decodesLZ4JavaLegacyBlocks() throws IOException {
        byte[] expected = new byte[LEGACY_BLOCK_SIZE + 4097];
        for (int index = 0; index < expected.length; index++) {
            expected[index] = (byte) (index * 31 + index / 101);
        }

        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        writeInt(frame, (int) LZ4Format.LEGACY_FRAME_MAGIC);
        appendBlock(frame, Arrays.copyOfRange(expected, 0, LEGACY_BLOCK_SIZE));
        appendBlock(frame, Arrays.copyOfRange(expected, LEGACY_BLOCK_SIZE, expected.length));

        ByteBuffer decoded = new LZ4Codec().decompress(
                ByteBuffer.wrap(frame.toByteArray()),
                expected.length
        );
        byte[] actual = new byte[decoded.remaining()];
        decoded.get(actual);
        assertArrayEquals(expected, actual);
    }

    /// Compresses and appends one independently implemented legacy block.
    private static void appendBlock(ByteArrayOutputStream frame, byte[] decoded) {
        byte[] compressed = LZ4Factory.safeInstance().fastCompressor().compress(decoded);
        writeInt(frame, compressed.length);
        frame.writeBytes(compressed);
    }

    /// Writes one little-endian 32-bit integer.
    private static void writeInt(ByteArrayOutputStream output, int value) {
        byte[] bytes = new byte[Integer.BYTES];
        ByteArrayAccess.writeIntLittleEndian(bytes, 0, value);
        output.writeBytes(bytes);
    }
}
