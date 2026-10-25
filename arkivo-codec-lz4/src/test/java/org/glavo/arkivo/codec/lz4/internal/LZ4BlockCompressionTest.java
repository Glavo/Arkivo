// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4.internal;

import net.jpountz.lz4.LZ4Factory;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies greedy match selection, tail boundaries, and dictionary truncation.
@NotNullByDefault
final class LZ4BlockCompressionTest {
    /// Checks encoded-byte stability and input immutability for literals and overlapping matches.
    @ParameterizedTest
    @CsvSource({
            "0, 0498c3721fba4af28c967496cad90b076fa7b0cbf88fdd3382233a0533e9caf1",
            "1, 71b35e05f24157edb37054e9353e61a2de2b39aa35f1d5ab1dc4ac604b9568fb",
            "17, e22c65e142675041c273ac4869d420ccfa76a715732375c3376cb7f30806931c",
            "65535, 5f6880655766f770bb6af2436347ea0f3adfa7cd21d1e85c94228c5942f5c04d",
            "65536, e34186a96f9609a571c51f678a29f7239d9f1744692e91e657212cc8cac06335"
    })
    void preservesBlockEncoding(int dictionarySize, String expectedDigest)
            throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int[] largerSizes = {127, 128, 129, 4095, 4096, 4097, 65535, 65536, 65537, 1024 * 1024};
        for (int period : new int[]{0, 1, 7, 31}) {
            byte[] dictionary = content(dictionarySize, period, 0);
            byte[] originalDictionary = dictionary.clone();
            for (int index = 0; index < 65 + largerSizes.length; index++) {
                int size = index < 65 ? index : largerSizes[index - 65];
                byte[] source = content(size, period, dictionarySize);
                if (period == 0) {
                    // Include short matches ending at each byte alignment.
                    for (int offset = 128; offset + 64 <= size; offset += 128) {
                        System.arraycopy(source, offset - 128, source, offset, 4 + (offset / 128 % 32));
                    }
                }
                byte[] originalSource = source.clone();
                byte[] encoded = LZ4BlockCompression.compress(source, dictionary);
                digest.update(encoded);
                var decoded = LZ4BlockDecompression.decompress(encoded, dictionary, size, -1, -1);
                assertArrayEquals(originalSource, Arrays.copyOf(decoded.bytes(), decoded.length()));
                assertArrayEquals(originalSource, source);
                assertArrayEquals(originalDictionary, dictionary);
                if (dictionarySize == 0) {
                    assertArrayEquals(originalSource, LZ4Factory.safeInstance().safeDecompressor()
                            .decompress(encoded, size));
                }
            }
        }
        assertEquals(expectedDigest, HexFormat.of().formatHex(digest.digest()));
    }

    /// Finds repetitive regions after an incompressible prefix without retaining the enlarged search stride.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 63, 64, 65, 4095, 65535, 131071})
    void findsMatchesAfterLongLiteralRuns(int prefixLength) throws IOException {
        byte[] source = content(prefixLength + 3 * 4096 + 5, 0, 0);
        for (int offset = prefixLength; offset < source.length - 5; offset++) {
            int relative = offset - prefixLength;
            source[offset] = (byte) (relative / 4096 * 64 + relative % 31);
        }
        byte[] encoded = LZ4BlockCompression.compress(source);
        // At least two thirds of the repetitive suffix must be saved despite the skipped prefix probes.
        assertTrue(encoded.length <= prefixLength + prefixLength / 255 + 4096,
                "encoded=" + encoded.length);
        assertArrayEquals(source, LZ4Factory.safeInstance().safeDecompressor().decompress(encoded, source.length));
        var decoded = LZ4BlockDecompression.decompress(encoded, source.length, -1, -1);
        assertArrayEquals(source, Arrays.copyOf(decoded.bytes(), decoded.length()));
    }

    /// Allows a dictionary match at the minimum block size while shorter blocks remain literal-only.
    @Test
    void preservesLastLegalMatchStart() {
        byte[] source = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
        byte[] dictionary = Arrays.copyOf(source, 7);
        assertArrayEquals(new byte[]{3, 7, 0, 0x50, 7, 8, 9, 10, 11},
                LZ4BlockCompression.compress(source, dictionary));
        byte[] shorter = Arrays.copyOf(source, 11);
        byte[] literals = new byte[12];
        literals[0] = (byte) 0xb0;
        System.arraycopy(shorter, 0, literals, 1, shorter.length);
        assertArrayEquals(literals, LZ4BlockCompression.compress(shorter, dictionary));
    }

    /// Creates random literals or a periodic sequence continuing from the dictionary offset.
    private static byte[] content(int size, int period, int offset) {
        byte[] bytes = new byte[size];
        if (period == 0) {
            new Random(42L + offset).nextBytes(bytes);
        } else {
            for (int index = 0; index < size; index++) bytes[index] = (byte) ((index + offset) % period);
        }
        return bytes;
    }
}
