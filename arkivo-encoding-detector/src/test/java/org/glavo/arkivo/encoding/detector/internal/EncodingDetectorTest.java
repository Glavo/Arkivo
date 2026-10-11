// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.encoding.detector.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies common-encoding scoring independently of ZIP declarations and fallback policy.
@NotNullByDefault
class EncodingDetectorTest {
    /// Every installed inference candidate preserves all ASCII bytes used by the ZIP array shortcut.
    @Test
    void candidatesPreserveAscii() throws CharacterCodingException {
        byte[] ascii = new byte[128];
        for (int i = 0; i < ascii.length; i++) ascii[i] = (byte) i;
        String expected = new String(ascii, StandardCharsets.US_ASCII);
        for (Charset charset : BigramModels.INSTANCE.candidates.keySet()) {
            assertEquals(expected, charset.newDecoder().decode(ByteBuffer.wrap(ascii)).toString(), charset.name());
        }
    }

    /// ASCII fields allocate neither candidate decoders nor a character workspace.
    @Test
    void asciiDoesNotAllocateWorkspace() throws Exception {
        var detector = new EncodingDetector();
        var bytes = StandardCharsets.US_ASCII.encode("dir/file.txt");
        assertEquals("dir/file.txt", detector.decode(bytes, EncodingDetector.Kind.NAME,
                EncodingDetector.Preference.NONE));
        assertTrue(detector.rank(List.of(bytes), EncodingDetector.Kind.NAME, EncodingDetector.Preference.NONE).isEmpty());
        for (String field : List.of("decoders", "output")) {
            var storage = EncodingDetector.class.getDeclaredField(field);
            storage.setAccessible(true);
            assertNull(storage.get(detector));
        }
    }

