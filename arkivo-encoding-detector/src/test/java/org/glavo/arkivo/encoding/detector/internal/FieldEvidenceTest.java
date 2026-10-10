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

import static org.junit.jupiter.api.Assertions.*;

/// Verifies role isolation and bounded, deterministic metadata context.
@NotNullByDefault
class FieldEvidenceTest {
    /// Reduces duplicate names, parent-prefix changes, and numbered variants without retaining buffers.
    @Test
    void repetitionOrderAndLifetime() {
        var detector = new EncodingDetector();
        var charset = Charset.forName("windows-31j");
        var first = ByteBuffer.wrap("親/日本語_001.txt".getBytes(charset));
        var second = ByteBuffer.wrap("別/日本語_999.bin".getBytes(charset));
        var expected = detector.prepare(List.of(first), EncodingDetector.Kind.NAME);
        var combined = detector.prepare(List.of(first, second, first.asReadOnlyBuffer()), EncodingDetector.Kind.NAME);
        // The correct candidate sees identical basename features; wrong candidates may see different text.
        assertEquals(support(expected, charset), support(combined, charset));
        var inputs = new ArrayList<>(List.of(first, second));
        var snapshot = detector.prepare(inputs, EncodingDetector.Kind.NAME);
        Collections.reverse(inputs);
        assertEquals(snapshot.candidates(), detector.prepare(inputs, EncodingDetector.Kind.NAME).candidates());
        var saved = List.copyOf(snapshot.candidates());
        first.put(0, (byte) 'X');
        assertEquals(saved, snapshot.candidates());
        assertThrows(UnsupportedOperationException.class, snapshot.candidates()::clear);
    }

    /// Keeps names and comments separate and rejects accidental role mixing at selection.
    @Test
    void rolesAreNotInterchangeable() {
        var detector = new EncodingDetector();
        var input = ByteBuffer.wrap("é.txt".getBytes(Charset.forName("windows-1252")));
        var comments = detector.prepare(List.of(input), EncodingDetector.Kind.COMMENT);
        assertThrows(IllegalArgumentException.class, () -> detector.detect(input, EncodingDetector.Kind.NAME, comments));
    }

    /// Preserves evidence from valid fields when another field rejects that encoding.
    @Test
    void invalidFieldDoesNotVetoArchive() {
        var detector = new EncodingDetector();
        var utf8 = ByteBuffer.wrap("中日".getBytes(StandardCharsets.UTF_8));
        var invalid = ByteBuffer.wrap(new byte[]{(byte) 0xFF});
        var context = detector.prepare(List.of(utf8, invalid), EncodingDetector.Kind.NAME);
        assertEquals(1, support(context, StandardCharsets.UTF_8).fields());
        var invalidScores = detector.analyze(invalid, EncodingDetector.Kind.NAME);
        assertFalse(invalidScores.stream().anyMatch(value -> value.charset().equals(StandardCharsets.UTF_8)));
        assertNotEquals(StandardCharsets.UTF_8, detector.detect(invalid, EncodingDetector.Kind.NAME, context));
    }

    /// Checks all batch limits before deduplication; no oversized field is silently sampled.
    @Test
    void budgetsAndEmptyInput() {
        var detector = new EncodingDetector();
        var kind = EncodingDetector.Kind.NAME;
        assertTrue(detector.prepare(List.of(), kind).candidates().isEmpty());
        assertTrue(detector.prepare(Collections.nCopies(512, ByteBuffer.allocate(0)), kind).candidates().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> detector.prepare(Collections.nCopies(513, ByteBuffer.allocate(0)), kind));
        var maximum = ByteBuffer.allocate(4096);
        assertTrue(detector.prepare(Collections.nCopies(64, maximum), kind).candidates().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> detector.prepare(Collections.nCopies(65, maximum), kind));
        assertThrows(IllegalArgumentException.class, () -> detector.prepare(List.of(ByteBuffer.allocate(4097)), kind));
        assertEquals(0, maximum.position());
        assertThrows(NullPointerException.class, () -> detector.prepare(null, kind));
        assertThrows(NullPointerException.class, () -> detector.prepare(Collections.singletonList(null), kind));
    }

    /// Finds a support record without interpreting it as a probability or selected label.
    private static EncodingEvidence.Support support(EncodingEvidence context, Charset charset) {
        for (var value : context.candidates()) if (value.charset().equals(charset)) return value;
        throw new AssertionError("Missing context charset: " + charset);
    }
}
