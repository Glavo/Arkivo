// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.encoding.detector.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies the sparse Kala port independently of its measured filename recognition accuracy.
@NotNullByDefault
class KalaMetadataDetectorTest {
    /// Preserves upstream weights and projected cosine scores against a dense reference calculation.
    @Test
    void sparseModelsMatchDenseReference() throws Exception {
        var models = KalaModels.instance();
        var byName = new TreeMap<String, KalaModels.Model>();
        models.candidates.values().forEach(variants -> variants.forEach(model -> byName.put(model.name(), model)));
        byte[] sample = new byte[137];
        new Random(723047).nextBytes(sample);
        var profile = KalaMetadataDetector.profile(ByteBuffer.wrap(sample), EncodingDetector.Kind.COMMENT);
        try (var input = new DataInputStream(Objects.requireNonNull(getClass()
                .getResourceAsStream("/kala/encdet/internal/models.bin")))) {
            assertEquals(0x434d4432, input.readInt());
            int count = input.readInt();
            var names = new ArrayList<String>();
            for (int i = 0; i < count; i++) {
                names.add(new String(input.readNBytes(input.readInt()), StandardCharsets.UTF_8));
                input.readDouble();
            }
            int checked = 0;
            try (var inflated = new DataInputStream(new InflaterInputStream(input))) {
                byte[] row = new byte[65536];
                for (var name : names) {
                    inflated.readFully(row);
                    @Nullable var sparse = byName.get(name);
                    if (sparse == null) continue;
                    checked++;
                    long squared = 0;
                    for (int pair = 0; pair < row.length; pair++) {
                        boolean nonAscii = (pair >>> 8) >= 128 || (pair & 255) >= 128;
                        int expected = nonAscii ? row[pair] & 255 : 0;
                        assertEquals(expected, sparse.weight(pair));
                        squared += (long) expected * expected;
                    }
                    assertEquals(Math.sqrt(squared), sparse.norm());
                    long dot = 0;
                    for (int i = 0; i < profile.pairs().length; i++) {
                        dot += (long) (row[profile.pairs()[i]] & 255) * profile.frequencies()[i];
                    }
                    double expected = squared == 0 ? 0 : dot / (Math.sqrt(squared) * profile.norm());
                    assertEquals(expected, sparse.score(profile.pairs(), profile.frequencies(), profile.norm()), 1e-15);
                }
                assertEquals(-1, inflated.read());
            }
            assertEquals(byName.size(), checked);
        }
        System.out.printf(java.util.Locale.ROOT, "kala_candidates=%d,kala_language_models=%d,sparse_array_bytes=%d%n",
                models.candidates.size(), byName.size(), models.payloadBytes());
        assertTrue(models.payloadBytes() < 4 * 1024 * 1024,
                () -> "Unexpected sparse payload: " + models.payloadBytes());
    }

    /// Checks sparse counts against a separate map implementation, including repetitions and input windows.
    @Test
    void profilesMatchIndependentCounts() {
        var random = new Random(909);
        for (int trial = 0; trial < 100; trial++) {
            byte[] raw = new byte[5000];
            random.nextBytes(raw);
            var input = ByteBuffer.wrap(raw, 3, raw.length - 7);
            var expected = new TreeMap<Integer, Integer>();
            int end = input.position() + Math.min(input.remaining(), KalaMetadataDetector.MAX_PROFILE_BYTES);
            for (int i = input.position() + 1; i < end; i++) {
                int first = raw[i - 1] & 255;
                int second = raw[i] & 255;
                if (first >= 128 || second >= 128) expected.merge(first * 256 + second, 1, Integer::sum);
            }
            var models = KalaModels.instance();
            expected.replaceAll((pair, count) -> Math.min(count, 2) * models.idf(pair));
            expected.values().removeIf(value -> value == 0);
            var actual = KalaMetadataDetector.profile(input, EncodingDetector.Kind.COMMENT);
            assertEquals(expected.size(), actual.pairs().length);
            int index = 0;
            long squared = 0;
            for (var entry : expected.entrySet()) {
                assertEquals(entry.getKey().intValue(), actual.pairs()[index]);
                assertEquals(entry.getValue().intValue(), actual.frequencies()[index++]);
                squared += (long) entry.getValue() * entry.getValue();
            }
            assertEquals(Math.sqrt(squared), actual.norm());
        }
    }

