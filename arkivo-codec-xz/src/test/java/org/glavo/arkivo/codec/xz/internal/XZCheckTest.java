// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.xz.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies XZ integrity-check widths, byte representations, state reuse, and validation.
@NotNullByDefault
final class XZCheckTest {
    /// Verifies every implemented Check ID emits the independently calculated canonical byte representation.
    @Test
    void createsSupportedChecksAndResetsAfterFinish() throws IOException, NoSuchAlgorithmException {
        byte[] input = "123456789".getBytes(StandardCharsets.US_ASCII);
        CRC32 crc32 = new CRC32();
        crc32.update(input);

        assertCheck(XZSupport.CHECK_NONE, input, new byte[0]);
        assertCheck(XZSupport.CHECK_CRC32, input, littleEndian(crc32.getValue(), Integer.BYTES));
        assertCheck(
                XZSupport.CHECK_CRC64,
                input,
                littleEndian(0x995d_c9bb_df19_39faL, Long.BYTES)
        );
        assertCheck(
                XZSupport.CHECK_SHA256,
                input,
                MessageDigest.getInstance("SHA-256").digest(input)
        );
    }

    /// Verifies all defined Check IDs map to the format-prescribed encoded widths.
    @Test
    void returnsEveryDefinedCheckWidth() throws IOException {
        int[] expectedSizes = {
                0,
                4, 4, 4,
                8, 8, 8,
                16, 16, 16,
                32, 32, 32,
                64, 64, 64
        };
        for (int type = 0; type < expectedSizes.length; type++) {
            assertEquals(expectedSizes[type], XZCheck.sizeOf(type), "Check ID " + type);
        }

        IOException negative = assertThrows(IOException.class, () -> XZCheck.sizeOf(-1));
        assertEquals("Invalid XZ integrity check type: -1", negative.getMessage());
        IOException excessive = assertThrows(IOException.class, () -> XZCheck.sizeOf(16));
        assertEquals("Invalid XZ integrity check type: 16", excessive.getMessage());
    }

    /// Verifies reserved and out-of-domain Check IDs cannot create a checksum implementation.
    @Test
    void rejectsUnsupportedCheckImplementations() {
        int[] unsupportedTypes = {-1, 2, 3, 5, 9, 11, 15, 16};
        for (int type : unsupportedTypes) {
            IOException exception = assertThrows(IOException.class, () -> XZCheck.create(type));
            assertEquals("Unsupported XZ integrity check type: " + type, exception.getMessage());
        }
    }

    /// Verifies update arguments are checked before the reusable checksum state changes.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void validatesUpdateRanges() throws IOException {
        XZCheck check = XZCheck.create(XZSupport.CHECK_CRC32);
        byte[] source = {1, 2, 3};

        assertThrows(NullPointerException.class, () -> check.update(null, 0, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> check.update(source, -1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> check.update(source, 1, 3));

        check.update(source, 1, 0);
        assertArrayEquals(new byte[Integer.BYTES], check.finish());
    }

    /// Verifies one supported check against padded input and a second calculation after implicit reset.
    private static void assertCheck(int type, byte[] input, byte[] expected) throws IOException {
        XZCheck check = XZCheck.create(type);
        assertEquals(expected.length, check.size());

        byte[] padded = new byte[input.length + 2];
        padded[0] = 11;
        System.arraycopy(input, 0, padded, 1, input.length);
        padded[padded.length - 1] = 22;
        check.update(padded, 1, input.length);
        assertArrayEquals(expected, check.finish());

        check.update(input, 0, input.length);
        assertArrayEquals(expected, check.finish());
    }

    /// Encodes the least significant bytes of a primitive value in little-endian order.
    private static byte[] littleEndian(long value, int size) {
        byte[] result = new byte[size];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) (value >>> (index * Byte.SIZE));
        }
        return result;
    }
}
