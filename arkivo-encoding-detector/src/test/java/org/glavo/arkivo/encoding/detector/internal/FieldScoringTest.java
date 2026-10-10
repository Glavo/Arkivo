// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.encoding.detector.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies independent metadata scoring without prescribing a charset selection policy.
@NotNullByDefault
class FieldScoringTest {
    /// Leaves empty and ASCII-only fields without language evidence.
    @Test
    void asciiHasNoEvidence() {
        assertTrue(DetectionEngine.scoreField(ByteBuffer.allocate(0)).isEmpty());
        assertTrue(DetectionEngine.scoreField(ByteBuffer.wrap("dir/001.txt".getBytes(StandardCharsets.US_ASCII))).isEmpty());
        assertThrows(NullPointerException.class, () -> DetectionEngine.scoreField(null));
    }

    /// Preserves caller state on heap, direct, read-only, and sliced buffers.
    @Test
    void bufferStateAndSnapshot() {
        byte[] bytes = "résumé.txt".getBytes(Charset.forName("IBM437"));
        var expected = DetectionEngine.scoreField(ByteBuffer.wrap(bytes));
        assertFalse(expected.isEmpty());
        for (var storage : List.of(ByteBuffer.allocate(bytes.length + 4), ByteBuffer.allocateDirect(bytes.length + 4))) {
            storage.position(2).put(bytes).put((byte) 0xFF);
            storage.limit(bytes.length + 2).position(2).mark();
            for (var input : List.of(storage, storage.asReadOnlyBuffer(), storage.slice())) {
                input.mark();
                int position = input.position();
                int limit = input.limit();
                var scores = DetectionEngine.scoreField(input);
                assertEquals(expected, scores);
                assertEquals(position, input.position());
                assertEquals(limit, input.limit());
                input.reset();
                assertEquals(position, input.position());
                assertThrows(UnsupportedOperationException.class, scores::clear);
            }
            storage.put(2, (byte) 'X');
            assertEquals(expected, DetectionEngine.scoreField(ByteBuffer.wrap(bytes)));
        }
    }

    /// Keeps single-letter evidence that the continuous prose detector cannot select.
    @Test
    void shortWordEvidence() {
        var charset = Charset.forName("windows-1251");
        byte[] input = "я".getBytes(charset);
        var candidate = new NonLatinCasedCandidate(Models.SINGLE_BYTE_DATA[3]);
        candidate.accept(input[0] & 255);
        candidate.finish();
        assertEquals(Candidate.INVALID, candidate.eligibleScore());
        assertNotEquals(Candidate.INVALID, candidate.score);
        assertTrue(DetectionEngine.scoreField(ByteBuffer.wrap(input))
                .contains(new EncodingScore(charset, candidate.score)));
    }

    /// Does not complete a multibyte character using bytes from the next field.
    @Test
    void boundariesAndCallOrder() {
        var whole = ByteBuffer.wrap(new byte[]{(byte) 0xC3, (byte) 0xA9});
        assertTrue(hasUtf8(DetectionEngine.scoreField(whole)));
        var first = ByteBuffer.wrap(new byte[]{(byte) 0xC3});
        var second = ByteBuffer.wrap(new byte[]{(byte) 0xA9});
        var before = DetectionEngine.scoreField(first);
        assertFalse(hasUtf8(before));
        assertFalse(hasUtf8(DetectionEngine.scoreField(second)));
        assertEquals(before, DetectionEngine.scoreField(first));
        byte[] longField = new byte[8193];
        java.util.Arrays.fill(longField, (byte) 'a');
        longField[0] = (byte) 0xC3;
        longField[1] = (byte) 0xA9;
        longField[longField.length - 1] = (byte) 0xC3;
        assertFalse(hasUtf8(DetectionEngine.scoreField(ByteBuffer.wrap(longField))));
    }

    /// Checks UTF-8 membership rather than forcing a ranking policy.
    private static boolean hasUtf8(List<EncodingScore> scores) {
        return scores.stream().anyMatch(score -> score.charset().equals(StandardCharsets.UTF_8));
    }

    /// Uses the strongest of the Western and Icelandic models instead of counting one charset twice.
    @Test
    void alternativeModelsAreCombinedByMaximum() {
        byte[] bytes = "Þór.txt".getBytes(Charset.forName("windows-1252"));
        var western = new LatinCandidate(Models.SINGLE_BYTE_DATA[7]);
        var icelandic = new LatinCandidate(Models.SINGLE_BYTE_DATA[8]);
        for (byte value : bytes) {
            western.accept(value & 255);
            icelandic.accept(value & 255);
        }
        western.finish();
        icelandic.finish();
        var expected = new EncodingScore(Charset.forName("windows-1252"), Math.max(western.score, icelandic.score));
        var scores = DetectionEngine.scoreField(ByteBuffer.wrap(bytes));
        assertTrue(scores.contains(expected));
        assertEquals(1, scores.stream().filter(score -> score.charset().equals(expected.charset())).count());
    }

    /// Requires every returned encoding to accept the whole field and appear only once.
    @Test
    void strictValidityAndUniqueCharsets() {
        var random = new Random(437852);
        for (int trial = 0; trial < 300; trial++) {
            byte[] bytes = new byte[random.nextInt(65)];
            random.nextBytes(bytes);
            var seen = new HashSet<Charset>();
            for (var result : DetectionEngine.scoreField(ByteBuffer.wrap(bytes))) {
                assertTrue(seen.add(result.charset()));
                assertNotEquals(Candidate.INVALID, result.score());
                assertTrue(ModelTest.valid(result.charset(), bytes), result.charset().name());
            }
        }
    }

    /// Keeps different calls' mutable scoring state isolated across threads.
    @Test
    void concurrentFields() throws Exception {
        byte[] bytes = "資料/説明.txt".getBytes(Charset.forName("windows-31j"));
        var expected = DetectionEngine.scoreField(ByteBuffer.wrap(bytes));
        var executor = Executors.newFixedThreadPool(4);
        try {
            Callable<List<EncodingScore>> operation = () -> DetectionEngine.scoreField(ByteBuffer.wrap(bytes));
            for (var result : executor.invokeAll(java.util.Collections.nCopies(32, operation))) {
                assertEquals(expected, result.get());
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