    /// Keeps ASCII independent of candidates and excludes synthetic pairs between distinct fields.
    @Test
    void asciiAndFieldBoundaries() throws Exception {
        var detector = new KalaMetadataDetector();
        var ascii = ByteBuffer.allocate(128);
        for (int value = 0; value < 128; value++) ascii.put((byte) value);
        ascii.flip().mark();
        assertEquals(StandardCharsets.US_ASCII, detector.detect(ascii, EncodingDetector.Kind.NAME, null));
        assertTrue(detector.analyze(ascii, EncodingDetector.Kind.NAME, null).isEmpty());
        assertEquals(KalaMetadataDetector.Profile.EMPTY,
                detector.prepare(List.of(ascii), EncodingDetector.Kind.NAME).profile());
        var field = KalaMetadataDetector.class.getDeclaredField("candidates");
        field.setAccessible(true);
        assertNull(field.get(detector));
        assertEquals(0, ascii.reset().position());
        var isolated = detector.prepare(List.of(ByteBuffer.wrap(new byte[]{(byte) 0xC3}),
                ByteBuffer.wrap(new byte[]{(byte) 0xA9})), EncodingDetector.Kind.COMMENT);
        assertEquals(KalaMetadataDetector.Profile.EMPTY, isolated.profile());
        assertNotEquals(KalaMetadataDetector.Profile.EMPTY, KalaMetadataDetector.profile(
                ByteBuffer.wrap(new byte[]{(byte) 0xC3, (byte) 0xA9}), EncodingDetector.Kind.COMMENT));
    }

    /// Preserves full-field validity beyond the profile limit and resets decoders after failure.
    @Test
    void strictValidationAndReuse() throws Exception {
        var detector = new KalaMetadataDetector();
        byte[] raw = ("é" + "a".repeat(20000)).getBytes(StandardCharsets.UTF_8);
        var input = ByteBuffer.wrap(raw);
        assertTrue(hasUtf8(detector.analyze(input, EncodingDetector.Kind.NAME, null)));
        raw[raw.length - 1] = (byte) 0xC3;
        assertFalse(hasUtf8(detector.analyze(input, EncodingDetector.Kind.NAME, null)));
        var next = ByteBuffer.wrap("日本語😀".getBytes(StandardCharsets.UTF_8));
        assertEquals(new KalaMetadataDetector().analyze(next, EncodingDetector.Kind.NAME, null),
                detector.analyze(next, EncodingDetector.Kind.NAME, null));
        var random = new Random(707165);
        for (int i = 0; i < 50; i++) {
            byte[] bytes = new byte[1 + random.nextInt(100)];
            random.nextBytes(bytes);
            bytes[0] |= (byte) 128;
            for (var score : detector.analyze(ByteBuffer.wrap(bytes), EncodingDetector.Kind.COMMENT, null)) {
                score.charset().newDecoder().decode(ByteBuffer.wrap(bytes));
                assertTrue(score.similarity() >= 0 && score.similarity() <= 1.000000000000001);
            }
        }
        var controls = ByteBuffer.wrap("é\t.txt".getBytes(StandardCharsets.UTF_8));
        assertFalse(hasUtf8(detector.analyze(controls, EncodingDetector.Kind.NAME, null)));
        assertTrue(hasUtf8(detector.analyze(controls, EncodingDetector.Kind.COMMENT, null)));
    }

    /// Keeps scores, positions, marks, and frozen context stable across heap/direct/read-only views.
    @Test
    void bufferOwnershipAndDeterminism() {
        var detector = new KalaMetadataDetector();
        byte[] raw = "資料/日本語.txt".getBytes(Charset.forName("windows-31j"));
        var expected = detector.analyze(ByteBuffer.wrap(raw), EncodingDetector.Kind.NAME, null);
        for (var storage : List.of(ByteBuffer.allocate(raw.length + 4), ByteBuffer.allocateDirect(raw.length + 4))) {
            storage.position(2).put(raw).put((byte) 0xFF).limit(raw.length + 2).position(2);
            for (var view : List.of(storage, storage.asReadOnlyBuffer(), storage.slice())) {
                int position = view.position();
                int limit = view.limit();
                view.mark();
                assertEquals(expected, detector.analyze(view, EncodingDetector.Kind.NAME, null));
                detector.detect(view, EncodingDetector.Kind.NAME, null);
                assertEquals(position, view.position());
                assertEquals(limit, view.limit());
                assertEquals(position, view.reset().position());
            }
        }
        var fields = new ArrayList<>(List.of(ByteBuffer.wrap(raw), utf8("résumé"), utf8("文書")));
        var context = detector.prepare(fields, EncodingDetector.Kind.NAME);
        Collections.reverse(fields);
        fields.add(utf8("résumé"));
        fields.add(utf8("ascii.txt"));
        assertEquals(context, detector.prepare(fields, EncodingDetector.Kind.NAME));
        char[] retained = context.profile().pairs().clone();
        Arrays.fill(raw, (byte) 'A');
        assertArrayEquals(retained, context.profile().pairs());
        assertThrows(IllegalArgumentException.class, () -> detector.detect(utf8("résumé"),
                EncodingDetector.Kind.COMMENT, context));
        assertThrows(UnsupportedOperationException.class, expected::clear);
    }

