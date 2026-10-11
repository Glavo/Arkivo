// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.encoding.detector.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies common-encoding scoring independently of ZIP declarations and fallback policy.
@NotNullByDefault
class EncodingDetectorTest {
    /// ASCII fields neither allocate candidate decoders nor initialize model-dependent state.
    @Test
    void asciiDoesNotAllocateCandidates() throws Exception {
        var detector = new EncodingDetector();
        var bytes = StandardCharsets.US_ASCII.encode("dir/file.txt");
        assertEquals("dir/file.txt", detector.decode(bytes, EncodingDetector.Kind.NAME,
                EncodingDetector.Preference.NONE));
        assertTrue(detector.rank(List.of(bytes), EncodingDetector.Kind.NAME, EncodingDetector.Preference.NONE).isEmpty());
        var candidates = EncodingDetector.class.getDeclaredField("candidates");
        candidates.setAccessible(true);
        assertNull(candidates.get(detector));
    }

    /// ASCII detection succeeds in an isolated loader without the model resource.
    @Test
    void asciiDoesNotLoadModels() throws Exception {
        // Gradle keeps compiled classes and resources in separate directories.
        URL classes = EncodingDetector.class.getProtectionDomain().getCodeSource().getLocation();
        try (var loader = new URLClassLoader(new URL[]{classes}, ClassLoader.getPlatformClassLoader())) {
            String packageName = EncodingDetector.class.getPackageName() + ".";
            assertNull(loader.getResource(packageName.replace('.', '/') + "bigram-models.bin"));
            var type = loader.loadClass(EncodingDetector.class.getName());
            var kind = loader.loadClass(packageName + "EncodingDetector$Kind");
            var preference = loader.loadClass(packageName + "EncodingDetector$Preference");
            var detector = type.getConstructor().newInstance();
            var bytes = StandardCharsets.US_ASCII.encode("dir/file.txt");
            var name = kind.getField("NAME").get(null);
            var none = preference.getField("NONE").get(null);
            assertEquals("dir/file.txt", type.getMethod("decode", ByteBuffer.class, kind, preference)
                    .invoke(detector, bytes, name, none));
            assertEquals(List.of(), type.getMethod("rank", List.class, kind, preference)
                    .invoke(detector, List.of(bytes), name, none));
            assertThrows(ExceptionInInitializerError.class,
                    () -> Class.forName(packageName + "BigramModels", true, loader));
        }
    }

    /// Pools profiles without introducing pairs at a field boundary or multiplying duplicate evidence.
    @Test
    void boundariesDuplicatesAndOrder() {
        var detector = new EncodingDetector();
        var kind = EncodingDetector.Kind.NAME;
        var preference = EncodingDetector.Preference.NONE;
        assertTrue(detector.rank(List.of(ByteBuffer.wrap(new byte[]{(byte) 0xC3}),
                ByteBuffer.wrap(new byte[]{(byte) 0xA9})), kind, preference).isEmpty());
        var names = new ArrayList<>(List.of(utf8("résumé.txt"), utf8("日本語.txt"), utf8("文件.txt")));
        var expected = detector.rank(names, kind, preference);
        assertFalse(expected.isEmpty());
        assertEquals(StandardCharsets.UTF_8, expected.get(0).charset());
        names.add(names.get(0).duplicate());
        for (int seed = 0; seed < 4; seed++) {
            Collections.shuffle(names, new Random(seed));
            assertEquals(expected, detector.rank(names, kind, preference));
        }
    }

    /// Strict validation includes unscored tails and controls in parent path components.
    @Test
    void completeValidationAndRecovery() {
        var detector = new EncodingDetector();
        String prefix = "é" + "a".repeat(5000);
        var malformed = ByteBuffer.allocate(prefix.getBytes(StandardCharsets.UTF_8).length + 1);
        malformed.put(prefix.getBytes(StandardCharsets.UTF_8)).put((byte) 0xFF).flip();
        assertNull(detector.decode(malformed, EncodingDetector.Kind.NAME, StandardCharsets.UTF_8));
        for (String text : List.of(prefix + "\u0001", "bad\u0001/é.txt", "é/bad\u0001.txt"))
            assertNull(detector.decode(utf8(text), EncodingDetector.Kind.NAME, StandardCharsets.UTF_8));
        assertEquals(prefix + "\n\t", detector.decode(utf8(prefix + "\n\t"), EncodingDetector.Kind.COMMENT, StandardCharsets.UTF_8));
        assertEquals("日本語😀.txt", detector.decode(utf8("日本語😀.txt"), EncodingDetector.Kind.NAME, StandardCharsets.UTF_8));
    }

