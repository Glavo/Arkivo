// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.encoding.detector.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;

import static org.junit.jupiter.api.Assertions.*;

/// Checks DOS byte mappings independently of charset selection accuracy.
@NotNullByDefault
class DosModelTest {
    /// Transfers classes by decoded character identity, never by the numerical byte value.
    @Test
    void characterClassesMatchSourceModels() throws Exception {
        for (int index = 0; index < DosModels.VALUES.length; index++) {
            var target = DosModels.VALUES[index];
            var source = Models.SINGLE_BYTE_DATA[index == 2 ? 1 : 7];
            assertSame(source.probabilities(), target.probabilities());
            assertSame(source.lower(), target.lower());
            var encoder = Charset.forName(source.charsetName()).newEncoder();
            var decoder = Charset.forName(target.charsetName()).newDecoder();
            for (int value = 0; value < 256; value++) {
                var character = decoder.decode(ByteBuffer.wrap(new byte[]{(byte) value}));
                int expected = source.ascii() + source.nonAscii();
                try {
                    var encoded = encoder.encode(character);
                    if (encoded.remaining() == 1) {
                        expected = source.classify(encoded.get() & 255);
                    }
                } catch (CharacterCodingException ignored) {
                    // Characters outside the source alphabet have no trained class.
                }
                assertEquals(expected, target.classify(value), target.charsetName() + " byte=" + value);
            }
        }
    }

    /// Preserves language scores for shared Latin text without adding per-encoding bonuses.
    @Test
    void sharedTextHasIdenticalScores() {
        for (int index = 0; index < DosModels.VALUES.length; index++) {
            var source = Models.SINGLE_BYTE_DATA[index == 2 ? 1 : 7];
            var target = DosModels.VALUES[index];
            String text = index == 2 ? "Łódź/żółć_01.txt" : "résumé/München_01.txt";
            // Remapping the source to itself disables Windows-specific ordinal heuristics on both sides.
            assertEquals(score(source.remap(source.charsetName()), text), score(target, text));
        }
    }

    /// Scores a representable string with a freshly initialized Latin candidate.
    private static long score(SingleByteData data, String text) {
        var charset = Charset.forName(data.charsetName());
        assertTrue(charset.newEncoder().canEncode(text));
        var candidate = new LatinCandidate(data);
        for (byte value : text.getBytes(charset)) {
            candidate.accept(value & 255);
        }
        candidate.finish();
        assertNotEquals(Candidate.INVALID, candidate.score);
        return candidate.score;
    }

    /// Excludes an unavailable provider even though neutral classes can be constructed.
    @Test
    void unavailableCharset() {
        var model = Models.SINGLE_BYTE_DATA[7].remap("x-arkivo-unavailable-charset");
        var candidate = new LatinCandidate(model);
        candidate.accept('a');
        candidate.finish();
        assertEquals(Candidate.INVALID, candidate.score);
        for (int value = 128; value < 256; value++) {
            assertEquals(255, model.classify(value));
        }
    }
}
