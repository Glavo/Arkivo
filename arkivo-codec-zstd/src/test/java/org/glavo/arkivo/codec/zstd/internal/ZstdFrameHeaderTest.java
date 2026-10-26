// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.zstd.ZstdStandardFrameInfo;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies bounded Zstandard frame-window parsing.
@NotNullByDefault
final class ZstdFrameHeaderTest {
    /// Checks every valid descriptor layout, proper prefix and one-byte incremental header delivery.
    ///
    /// @param magicless whether the header omits the standard magic
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void parsesEveryHeaderLayoutIncrementally(boolean magicless) throws IOException {
        for (int descriptor = 0; descriptor <= 255; descriptor++) {
            if ((descriptor & 0x18) != 0) continue;
            boolean singleSegment = (descriptor & 0x20) != 0;
            int dictionarySize = new int[]{0, 1, 2, 4}[descriptor & 3];
            int contentSize = new int[]{singleSegment ? 1 : 0, 2, 4, 8}[descriptor >>> 6];
            ByteBuffer header = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN);
            if (!magicless) header.putInt(0xfd2fb528);
            header.put((byte) descriptor);
            if (!singleSegment) header.put((byte) 0);
            for (int index = 0; index < dictionarySize; index++) header.put((byte) (index == 0 ? 1 : 0));
            for (int index = 0; index < contentSize; index++) header.put((byte) (index == 0 ? 1 : 0));
            int length = header.position();
            long expectedContent = contentSize == 0 ? CompressionCodec.UNKNOWN_SIZE : contentSize == 2 ? 257 : 1;
            var expected = new ZstdStandardFrameInfo(length, expectedContent,
                    singleSegment ? expectedContent : 1024, dictionarySize == 0 ? 0 : 1, (descriptor & 4) != 0);
            for (int kind = 0; kind < 3; kind++) {
                ByteBuffer source = kind == 1 ? ByteBuffer.allocateDirect(length + 5) : ByteBuffer.allocate(length + 5);
                source.position(3).put(header.array(), 0, length);
                if (kind == 2) source = source.asReadOnlyBuffer();
                ByteBuffer input = source;
                ByteBuffer target = ByteBuffer.allocate(1);
                try (var decoder = new ZstdDecoder(null, 1024, magicless, true)) {
                    for (int prefix = 0; prefix < length; prefix++) {
                        input.limit(3 + prefix).position(3).mark();
                        assertNull(ZstdFrameHeader.tryParse(input, magicless));
                        assertThrows(EOFException.class, () -> ZstdFrameHeader.parse(input, magicless));
                        assertEquals(3, input.position());
                        assertEquals(3 + prefix, input.limit());
                        input.reset();
                        decoder.reset();
                        assertThrows(EOFException.class, () -> decoder.finish(input, target));
                        assertEquals(3 + prefix, input.position());
                        assertEquals(0, target.position());
                    }
                    input.limit(3 + length).position(3).mark();
                    assertEquals(expected, ZstdFrameHeader.tryParse(input, magicless));
                    assertEquals(3, input.position());
                    input.reset();
                    decoder.reset();
                    CodecOutcome complete = dictionarySize == 0 ? CodecOutcome.NEEDS_INPUT : CodecOutcome.NEEDS_DICTIONARY;
                    for (int prefix = 0; prefix <= length; prefix++) {
                        input.limit(3 + prefix);
                        assertEquals(prefix == length ? complete : CodecOutcome.NEEDS_INPUT,
                                decoder.decode(input, target));
                        assertEquals(3 + prefix, input.position());
                        assertEquals(3 + prefix, input.limit());
                        assertEquals(0, target.position());
                    }
                    if (dictionarySize != 0) {
                        assertEquals(1, decoder.dictionaryRequest().dictionaryId());
                        input.limit(input.capacity());
                        assertEquals(CodecOutcome.NEEDS_DICTIONARY, decoder.decode(input, target));
                        assertEquals(3 + length, input.position());
                    }
                }
            }
        }
    }

    /// Stops exactly at each empty skippable frame's boundary after fragmented header input.
    @Test
    void parsesEverySkippableHeaderIncrementally() throws IOException {
        try (var decoder = new ZstdDecoder(null, 1024, false, true)) {
            for (int id = 0; id < 16; id++) {
                ByteBuffer input = ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(0x184d2a50 + id).putInt(0).put(new byte[]{42, 43, 44}).flip();
                ByteBuffer target = ByteBuffer.allocate(1);
                for (int prefix = 0; prefix < 8; prefix++) {
                    decoder.reset();
                    input.limit(prefix).position(0);
                    assertEquals(CodecOutcome.NEEDS_INPUT, decoder.decode(input, target));
                    assertEquals(prefix, input.position());
                    input.limit(input.capacity());
                    assertEquals(CodecOutcome.FINISHED, decoder.finish(input, target));
                    assertEquals(8, input.position());
                    assertEquals(0, target.position());
                }
            }
        }
    }

    /// Rejects malformed prefixes as soon as their invalid field is complete, without consuming following bytes.
    @Test
    void preservesMalformedHeaderConsumption() throws IOException {
        for (boolean magicless : new boolean[]{false, true}) {
            try (var decoder = new ZstdDecoder(null, 1024, magicless, true)) {
                for (int descriptor : new int[]{0x08, 0x10, 0x18}) {
                    ByteBuffer input = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN);
                    if (!magicless) input.putInt(0xfd2fb528);
                    input.put((byte) descriptor).clear();
                    decoder.reset();
                    IOException failure = assertThrows(IOException.class, () -> decoder.decode(input, ByteBuffer.allocate(1)));
                    assertEquals("Reserved Zstandard frame descriptor bits are set", failure.getMessage());
                    assertEquals(magicless ? 1 : 5, input.position());
                }
                if (!magicless) {
                    decoder.reset();
                    ByteBuffer input = ByteBuffer.wrap(new byte[18]);
                    IOException failure = assertThrows(IOException.class, () -> decoder.decode(input, ByteBuffer.allocate(1)));
                    assertEquals("Invalid Zstandard frame", failure.getMessage());
                    assertEquals(4, input.position());
                }
            }
        }
    }

    /// Verifies ordinary and fractional window descriptors.
    @Test
    void parsesWindowDescriptor() {
        assertEquals(1_024L, requiredWindow(0x00, 0x00));
        assertEquals(3_840L, requiredWindow(0x00, 0x0f));
    }

    /// Verifies single-segment windows use the decoded frame content size.
    @Test
    void parsesSingleSegmentContentSize() {
        assertEquals(100L, requiredWindow(0x20, 100));
        assertEquals(256L, requiredWindow(0x60, 0, 0));
        assertEquals(65_536L, requiredWindow(0xa0, 0x00, 0x00, 0x01, 0x00));
    }

    /// Verifies magicless headers report the same logical window without a four-byte prefix.
    @Test
    void parsesMagiclessWindow() {
        assertEquals(
                100L,
                ZstdFrameHeader.requiredWindowSize(
                        ByteBuffer.wrap(new byte[]{0x20, 100}),
                        true
                )
        );
        assertEquals(
                ZstdFrameHeader.NEED_MORE_INPUT,
                ZstdFrameHeader.requiredWindowSize(ByteBuffer.wrap(new byte[]{0x00}), true)
        );
    }

    /// Verifies incomplete, invalid, and skippable prefixes do not invent a window size.
    @Test
    void handlesNonstandardPrefixes() {
        assertEquals(
                ZstdFrameHeader.NEED_MORE_INPUT,
                ZstdFrameHeader.requiredWindowSize(ByteBuffer.wrap(new byte[]{0x28, (byte) 0xb5}))
        );
        assertEquals(0L, ZstdFrameHeader.requiredWindowSize(ByteBuffer.wrap(new byte[]{1, 2, 3, 4})));
        assertEquals(
                0L,
                ZstdFrameHeader.requiredWindowSize(ByteBuffer.wrap(new byte[]{0x50, 0x2a, 0x4d, 0x18}))
        );
    }

    /// Creates one standard frame prefix and returns its required window size.
    private static long requiredWindow(int descriptor, int... fields) {
        byte[] header = new byte[5 + fields.length];
        header[0] = 0x28;
        header[1] = (byte) 0xb5;
        header[2] = 0x2f;
        header[3] = (byte) 0xfd;
        header[4] = (byte) descriptor;
        for (int index = 0; index < fields.length; index++) {
            header[5 + index] = (byte) fields[index];
        }
        return ZstdFrameHeader.requiredWindowSize(ByteBuffer.wrap(header));
    }
}
