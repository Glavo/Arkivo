// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.encoding.detector.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Compares every one- and two-byte sequence with strict whole-buffer JDK decoding.
@NotNullByDefault
class ByteDecoderExhaustiveTest {
    /// Checks rejection and incomplete-sequence handling independently of guessed labels.
    @ParameterizedTest
    @ValueSource(strings = {"UTF-8", "GB18030", "Big5-HKSCS", "windows-31j", "EUC-JP",
            "x-windows-949", "ISO-2022-JP"})
    void allBytePairs(String name) {
        var charset = Charset.forName(name);
        var reference = charset.newDecoder();
        for (int size = 1; size <= 2; size++) {
            var bytes = new byte[size];
            var input = ByteBuffer.wrap(bytes);
            for (int value = 0; value < (1 << (size * 8)); value++) {
                bytes[0] = (byte) value;
                if (size == 2) {
                    bytes[1] = (byte) (value >>> 8);
                }
                input.clear();
                boolean expected;
                try {
                    reference.decode(input);
                    expected = true;
                } catch (CharacterCodingException exception) {
                    expected = false;
                }
                var decoder = new ByteDecoder(charset);
                boolean accepted = true;
                for (byte b : bytes) {
                    accepted &= decoder.accept(b & 255) >= 0;
                }
                accepted &= decoder.finish();
                assertEquals(expected, accepted, name + " length=" + size + " bytes=" + value);
            }
        }
    }
}
