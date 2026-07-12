// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies bounded Zstandard frame-window parsing.
@NotNullByDefault
final class ZstdFrameHeaderTest {
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
