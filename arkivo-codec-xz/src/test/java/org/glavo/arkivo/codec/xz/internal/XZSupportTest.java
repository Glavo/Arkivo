// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.xz.internal;

import org.glavo.arkivo.codec.lzma.LZMAProperties;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies canonical XZ integers, LZMA2 dictionary properties, and exact stream reads.
@NotNullByDefault
final class XZSupportTest {
    /// Verifies representative VLI boundaries against their canonical byte encodings.
    @Test
    void readsAndWritesCanonicalVariableLengthIntegers() throws IOException {
        assertVli(0L, new byte[]{0});
        assertVli(0x7fL, new byte[]{0x7f});
        assertVli(0x80L, new byte[]{(byte) 0x80, 0x01});
        assertVli(0x3fffL, new byte[]{(byte) 0xff, 0x7f});
        assertVli(0x4000L, new byte[]{(byte) 0x80, (byte) 0x80, 0x01});
        assertVli(
                Long.MAX_VALUE,
                new byte[]{
                        (byte) 0xff, (byte) 0xff, (byte) 0xff,
                        (byte) 0xff, (byte) 0xff, (byte) 0xff,
                        (byte) 0xff, (byte) 0xff, 0x7f
                }
        );
    }

    /// Verifies negative, truncated, overlong, and non-canonical VLI representations are rejected.
    @Test
    void rejectsInvalidVariableLengthIntegers() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        IllegalArgumentException negative = assertThrows(
                IllegalArgumentException.class,
                () -> XZSupport.writeVli(output, -1L)
        );
        assertEquals("XZ variable-length integers must be nonnegative", negative.getMessage());
        assertEquals(0, output.size());

        EOFException truncated = assertThrows(
                EOFException.class,
                () -> XZSupport.readVli(new ByteArrayInputStream(new byte[]{(byte) 0x80}))
        );
        assertEquals("Truncated XZ stream", truncated.getMessage());

        IOException nonCanonical = assertThrows(
                IOException.class,
                () -> XZSupport.readVli(new ByteArrayInputStream(new byte[]{(byte) 0x80, 0x00}))
        );
        assertEquals("Non-canonical XZ variable-length integer", nonCanonical.getMessage());

        IOException excessive = assertThrows(
                IOException.class,
                () -> XZSupport.readVli(new ByteArrayInputStream(new byte[]{
                        (byte) 0x80, (byte) 0x80, (byte) 0x80,
                        (byte) 0x80, (byte) 0x80, (byte) 0x80,
                        (byte) 0x80, (byte) 0x80, (byte) 0x80
                }))
        );
        assertEquals("XZ variable-length integer is too large", excessive.getMessage());
    }

    /// Verifies dictionary sizes round up to the smallest representable LZMA2 property.
    @Test
    void convertsLzma2DictionaryPropertiesAtBoundaries() throws IOException {
        assertEquals(0, XZSupport.lzma2DictionaryProperty(LZMAProperties.MINIMUM_DICTIONARY_SIZE));
        assertEquals(1, XZSupport.lzma2DictionaryProperty(LZMAProperties.MINIMUM_DICTIONARY_SIZE + 1));
        assertEquals(1, XZSupport.lzma2DictionaryProperty(6 * 1024));
        assertEquals(2, XZSupport.lzma2DictionaryProperty(6 * 1024 + 1));
        assertEquals(37, XZSupport.lzma2DictionaryProperty(LZMAProperties.MAXIMUM_DICTIONARY_SIZE));

        assertEquals(4 * 1024, XZSupport.lzma2DictionarySize(0));
        assertEquals(6 * 1024, XZSupport.lzma2DictionarySize(1));
        assertEquals(8 * 1024, XZSupport.lzma2DictionarySize(2));
        assertEquals(LZMAProperties.MAXIMUM_DICTIONARY_SIZE, XZSupport.lzma2DictionarySize(37));

        IllegalArgumentException belowMinimum = assertThrows(
                IllegalArgumentException.class,
                () -> XZSupport.lzma2DictionaryProperty(LZMAProperties.MINIMUM_DICTIONARY_SIZE - 1)
        );
        assertEquals("Unsupported XZ LZMA2 dictionary size: 4095", belowMinimum.getMessage());
        IllegalArgumentException aboveMaximum = assertThrows(
                IllegalArgumentException.class,
                () -> XZSupport.lzma2DictionaryProperty(LZMAProperties.MAXIMUM_DICTIONARY_SIZE + 1)
        );
        assertEquals(
                "Unsupported XZ LZMA2 dictionary size: 1610612737",
                aboveMaximum.getMessage()
        );

        IOException negativeProperty = assertThrows(IOException.class, () -> XZSupport.lzma2DictionarySize(-1));
        assertEquals("Unsupported XZ LZMA2 dictionary property: -1", negativeProperty.getMessage());
        IOException excessiveProperty = assertThrows(IOException.class, () -> XZSupport.lzma2DictionarySize(38));
        assertEquals("Unsupported XZ LZMA2 dictionary property: 38", excessiveProperty.getMessage());
    }

    /// Verifies exact reads recover from transient zero progress and distinguish empty ranges from truncation.
    @Test
    void readsExactRangesAcrossZeroProgressAndEndOfInput() throws IOException {
        byte[] target = {9, 9, 9, 9, 9};
        ZeroProgressInputStream input = new ZeroProgressInputStream(new byte[]{1, 2, 3});

        XZSupport.readFully(input, target, 1, 3);

        assertArrayEquals(new byte[]{9, 1, 2, 3, 9}, target);
        assertEquals(-1, input.read());
        XZSupport.readFully(InputStream.nullInputStream(), target, 2, 0);

        EOFException truncated = assertThrows(
                EOFException.class,
                () -> XZSupport.readFully(new ByteArrayInputStream(new byte[]{4}), new byte[2], 0, 2)
        );
        assertEquals("Truncated XZ stream", truncated.getMessage());
        assertEquals(5, XZSupport.readRequiredByte(new ByteArrayInputStream(new byte[]{5})));
        EOFException missing = assertThrows(
                EOFException.class,
                () -> XZSupport.readRequiredByte(InputStream.nullInputStream())
        );
        assertEquals("Truncated XZ stream", missing.getMessage());
    }

    /// Verifies one VLI encoder and decoder against an independently supplied canonical representation.
    private static void assertVli(long value, byte[] expected) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        XZSupport.writeVli(output, value);
        assertArrayEquals(expected, output.toByteArray());

        ByteArrayInputStream input = new ByteArrayInputStream(expected);
        assertEquals(value, XZSupport.readVli(input));
        assertEquals(-1, input.read());
    }

    /// Supplies one zero-progress bulk read before exposing fixed in-memory bytes.
    @NotNullByDefault
    private static final class ZeroProgressInputStream extends ByteArrayInputStream {
        /// Whether the initial nonempty bulk read has been intercepted.
        private boolean zeroReturned;

        /// Creates a stream over the supplied bytes.
        private ZeroProgressInputStream(byte[] bytes) {
            super(bytes);
        }

        /// Returns zero for the first nonempty bulk read and delegates all later reads.
        @Override
        public synchronized int read(byte[] bytes, int offset, int length) {
            if (!zeroReturned && length > 0) {
                zeroReturned = true;
                return 0;
            }
            return super.read(bytes, offset, length);
        }
    }
}
