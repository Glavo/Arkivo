// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies greedy match selection, tail boundaries, and dictionary truncation.
@NotNullByDefault
final class LZ4BlockCompressionTest {
    /// Checks encoded-byte stability and input immutability for literals and overlapping matches.
    @ParameterizedTest
    @CsvSource({
            "0, 21c2af72d4800669d576dde07b272873cb2cb93a2a13f9a10cbd08f82ea819c7",
            "1, 99a5fa6a965bb39eb2875ea2100c67ebcd3b305e4a0daa00aa00e9736f954f7e",
            "17, 579c275d70aff8c470afd744b022cad7c9f0bedb913d0c006970643b36dbd764",
            "65535, 05451867bb57594068e4f238d6550b675332cdd756466248c2880daab9a139f6",
            "65536, 9a8fa2aa244c0cbc6ff562e1bc56b168124846a24e5124d9a43585e121c479a0"
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
            }
        }
        assertEquals(expectedDigest, HexFormat.of().formatHex(digest.digest()));
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
