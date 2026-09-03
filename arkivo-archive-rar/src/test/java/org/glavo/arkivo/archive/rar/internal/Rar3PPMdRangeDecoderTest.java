// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies RAR3 PPMd arithmetic range initialization, scaling, normalization, and validation.
@NotNullByDefault
final class Rar3PPMdRangeDecoderTest {
    /// Verifies a cumulative count selects and advances an ordinary arithmetic interval.
    @Test
    void decodesArithmeticInterval() throws IOException {
        Rar3PPMdRangeDecoder decoder = initialized(0x40, 0x00, 0x00, 0x00);

        assertEquals(1, decoder.currentCount(4));
        decoder.decode(1, 2);
        assertEquals(0, decoder.currentCount(2));
    }

    /// Verifies narrow intervals consume one new packed byte during each normalization.
    @Test
    void normalizesNarrowIntervals() throws IOException {
        Rar3PPMdRangeDecoder decoder = initialized(0, 0, 0, 0, 0xab, 0xcd);

        assertEquals(0, decoder.currentCount(256));
        decoder.decode(0, 1);
        assertEquals(0, decoder.currentCount(256));
        decoder.decode(0, 1);
        assertEquals(0, decoder.currentCount(256));
        assertThrows(IOException.class, () -> decoder.decode(0, 1));
    }

    /// Verifies invalid scales, collapsed ranges, and out-of-scale coded counts are rejected.
    @Test
    void validatesArithmeticScale() throws IOException {
        Rar3PPMdRangeDecoder invalidScale = initialized(0, 0, 0, 0);
        assertThrows(IOException.class, () -> invalidScale.currentCount(0));
        assertThrows(IOException.class, () -> invalidScale.currentCount(-1));

        Rar3PPMdRangeDecoder collapsed = initialized(0, 0, 0, 0);
        assertEquals(0, collapsed.currentCount(Integer.MAX_VALUE));
        assertThrows(IOException.class, () -> collapsed.currentCount(3));

        Rar3PPMdRangeDecoder outOfScale = initialized(0xff, 0xff, 0xff, 0xff);
        assertThrows(IOException.class, () -> outOfScale.currentCount(1));
    }

    /// Verifies lifecycle, initialization input, and interval bounds.
    @Test
    void validatesInitializationAndIntervals() throws IOException {
        Rar3PPMdRangeDecoder uninitialized = new Rar3PPMdRangeDecoder();
        assertThrows(IOException.class, () -> uninitialized.currentCount(1));
        assertThrows(NullPointerException.class, () -> uninitialized.decode(0, 1));

        Rar3PPMdRangeDecoder decoder = new Rar3PPMdRangeDecoder();
        assertThrows(NullPointerException.class, () -> decoder.initialize(null));
        assertThrows(IOException.class, () -> decoder.initialize(bitInput(0, 0, 0)));

        decoder.initialize(bitInput(0, 0, 0, 0));
        assertThrows(IOException.class, () -> decoder.decode(-1, 1));
        assertThrows(IOException.class, () -> decoder.decode(1, 1));
        assertThrows(IOException.class, () -> decoder.decode(2, 1));
    }

    /// Creates and initializes a range decoder from the supplied unsigned bytes.
    private static Rar3PPMdRangeDecoder initialized(int... values) throws IOException {
        Rar3PPMdRangeDecoder decoder = new Rar3PPMdRangeDecoder();
        decoder.initialize(bitInput(values));
        return decoder;
    }

    /// Creates a legacy bit input from the supplied unsigned bytes.
    private static Rar4BitInput bitInput(int... values) {
        byte[] bytes = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            bytes[index] = (byte) values[index];
        }
        return new Rar4BitInput(new ByteArrayInputStream(bytes));
    }
}