    /// Scoring reads shared models; decoding allocates only the selected candidates and reuses their state.
    @Test
    void candidatesAreAllocatedOnDemand() throws Exception {
        var detector = new EncodingDetector();
        var decoders = EncodingDetector.class.getDeclaredField("decoders");
        decoders.setAccessible(true);
        var output = EncodingDetector.class.getDeclaredField("output");
        output.setAccessible(true);
        assertFalse(detector.rank(List.of(utf8("日本語.txt")), EncodingDetector.Kind.NAME,
                EncodingDetector.Preference.NONE).isEmpty());
        assertNull(decoders.get(detector));
        assertNull(output.get(detector));
        assertThrows(IllegalArgumentException.class, () -> detector.decode(
                utf8("é"), EncodingDetector.Kind.NAME, StandardCharsets.UTF_16));
        assertNull(decoders.get(detector));
        assertNull(output.get(detector));
        assertEquals("é", detector.decode(utf8("é"), EncodingDetector.Kind.NAME, StandardCharsets.UTF_8));
        var created = (Map<?, ?>) Objects.requireNonNull(decoders.get(detector));
        assertEquals(Set.of(StandardCharsets.UTF_8), created.keySet());
        @Nullable Object utf8 = created.get(StandardCharsets.UTF_8);
        @Nullable Object characters = output.get(detector);
        assertNotNull(utf8);
        assertNotNull(characters);
        assertNull(detector.decode(ByteBuffer.wrap(new byte[]{(byte) 0xff}), EncodingDetector.Kind.NAME,
                StandardCharsets.UTF_8));
        assertEquals("日本語", detector.decode(utf8("日本語"), EncodingDetector.Kind.NAME, StandardCharsets.UTF_8));
        assertSame(utf8, created.get(StandardCharsets.UTF_8));
        assertSame(characters, output.get(detector));
        var latin = Charset.forName("windows-1252");
        assertEquals("é", detector.decode(latin.encode("é"), EncodingDetector.Kind.NAME, latin));
        assertEquals(Set.of(StandardCharsets.UTF_8, latin), created.keySet());
        assertSame(characters, output.get(detector));
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

    /// Strict validation includes unscored tails; valid controls are preserved rather than rejected.
    @Test
    void completeValidationAndRecovery() {
        var detector = new EncodingDetector();
        String prefix = "é" + "a".repeat(5000);
        var malformed = ByteBuffer.allocate(prefix.getBytes(StandardCharsets.UTF_8).length + 1);
        malformed.put(prefix.getBytes(StandardCharsets.UTF_8)).put((byte) 0xFF).flip();
        assertNull(detector.decode(malformed, EncodingDetector.Kind.NAME, StandardCharsets.UTF_8));
        for (String text : List.of(prefix + "\u0001", "bad\u0001/é.txt", "é/bad\u0001.txt"))
            assertEquals(text, detector.decode(utf8(text), EncodingDetector.Kind.NAME, StandardCharsets.UTF_8));
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

    /// UTF-8 syntax excludes overlong forms, surrogate encodings, out-of-range scalars and incomplete fields.
    @Test
    void utf8StructureRejectsMalformedFields() {
        var detector = new EncodingDetector();
        for (String hex : List.of("80", "c0af", "c1bf", "c2", "e08080", "eda080", "f0808080",
                "f4908080", "f5808080", "ff", "e282", "e228a1")) {
            var field = ByteBuffer.wrap(java.util.HexFormat.of().parseHex(hex));
            assertNull(detector.decode(field, EncodingDetector.Kind.NAME, StandardCharsets.UTF_8));
            assertFalse(detector.rank(List.of(utf8("日本語"), field), EncodingDetector.Kind.NAME,
                    EncodingDetector.Preference.NONE).stream().anyMatch(score -> score.charset().equals(StandardCharsets.UTF_8)), hex);
        }
        assertFalse(detector.rank(List.of(utf8("日本語"), ByteBuffer.wrap(new byte[]{(byte) 0xc3}),
                        ByteBuffer.wrap(new byte[]{(byte) 0xa9})), EncodingDetector.Kind.NAME, EncodingDetector.Preference.NONE)
                .stream().anyMatch(score -> score.charset().equals(StandardCharsets.UTF_8)));
    }

    /// Structural support includes supplementary and uncommon characters, independently of model vocabulary.
    @Test
    void utf8StructureSupportsShortNames() {
        var detector = new EncodingDetector();
        for (String name : List.of("😀", "𠮷", "é", "😀𠮷.txt")) {
            assertEquals(name, detector.decode(utf8(name), EncodingDetector.Kind.NAME, EncodingDetector.Preference.NONE), name);
        }
        var names = new ArrayList<>(List.of(utf8("😀"), utf8("𠮷"), utf8("é")));
        var expected = detector.rank(names, EncodingDetector.Kind.NAME, EncodingDetector.Preference.NONE);
        names.addAll(Collections.nCopies(100, utf8("😀")));
        Collections.reverse(names);
        assertEquals(expected, detector.rank(names, EncodingDetector.Kind.NAME, EncodingDetector.Preference.NONE));
    }

    /// A sampled prefix ending inside a UTF-8 sequence does not reject a valid complete input.
    @Test
    void utf8SampleBoundaryIsNotFieldBoundary() {
        var detector = new EncodingDetector();
        for (String suffix : List.of("é", "中", "😀")) {
            for (int split = 1; split < suffix.getBytes(StandardCharsets.UTF_8).length; split++) {
                String name = "日本語" + "a".repeat(4096 - 9 - split) + suffix;
                assertEquals(name, detector.decode(utf8(name), EncodingDetector.Kind.NAME, EncodingDetector.Preference.NONE));
                var broken = ByteBuffer.allocate(utf8(name).remaining() + 1).put(utf8(name)).put((byte) 0xff).flip();
                assertNull(detector.decode(broken, EncodingDetector.Kind.NAME, StandardCharsets.UTF_8));
            }
        }
    }

    /// Controls reduce support without becoming decoding errors or invalidating exact Unicode counterparts.
    @Test
    void characterQualityIsSoftAndRoleAware() {
        var detector = new EncodingDetector();
        assertEquals(1, EncodingDetector.textQuality("é\ue000\u200d\u0301😀", EncodingDetector.Kind.NAME));
        assertEquals(1, EncodingDetector.textQuality("é\t\r\n", EncodingDetector.Kind.COMMENT));
        assertEquals(0.25, EncodingDetector.textQuality("é\t\r\n", EncodingDetector.Kind.NAME));
        assertEquals(0.5, EncodingDetector.textQuality("é\u0085", EncodingDetector.Kind.COMMENT));
        assertEquals(0.2, EncodingDetector.textQuality("\u0001".repeat(100), EncodingDetector.Kind.NAME));
        String name = "日本語\n.txt";
        assertEquals(name, detector.decode(utf8(name), EncodingDetector.Kind.NAME, EncodingDetector.Preference.NONE));
        assertTrue(detector.matching(utf8(name), name).contains(StandardCharsets.UTF_8));
        assertEquals("a\n.txt", detector.decode(utf8("a\n.txt"), EncodingDetector.Kind.NAME, EncodingDetector.Preference.NONE));
    }

    /// Structural filtering agrees with the JDK at scalar boundaries and on deterministic arbitrary bytes.
    @Test
    void utf8StructureMatchesStrictDecoder() {
        var fields = new ArrayList<ByteBuffer>();
        for (int scalar : new int[]{0x80, 0x7ff, 0x800, 0xd7ff, 0xe000, 0xffff, 0x10000, 0x10ffff}) {
            var encoded = utf8(new String(Character.toChars(scalar)));
            fields.add(encoded);
            for (int length = 1; length < encoded.remaining(); length++) fields.add(encoded.slice(0, length));
        }
        var random = new Random(3629);
        for (int index = 0; index < 512; index++) {
            byte[] bytes = new byte[1 + random.nextInt(16)];
            random.nextBytes(bytes);
            fields.add(ByteBuffer.wrap(bytes));
        }
        var reference = StandardCharsets.UTF_8.newDecoder();
        var detector = new EncodingDetector();
        for (var field : fields) {
            boolean valid;
            try {
                reference.decode(field.duplicate());
                valid = true;
            } catch (CharacterCodingException exception) {
                valid = false;
            }
            var buffer = ByteBuffer.allocateDirect(field.remaining() + 2).position(1).put(field.duplicate()).flip().position(1);
            var view = buffer.asReadOnlyBuffer();
            view.mark();
            boolean ranked = detector.rank(List.of(utf8("日本語😀"), view), EncodingDetector.Kind.NAME,
                    EncodingDetector.Preference.NONE).stream().anyMatch(score -> score.charset().equals(StandardCharsets.UTF_8));
            assertEquals(valid, ranked, () -> java.util.HexFormat.of().formatHex(field.array(), field.arrayOffset() + field.position(),
                    field.arrayOffset() + field.limit()));
            assertEquals(1, view.position());
            assertEquals(field.remaining() + 1, view.limit());
            view.reset();
        }
    }

    /// Lazy candidate evaluation has the same result as checking every ranked candidate after character penalties.
    @Test
    void adjustedSelectionMatchesExhaustiveScoring() {
        var detector = new EncodingDetector();
        for (String encoding : List.of("UTF-8", "windows-1252", "IBM437", "ISO-8859-1")) {
            for (String name : List.of("résumé.txt", "é\u0085.txt", "café\n.txt", "\u0001é\u0002.txt")) {
                var bytes = Charset.forName(encoding).encode(name);
                for (var kind : EncodingDetector.Kind.values()) {
                    for (var preference : EncodingDetector.Preference.values()) {
                        double best = -1;
                        boolean preferred = false;
                        var outputs = new java.util.HashSet<String>();
                        for (var score : detector.rank(List.of(bytes), kind, preference)) {
                            @Nullable String text = detector.decode(bytes, kind, score.charset());
                            if (text == null) continue;
                            double adjusted = score.similarity() * EncodingDetector.textQuality(text, kind);
                            boolean accepts = preference.accepts(score.charset());
                            if (adjusted > best || adjusted == best && accepts && !preferred) {
                                best = adjusted;
                                preferred = accepts;
                                outputs.clear();
                                outputs.add(text);
                            } else if (adjusted == best && accepts == preferred) outputs.add(text);
                        }
                        @Nullable String expected = outputs.size() == 1 ? outputs.iterator().next() : null;
                        assertEquals(expected, detector.decode(bytes, kind, preference), encoding + ": " + name);
                    }
                }
            }
        }
    }
}