    /// Mutable workspaces preserve all caller buffer state and remain independent across instances.
    @Test
    void buffersAndIndependentWorkspaces() {
        byte[] raw = "软件安装说明.txt".getBytes(Charset.forName("GB18030"));
        IntStream.range(0, 24).parallel().forEach(index -> {
            var detector = new EncodingDetector();
            var buffer = index % 2 == 0 ? ByteBuffer.allocateDirect(raw.length + 2) : ByteBuffer.allocate(raw.length + 2);
            buffer.position(1).put(raw).flip().position(1).mark();
            var view = buffer.asReadOnlyBuffer();
            assertEquals("软件安装说明.txt", detector.decode(view, EncodingDetector.Kind.NAME,
                    EncodingDetector.Preference.NONE));
            assertEquals(1, view.position());
            assertEquals(raw.length + 1, view.limit());
            view.reset();
            detector.decode(utf8("résumé.txt"), EncodingDetector.Kind.NAME, EncodingDetector.Preference.NONE);
            assertEquals("软件安装说明.txt", detector.decode(view, EncodingDetector.Kind.NAME,
                    EncodingDetector.Preference.NONE));
        });
    }

    /// Candidate text remains complete across multiple workspace fills.
    @Test
    void equivalentOutputsAcrossBufferBoundaries() {
        var detector = new EncodingDetector();
        var first = Charset.forName("windows-1252");
        var second = StandardCharsets.ISO_8859_1;
        var bytes = first.encode("é".repeat(2500));
        assertEquals("é".repeat(2500), detector.decode(bytes, EncodingDetector.Kind.NAME, first));
        assertEquals("é".repeat(2500), detector.decode(bytes, EncodingDetector.Kind.NAME, second));
        assertNotEquals("é".repeat(2500), detector.decode(bytes, EncodingDetector.Kind.NAME, Charset.forName("IBM437")));
        assertEquals(0, bytes.position());
    }

    /// Counterparts are compared across workspace boundaries, with fresh state after each mismatch or error.
    @Test
    void counterpartComparisonAndRecovery() {
        var detector = new EncodingDetector();
        String text = "é".repeat(1023) + "😀" + "日本語".repeat(500);
        var bytes = utf8(text);
        bytes.mark();
        assertTrue(detector.matching(bytes, text).contains(StandardCharsets.UTF_8));
        assertFalse(detector.matching(bytes, text.substring(0, text.length() - 1)).contains(StandardCharsets.UTF_8));
        assertFalse(detector.matching(bytes, text + "x").contains(StandardCharsets.UTF_8));
        assertFalse(detector.matching(bytes, "x" + text.substring(1)).contains(StandardCharsets.UTF_8));
        var malformed = ByteBuffer.allocate(bytes.remaining() + 1).put(bytes.duplicate()).put((byte) 0xff).flip();
        assertFalse(detector.matching(malformed, text).contains(StandardCharsets.UTF_8));
        assertEquals(text, detector.decode(bytes, EncodingDetector.Kind.NAME, StandardCharsets.UTF_8));
        assertTrue(detector.matching(bytes, text).contains(StandardCharsets.UTF_8));
        assertEquals(0, bytes.position());
        bytes.reset();
    }

    /// Budget checks reject excessive batches before accumulating profiles.
    @Test
    void batchBudgets() {
        var detector = new EncodingDetector();
        assertThrows(IllegalArgumentException.class, () -> detector.rank(Collections.nCopies(513, utf8("é")),
                EncodingDetector.Kind.NAME, EncodingDetector.Preference.NONE));
        assertThrows(IllegalArgumentException.class, () -> detector.rank(List.of(ByteBuffer.allocate(4097)),
                EncodingDetector.Kind.NAME, EncodingDetector.Preference.NONE));
        assertThrows(IllegalArgumentException.class, () -> detector.rank(Collections.nCopies(65, ByteBuffer.allocate(4096)),
                EncodingDetector.Kind.NAME, EncodingDetector.Preference.NONE));
    }

    /// Creates independent UTF-8 test bytes.
    private static ByteBuffer utf8(String text) {
        return ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
    }

    /// Comment profiles retain prose evidence; raw encoding-table runs do not establish a language.
    @Test
    void commentEvidenceAndByteTables() {
        var detector = new EncodingDetector();
        String text = "Comentario: Café ñoño ▓▒░";
        var bytes = ByteBuffer.wrap(text.getBytes(Charset.forName("IBM437")));
        var selected = detector.decode(bytes, EncodingDetector.Kind.COMMENT, EncodingDetector.Preference.OEM);
        assertNotNull(selected);
        assertEquals(text, selected);
        byte[] sweep = new byte[16];
        for (int i = 0; i < sweep.length; i++) sweep[i] = (byte) (0x80 + i);
        assertTrue(detector.rank(List.of(ByteBuffer.wrap(sweep)), EncodingDetector.Kind.NAME,
                EncodingDetector.Preference.NONE).isEmpty());
    }
}