    /// Ignores ASCII suffix variation and repeated raw parents while preserving field boundaries.
    @Test
    void repetitionAndParentPrefixes() {
        var detector = new KalaMetadataDetector();
        var one = detector.prepare(List.of(utf8("dir/日本語_001.txt")), EncodingDetector.Kind.NAME);
        var repeated = detector.prepare(List.of(utf8("dir/日本語_001.txt"), utf8("dir/日本語_999.bin")),
                EncodingDetector.Kind.NAME);
        assertEquals(one, repeated);
        assertEquals(KalaMetadataDetector.profile(utf8("日本語.txt"), EncodingDetector.Kind.NAME),
                KalaMetadataDetector.profile(utf8("文書/文書/文書/日本語.txt"), EncodingDetector.Kind.NAME));
        assertEquals(KalaMetadataDetector.profile(utf8("文書"), EncodingDetector.Kind.NAME),
                KalaMetadataDetector.profile(utf8("文書/file.txt"), EncodingDetector.Kind.NAME));
        assertEquals(KalaMetadataDetector.profile(utf8("éééé"), EncodingDetector.Kind.COMMENT),
                KalaMetadataDetector.profile(utf8("é".repeat(100)), EncodingDetector.Kind.COMMENT));
    }

    /// Enforces all batch limits before allocating a joint workspace.
    @Test
    void batchBudgets() {
        var detector = new KalaMetadataDetector();
        assertThrows(IllegalArgumentException.class, () -> detector.prepare(
                Collections.nCopies(513, ByteBuffer.allocate(0)), EncodingDetector.Kind.NAME));
        assertThrows(IllegalArgumentException.class, () -> detector.prepare(
                List.of(ByteBuffer.allocate(4097)), EncodingDetector.Kind.NAME));
        assertThrows(IllegalArgumentException.class, () -> detector.prepare(
                Collections.nCopies(65, ByteBuffer.allocate(4096)), EncodingDetector.Kind.NAME));
        assertEquals(KalaMetadataDetector.Profile.EMPTY, detector.prepare(
                Collections.nCopies(64, ByteBuffer.allocate(4096)), EncodingDetector.Kind.NAME).profile());
    }

    /// Shares only immutable models across independent detector instances.
    @Test
    void independentConcurrentWorkspaces() throws Exception {
        var expected = new KalaMetadataDetector().analyze(utf8("日本語.txt"), EncodingDetector.Kind.NAME, null);
        var executor = Executors.newFixedThreadPool(4);
        try {
            var calls = new ArrayList<Callable<List<KalaMetadataDetector.Score>>>();
            for (int i = 0; i < 16; i++) calls.add(() -> new KalaMetadataDetector().analyze(
                    utf8("日本語.txt"), EncodingDetector.Kind.NAME, null));
            for (var future : executor.invokeAll(calls)) assertEquals(expected, future.get());
        } finally {
            executor.shutdownNow();
        }
    }

    /// Keeps a strong archive profile from admitting a locally malformed candidate.
    @Test
    void contextCannotOverrideValidity() {
        var detector = new KalaMetadataDetector();
        var context = detector.prepare(List.of(utf8("日本語.txt"), utf8("文書.txt")), EncodingDetector.Kind.NAME);
        var input = ByteBuffer.wrap("中文.txt".getBytes(Charset.forName("GB18030")));
        var scores = detector.analyze(input, EncodingDetector.Kind.NAME, context);
        assertFalse(hasUtf8(scores));
        assertTrue(scores.stream().anyMatch(score -> score.charset().equals(Charset.forName("GB18030"))));
        assertEquals(context, detector.prepare(List.of(utf8("文書.txt"), utf8("日本語.txt")), EncodingDetector.Kind.NAME));
    }

    /// Tests presence independently of a candidate's rank.
    private static boolean hasUtf8(List<KalaMetadataDetector.Score> scores) {
        return scores.stream().anyMatch(score -> score.charset().equals(StandardCharsets.UTF_8));
    }

    /// Creates an owned UTF-8 probe without replacement characters.
    private static ByteBuffer utf8(String text) {
        return ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
    }
}
