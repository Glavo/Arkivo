// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.encoding.detector.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/// Checks bounded evidence collection independently of any archive selection policy.
@NotNullByDefault
class FieldEvidenceTest {
    /// Adds independently completed scores without spanning character or word boundaries.
    @Test
    void equalsIndependentScores() {
        var inputs = List.of(ByteBuffer.wrap(new byte[]{(byte) 0xC3}),
                ByteBuffer.wrap(new byte[]{(byte) 0xA9}),
                ByteBuffer.wrap("日本語".getBytes(Charset.forName("windows-31j"))));
        var expected = new TreeMap<Charset, EncodingEvidence>();
        for (var input : inputs) {
            for (var score : DetectionEngine.scoreField(input)) {
                var previous = expected.getOrDefault(score.charset(), new EncodingEvidence(score.charset(), 0, 0));
                expected.put(score.charset(), new EncodingEvidence(score.charset(),
                        previous.score() + score.score(), previous.fields() + 1));
            }
        }
        assertEquals(List.copyOf(expected.values()), DetectionEngine.scoreFields(inputs));
        assertFalse(expected.containsKey(StandardCharsets.UTF_8));
    }

    /// Collapses exact byte duplicates across buffer representations and preserves order independence.
    @Test
    void deduplicationOrderAndLifetime() {
        byte[] bytes = "résumé".getBytes(Charset.forName("IBM437"));
        var first = ByteBuffer.allocateDirect(bytes.length + 4);
        first.position(2).put(bytes).limit(bytes.length + 2).position(2).mark();
        var second = ByteBuffer.wrap("日本語".getBytes(Charset.forName("windows-31j")));
        var inputs = new ArrayList<>(List.of(first, second, ByteBuffer.wrap(bytes), first.slice().asReadOnlyBuffer()));
        var snapshot = DetectionEngine.scoreFields(inputs);
        assertEquals(DetectionEngine.scoreFields(List.of(first, second)), snapshot);
        Collections.reverse(inputs);
        assertEquals(snapshot, DetectionEngine.scoreFields(inputs));
        first.reset();
        assertEquals(2, first.position());
        first.put(2, (byte) 'X');
        assertEquals(DetectionEngine.scoreFields(List.of(ByteBuffer.wrap(bytes), second)), snapshot);
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
    }

    /// Keeps UTF-8 evidence from one field even when another field is not valid UTF-8.
    @Test
    void invalidFieldsDoNotEliminateOtherEvidence() {
        var valid = ByteBuffer.wrap("中".getBytes(StandardCharsets.UTF_8));
        var invalid = ByteBuffer.wrap(new byte[]{(byte) 0xFF});
        var expected = DetectionEngine.scoreFields(List.of(valid)).stream()
                .filter(value -> value.charset().equals(StandardCharsets.UTF_8)).toList();
        assertEquals(1, expected.size());
        assertEquals(expected, DetectionEngine.scoreFields(List.of(invalid, valid)).stream()
                .filter(value -> value.charset().equals(StandardCharsets.UTF_8)).toList());
    }

    /// Compares full remaining bytes when two different fields share a buffer hash code.
    @Test
    void hashCollisionsDoNotDiscardFields() {
        var first = ByteBuffer.wrap(new byte[]{(byte) 0x80, (byte) 0x81});
        var second = ByteBuffer.wrap(new byte[]{(byte) 0x9F, (byte) 0x80});
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, second);
        var dos = DetectionEngine.scoreFields(List.of(first, second)).stream()
                .filter(value -> value.charset().equals(Charset.forName("IBM437"))).toList();
        assertEquals(1, dos.size());
        assertEquals(2, dos.get(0).fields());
    }

    /// Checks exact limits before deduplication and rejects over-budget input without moving buffers.
    @Test
    void budgetsAndEmptyInput() {
        assertTrue(DetectionEngine.scoreFields(List.of()).isEmpty());
        assertTrue(DetectionEngine.scoreFields(Collections.nCopies(512, ByteBuffer.allocate(0))).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> DetectionEngine.scoreFields(Collections.nCopies(513, ByteBuffer.allocate(0))));
        var maximum = ByteBuffer.allocate(4096);
        assertTrue(DetectionEngine.scoreFields(Collections.nCopies(64, maximum)).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> DetectionEngine.scoreFields(Collections.nCopies(65, maximum)));
        assertThrows(IllegalArgumentException.class,
                () -> DetectionEngine.scoreFields(List.of(ByteBuffer.allocate(4097))));
        assertEquals(0, maximum.position());
        assertThrows(NullPointerException.class, () -> DetectionEngine.scoreFields(null));
        assertThrows(NullPointerException.class,
                () -> DetectionEngine.scoreFields(Collections.singletonList(null)));
    }
}
