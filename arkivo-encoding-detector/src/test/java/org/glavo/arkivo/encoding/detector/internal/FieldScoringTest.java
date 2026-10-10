// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.encoding.detector.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/// Exercises full-field validation, role-specific features, and reusable decoder state.
@NotNullByDefault
class FieldScoringTest {
    /// Leaves ASCII without statistical evidence and supplies no legacy fallback for uncertain input.
    @Test
    void asciiAndUnknown() {
        var detector = new EncodingDetector();
        for (String text : List.of("", "dir/001.txt", "()")) {
            var input = ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII));
            assertTrue(detector.analyze(input, EncodingDetector.Kind.NAME).isEmpty());
            assertEquals(StandardCharsets.US_ASCII, detector.detect(input, EncodingDetector.Kind.NAME));
        }
        assertNull(detector.detect(ByteBuffer.wrap(new byte[]{0, (byte) 0xFF}), EncodingDetector.Kind.NAME));
        assertThrows(NullPointerException.class, () -> detector.analyze(null, EncodingDetector.Kind.NAME));
        assertThrows(NullPointerException.class, () -> detector.detect(ByteBuffer.allocate(0), null));
    }

    /// Preserves positions, limits, marks, and snapshot contents across buffer representations.
    @Test
    void bufferStateAndSnapshots() {
        var detector = new EncodingDetector();
        byte[] bytes = "résumé.txt".getBytes(Charset.forName("IBM437"));
        var expected = detector.analyze(ByteBuffer.wrap(bytes), EncodingDetector.Kind.NAME);
        for (var storage : List.of(ByteBuffer.allocate(bytes.length + 4), ByteBuffer.allocateDirect(bytes.length + 4))) {
            storage.position(2).put(bytes).put((byte) 0xFF).limit(bytes.length + 2).position(2);
            for (var input : List.of(storage, storage.asReadOnlyBuffer(), storage.slice())) {
                input.mark();
                int position = input.position();
                int limit = input.limit();
                assertEquals(expected, detector.analyze(input, EncodingDetector.Kind.NAME));
                detector.detect(input, EncodingDetector.Kind.NAME);
                assertEquals(position, input.position());
                assertEquals(limit, input.limit());
                input.reset();
                assertEquals(position, input.position());
            }
            storage.put(2, (byte) 'X');
            assertEquals(expected, detector.analyze(ByteBuffer.wrap(bytes), EncodingDetector.Kind.NAME));
        }
        assertThrows(UnsupportedOperationException.class, expected::clear);
    }

    /// Keeps character validation independent of the score, including characters outside the Latin table.
    @Test
    void validityIsNotPlausibility() throws Exception {
        var detector = new EncodingDetector();
        var random = new Random(437852);
        for (int trial = 0; trial < 300; trial++) {
            byte[] bytes = new byte[1 + random.nextInt(80)];
            random.nextBytes(bytes);
            bytes[0] |= (byte) 0x80;
            var scores = detector.analyze(ByteBuffer.wrap(bytes), EncodingDetector.Kind.COMMENT);
            var seen = new java.util.HashSet<Charset>();
            for (var score : scores) {
                assertTrue(seen.add(score.charset()));
                score.charset().newDecoder().decode(ByteBuffer.wrap(bytes));
            }
            assertEquals(new EncodingDetector().analyze(ByteBuffer.wrap(bytes), EncodingDetector.Kind.COMMENT), scores);
            assertTrue(seen.contains(Charset.forName("IBM437")));
        }
    }

    /// Validates suffixes beyond the statistical budget and recovers cleanly after a malformed field.
    @Test
    void validatesCompleteField() {
        var detector = new EncodingDetector();
        byte[] bytes = ("é" + "a".repeat(20000)).getBytes(StandardCharsets.UTF_8);
        assertTrue(has(detector.analyze(ByteBuffer.wrap(bytes), EncodingDetector.Kind.COMMENT), "UTF-8"));
        bytes[bytes.length - 1] = (byte) 0xC3;
        assertFalse(has(detector.analyze(ByteBuffer.wrap(bytes), EncodingDetector.Kind.COMMENT), "UTF-8"));
        var next = ByteBuffer.wrap("éé".getBytes(StandardCharsets.UTF_8));
        assertEquals(new EncodingDetector().analyze(next, EncodingDetector.Kind.NAME), detector.analyze(next, EncodingDetector.Kind.NAME));
    }

    /// Preserves supplementary characters at the reusable output buffer boundary.
    @Test
    void supplementaryAndLongOutputs() {
        String text = "a".repeat(1023) + "𠀀😀" + "é".repeat(1200);
        var input = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
        var scorer = new MetadataScorer(StandardCharsets.UTF_8, null, MetadataScorer.Family.UTF8);
        assertEquals(scorer.score(input, EncodingDetector.Kind.COMMENT, CharBuffer.allocate(1024)),
                scorer.score(input, EncodingDetector.Kind.COMMENT, CharBuffer.allocate(3)));
        assertThrows(IllegalArgumentException.class,
                () -> scorer.score(input, EncodingDetector.Kind.COMMENT, CharBuffer.allocate(1)));
    }

    /// Compares equivalent candidate outputs beyond both the decoding buffer and scoring budget.
    @Test
    void outputEquivalenceRequiresTheWholeField() throws Exception {
        var detector = new EncodingDetector();
        var charset = Charset.forName("IBM437");
        String text = "résumé" + "a".repeat(5000);
        byte[] bytes = text.getBytes(charset);
        var input = ByteBuffer.wrap(bytes);
        @Nullable var selected = detector.detect(input, EncodingDetector.Kind.NAME);
        assertNotNull(selected);
        assertEquals(text, selected.newDecoder().decode(input.duplicate()).toString());
        // CP437 and CP850 agree on the scored prefix but differ on this unscored final byte.
        bytes[bytes.length - 1] = (byte) 0x9B;
        assertNull(detector.detect(input, EncodingDetector.Kind.NAME));
        bytes[bytes.length - 1] = 'a';
        assertEquals(selected, detector.detect(input, EncodingDetector.Kind.NAME));
    }

    /// Keeps repertoire byte tracking aligned across multibyte and supplementary scalar boundaries.
    @Test
    void multibyteRepertoireBoundaries() throws Exception {
        for (String name : List.of("GB18030", "Big5-HKSCS", "windows-31j", "EUC-JP", "x-windows-949")) {
            var charset = Charset.forName(name);
            var encoder = charset.newEncoder();
            var text = new StringBuilder("a".repeat(1023));
            for (String part : List.of("中", "𠀀", "😀", "ソ", "˘", "가", "힣", "日", "A", "ｿ", "é", "한")) {
                if (encoder.canEncode(part)) text.append(part);
            }
            var input = encoder.encode(CharBuffer.wrap(text.toString().repeat(200)));
            var family = switch (name) {
                case "GB18030" -> MetadataScorer.Family.CHINESE;
                case "Big5-HKSCS" -> MetadataScorer.Family.BIG5;
                case "x-windows-949" -> MetadataScorer.Family.KOREAN;
                default -> MetadataScorer.Family.JAPANESE;
            };
            var scorer = new MetadataScorer(charset, null, family);
            @Nullable var expected = scorer.score(input, EncodingDetector.Kind.COMMENT, CharBuffer.allocate(1024));
            assertNotNull(expected);
            assertEquals(expected, scorer.score(input, EncodingDetector.Kind.COMMENT, CharBuffer.allocate(3)), name);
        }
    }

    /// Does not join incomplete characters in successive fields.
    @Test
    void fieldsAreIndependent() {
        var detector = new EncodingDetector();
        for (byte value : new byte[]{(byte) 0xC3, (byte) 0xA9, (byte) 0xC3}) {
            assertFalse(has(detector.analyze(ByteBuffer.wrap(new byte[]{value}), EncodingDetector.Kind.NAME), "UTF-8"));
        }
        assertTrue(has(detector.analyze(ByteBuffer.wrap(new byte[]{(byte) 0xC3, (byte) 0xA9}), EncodingDetector.Kind.NAME), "UTF-8"));
    }

    /// Uses decoded path components and does not interpret Shift-JIS trail byte 0x5c as a separator.
    @Test
    void decodedNameBoundaries() {
        var charset = Charset.forName("windows-31j");
        var detector = new EncodingDetector();
        var bare = score(detector, "ソ日本語", charset, EncodingDetector.Kind.NAME);
        assertEquals(bare, score(detector, "親/ソ日本語_001.txt", charset, EncodingDetector.Kind.NAME));
        assertEquals(bare, score(detector, "別の親/ソ日本語_999.bin", charset, EncodingDetector.Kind.NAME));
        assertEquals(score(detector, "親", charset, EncodingDetector.Kind.NAME),
                score(detector, "親/readme.txt", charset, EncodingDetector.Kind.NAME));
        assertNotEquals(bare, score(detector, "親/ソ日本語_001.txt", charset, EncodingDetector.Kind.COMMENT));
    }

    /// Caps repetitive features without treating the cap as an input-validation limit.
    @Test
    void repeatedFeaturesAreBounded() {
        var detector = new EncodingDetector();
        assertEquals(score(detector, "中中", StandardCharsets.UTF_8, EncodingDetector.Kind.NAME),
                score(detector, "中".repeat(20000), StandardCharsets.UTF_8, EncodingDetector.Kind.NAME));
        assertTrue(score(detector, "中中", StandardCharsets.UTF_8, EncodingDetector.Kind.NAME).evidence() > 0);
    }

    /// Shares immutable model tables while keeping separate detector workspaces independent.
    @Test
    void concurrentInstances() throws Exception {
        var input = ByteBuffer.wrap("résumé".getBytes(Charset.forName("IBM437")));
        var expected = new EncodingDetector().analyze(input, EncodingDetector.Kind.NAME);
        var executor = Executors.newFixedThreadPool(4);
        try {
            Callable<List<EncodingScore>> operation = () -> new EncodingDetector().analyze(input, EncodingDetector.Kind.NAME);
            for (var future : executor.invokeAll(java.util.Collections.nCopies(24, operation)))
                assertEquals(expected, future.get());
        } finally {
            executor.shutdownNow();
        }
    }

    /// Returns a candidate's evidence for strictly encodable test text.
    private static EncodingScore score(EncodingDetector detector, String text, Charset charset, EncodingDetector.Kind kind) {
        assertTrue(charset.newEncoder().canEncode(text));
        for (var value : detector.analyze(ByteBuffer.wrap(text.getBytes(charset)), kind)) {
            if (value.charset().equals(charset)) return value;
        }
        throw new AssertionError("Missing valid charset: " + charset);
    }

    /// Tests whether strict validation retained a charset.
    private static boolean has(List<EncodingScore> scores, String name) {
        return scores.stream().anyMatch(value -> value.charset().equals(Charset.forName(name)));
    }
}
