// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.encoding.detector.internal;

import org.apache.commons.compress.archivers.zip.UnicodePathExtraField;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.glavo.arkivo.archive.zip.ZipArchiveOptions;
import org.glavo.arkivo.archive.zip.ZipArkivoStreamingReader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/// Compares the pinned, unmodified Kala artifact with production metadata scoring.
/// Reports exact text, abstentions, strict-decoding failures, and an explicit CP437 fallback.
/// Authored probes and independently specified archive names are reported separately; neither
/// collection is used to tune thresholds. Joint runs include the target field for every policy.
@NotNullByDefault
class OriginalKalaEvaluationTest {
    /// Unmodified upstream configuration, including its acceptance threshold and approximation policy.
    private static final kala.encdet.EncodingDetector ORIGINAL = kala.encdet.EncodingDetector.DEFAULT;

    /// Restricts candidates to supported stateless ASCII-compatible ZIP encodings without retuning scores.
    private static final kala.encdet.EncodingDetector ZIP = zipConfiguration();

    /// Explicit archive fallback, counted separately from detector selections.
    private static final Charset CP437 = Charset.forName("IBM437");

    /// Compares the same 208 authored probes, grouped by encoding and non-ASCII length.
    @Test
    void authoredShortNames() throws Exception {
        var totals = new TreeMap<String, Counts>();
        System.out.printf(Locale.ROOT, "configuration,version=0.1.0,threshold=%s,max_bytes=%d,zip_candidates=%s%n",
                ORIGINAL.minimumConfidence(), ORIGINAL.maxBytes(), ZIP.encodings());
        for (var group : ZipMetadataScoringTest.shortNameGroups()) {
            var charset = Charset.forName(group[0]);
            for (int index = 1; index < group.length; index++) {
                String stem = group[index];
                String length = stem.codePoints().filter(value -> value >= 128).count() <= 2 ? "1-2" : "3+";
                for (String name : List.of(stem, stem + ".txt", "dir/" + stem + "_001.txt", "dir/" + stem + "_999.bin")) {
                    var input = encode(name, charset);
                    var fields = new ArrayList<ByteBuffer>();
                    for (int other = 1; other < group.length; other++) {
                        if (other != index) fields.add(encode(group[other], charset));
                    }
                    fields.add(input);
                    var outcomes = evaluate(input, fields);
                    for (var entry : outcomes.entrySet()) {
                        for (String bucket : List.of("ALL", "length=" + length, "encoding=" + group[0])) {
                            totals.computeIfAbsent(bucket + "," + entry.getKey(), ignored -> new Counts())
                                    .add(name, input, entry.getValue());
                        }
                    }
                }
            }
        }
        print("authored", totals);
        assertEquals(208, totals.get("ALL,current/field").total);
    }

    /// Reads every entry of nine fixed archives; expected names come from upstream assertions or
    /// the zip-rs fixture's plaintext encoding declarations. Unicode declarations bypass all policies.
    @Test
    void independentlySpecifiedArchives() throws Exception {
        var fixtures = List.of(
                new Fixture("jszip", "test/ref/local_encoding_in_name.zip", List.of(
                        new Name("Новая папка/", "IBM866"),
                        new Name("Новая папка/Новый текстовый документ.txt", "IBM866"))),
                new Fixture("zip4j", "src/test/resources/test-archives/testfile_with_chinese_filename_by_7zip.zip",
                        List.of(new Name("fff - 副本.txt", "GBK"), new Name("fff.txt", "US-ASCII"))),
                new Fixture("ziprs", "tests/data/non_utf8.zip",
                        List.of(new Name("中文", "GB18030"), new Name("日文", "Shift_JIS"))),
                new Fixture("go", "src/archive/zip/testdata/utf8-7zip.zip", List.of(new Name("世界", "UTF-8"))),
                new Fixture("go", "src/archive/zip/testdata/utf8-infozip.zip", List.of(new Name("世界", "UTF-8"))),
                new Fixture("go", "src/archive/zip/testdata/utf8-osx.zip", List.of(new Name("世界", "UTF-8"))),
                new Fixture("go", "src/archive/zip/testdata/utf8-winrar.zip", List.of(new Name("世界", "UTF-8"))),
                new Fixture("go", "src/archive/zip/testdata/utf8-winzip.zip", List.of(new Name("世界", "UTF-8"))),
                new Fixture("jszip", "test/ref/winrar_utf8_in_name.zip", List.of(new Name("€15.txt", "IBM437"))));
        var totals = new TreeMap<String, Counts>();
        var completeArchives = new TreeMap<String, Integer>();
        var distinct = new TreeSet<String>();
        int guessedArchives = 0;
        int declaredNames = 0;
        int asciiNames = 0;
        for (var fixture : fixtures) {
            var names = new ArrayList<String>();
            var fields = new ArrayList<ByteBuffer>();
            var allExpected = fixture.names.stream().map(Name::text).sorted().toList();
            var actualNames = new ArrayList<String>();
            try (var archive = ZipFile.builder().setPath(fixture.path()).get()) {
                var entries = archive.getEntries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    boolean declared = entry.getGeneralPurposeBit().usesUTF8ForNames()
                            || entry.getExtraField(UnicodePathExtraField.UPATH_ID) != null;
                    if (declared) {
                        assertTrue(allExpected.contains(entry.getName()), entry.getName());
                        actualNames.add(entry.getName());
                        declaredNames++;
                    } else {
                        var input = ByteBuffer.wrap(entry.getRawName()).asReadOnlyBuffer();
                        var matches = new ArrayList<String>();
                        for (var expected : fixture.names) {
                            if (encode(expected.text, Charset.forName(expected.encoding)).equals(input)) {
                                matches.add(expected.text);
                            }
                        }
                        assertEquals(1, matches.size(), fixture.relative + ": unexpected raw name");
                        String expected = matches.get(0);
                        actualNames.add(expected);
                        if (EncodingDetector.isAscii(input)) {
                            assertEquals(expected, kala(input, input, true).text);
                            asciiNames++;
                            continue;
                        }
                        names.add(expected);
                        fields.add(input);
                    }
                }
            }
            assertEquals(allExpected, actualNames.stream().sorted().toList());
            if (fields.isEmpty()) {
                var options = ZipArchiveOptions.READ_DEFAULTS.withLegacyMetadataDecoder(input -> {
                    fail("Declared Unicode names must bypass legacy inference");
                    return "";
                });
                var streamed = new ArrayList<String>();
                try (var reader = ZipArkivoStreamingReader.open(fixture.path(), options)) {
                    while (reader.next()) streamed.add(reader.readAttributes().path());
                }
                assertEquals(allExpected, streamed.stream().sorted().toList());
                continue;
            }
            guessedArchives++;
            var failures = new TreeSet<String>();
            for (int index = 0; index < fields.size(); index++) {
                var input = fields.get(index);
                String expected = names.get(index);
                byte[] bytes = new byte[input.remaining()];
                input.duplicate().get(bytes);
                boolean unique = distinct.add(HexFormat.of().formatHex(bytes) + ":" + expected);
                for (var entry : evaluate(input, fields).entrySet()) {
                    String policy = entry.getKey();
                    var outcome = entry.getValue();
                    totals.computeIfAbsent("ALL," + policy, ignored -> new Counts()).add(expected, input, outcome);
                    if (unique) totals.computeIfAbsent("UNIQUE," + policy, ignored -> new Counts()).add(expected, input, outcome);
                    completeArchives.putIfAbsent(policy, 0);
                    if (!expected.equals(outcome.text)) failures.add(policy);
                    System.out.printf("case,%s,%s,%s,expected=%s,actual=%s,status=%s%n",
                            fixture.corpus, fixture.relative, policy, expected, outcome.text, outcome.status);
                }
            }
            completeArchives.replaceAll((policy, count) -> failures.contains(policy) ? count : count + 1);
        }
        print("real", totals);
        System.out.printf("corpus,archives=%d,requiring_inference=%d,declared_names=%d,ascii_names=%d,unique_inferred_names=%d%n",
                fixtures.size(), guessedArchives, declaredNames, asciiNames, distinct.size());
        for (var entry : completeArchives.entrySet()) {
            System.out.printf("complete_archive,%s,%d,%d%n", entry.getKey(), entry.getValue(), guessedArchives);
        }
    }

    /// Verifies the adapter's byte-preserving ASCII path and deterministic whole-archive sampling.
    @Test
    void adapterContracts() throws Exception {
        var ascii = ByteBuffer.wrap("dir/model_001.txt".getBytes(StandardCharsets.US_ASCII)).asReadOnlyBuffer();
        assertEquals("dir/model_001.txt", kala(ascii, ascii, true).text);
        var first = encode("文件.txt", Charset.forName("GB18030"));
        var second = encode("软件.txt", Charset.forName("GB18030"));
        assertEquals(batch(List.of(first, second)), batch(List.of(second, first, first)));
        var direct = ByteBuffer.allocateDirect(first.remaining() + 2);
        direct.put((byte) 0).put(first.duplicate()).put((byte) 0).flip();
        direct.position(1).limit(direct.limit() - 1);
        direct.mark();
        assertEquals(evaluate(first, List.of(first, second)), evaluate(direct, List.of(second, direct)));
        direct.reset();
        assertEquals(1, direct.position());
        assertEquals(first.remaining(), direct.remaining());
        assertEquals(ORIGINAL.minimumConfidence(), ZIP.minimumConfidence());
        assertFalse(ZIP.allowsCharsetApproximation());
        var utf8Sample = encode("文件说明.txt", StandardCharsets.UTF_8);
        var incomplete = ByteBuffer.wrap(new byte[]{(byte) 0xe2, (byte) 0x82});
        assertTrue(kala(incomplete, utf8Sample, false).invalid);
        assertNull(kala(incomplete, utf8Sample, true).text);
    }

    /// Evaluates isolated fields and inclusive archive context without applying a hidden fallback.
    private static TreeMap<String, Outcome> evaluate(ByteBuffer input, List<ByteBuffer> fields) throws Exception {
        int position = input.position();
        int limit = input.limit();
        var result = new TreeMap<String, Outcome>();
        var detector = new EncodingDetector();
        var context = detector.prepare(fields, EncodingDetector.Kind.NAME);
        for (String mode : List.of("field", "joint")) {
            @Nullable var current = detector.detect(input, EncodingDetector.Kind.NAME, mode.equals("field") ? null : context);
            result.put("current/" + mode, current == null ? Outcome.abstention()
                    : new Outcome(current.newDecoder().decode(input.duplicate()).toString(), "selected", false));
            var sample = mode.equals("field") ? input : batch(fields);
            result.put("original/" + mode, kala(input, sample, false));
            result.put("zip-adapted/" + mode, kala(input, sample, true));
        }
        assertEquals(position, input.position());
        assertEquals(limit, input.limit());
        return result;
    }

    /// Uses original candidate order and threshold. The adapter rejects invalid whole-field decodes;
    /// the default path retains upstream approximation and replacement behavior for comparison.
    private static Outcome kala(ByteBuffer input, ByteBuffer sample, boolean adapted) {
        if (adapted && EncodingDetector.isAscii(input)) {
            return new Outcome(StandardCharsets.US_ASCII.decode(input.duplicate()).toString(), "ascii", false);
        }
        var detector = adapted ? ZIP : ORIGINAL;
        var result = detector.detect(sample);
        if (adapted) {
            for (var candidate : result.candidates()) {
                @Nullable var encoding = candidate.encoding();
                if (encoding == null) continue;
                @Nullable var charset = encoding.charset();
                if (charset == null) continue;
                try {
                    return new Outcome(charset.newDecoder().decode(input.duplicate()).toString(), "selected", false);
                } catch (CharacterCodingException ignored) {
                    // Another already accepted candidate may strictly decode this complete field.
                }
            }
            return Outcome.abstention();
        }
        @Nullable var encoding = result.bestEncoding();
        if (encoding == null) return Outcome.abstention();
        @Nullable var charset = encoding.approximateCharset();
        if (charset == null) return new Outcome(null, "unsupported", false);
        var data = input.duplicate();
        if (encoding == kala.encdet.EncodingDetector.Encoding.UTF_8_SIG && data.remaining() >= 3
                && data.get(data.position()) == (byte) 0xef && data.get(data.position() + 1) == (byte) 0xbb
                && data.get(data.position() + 2) == (byte) 0xbf) data.position(data.position() + 3);
        boolean invalid = false;
        try {
            charset.newDecoder().decode(data.duplicate());
        } catch (CharacterCodingException ignored) {
            invalid = true;
        }
        String text = charset.decode(data).toString();
        if (sample == input) assertEquals(detector.toString(input), text);
        return new Outcome(text, invalid ? "replacement" : "selected", invalid);
    }

    /// Creates a deterministic, deduplicated LF-separated sample without changing individual bytes.
    /// Concatenation uses upstream's continuous-text API; it is not independent-field model pooling.
    private static @Unmodifiable ByteBuffer batch(List<ByteBuffer> fields) {
        var sorted = new TreeSet<ByteBuffer>();
        for (var field : fields) if (!EncodingDetector.isAscii(field)) sorted.add(field.asReadOnlyBuffer());
        int size = sorted.stream().mapToInt(field -> field.remaining() + 1).sum();
        assertTrue(size <= ORIGINAL.maxBytes(), "Evaluation samples must not be silently truncated");
        var result = ByteBuffer.allocate(size);
        for (var field : sorted) result.put(field.duplicate()).put((byte) '\n');
        return result.flip().asReadOnlyBuffer();
    }

    /// Filters by encoding capabilities, not by accuracy on the evaluation set.
    private static kala.encdet.EncodingDetector zipConfiguration() {
        var encodings = EnumSet.noneOf(kala.encdet.EncodingDetector.Encoding.class);
        byte[] ascii = new byte[128];
        for (int index = 0; index < ascii.length; index++) ascii[index] = (byte) index;
        for (var encoding : kala.encdet.EncodingDetector.Encoding.values()) {
            String name = encoding.name();
            if (name.equals("UTF_7") || name.equals("HZ") || name.startsWith("ISO_2022")) continue;
            @Nullable var charset = encoding.charset();
            if (charset == null) continue;
            try {
                var text = charset.newDecoder().decode(ByteBuffer.wrap(ascii)).toString();
                if (!text.equals(new String(ascii, StandardCharsets.US_ASCII))) continue;
                var encoded = charset.newEncoder().encode(CharBuffer.wrap(text));
                if (encoded.equals(ByteBuffer.wrap(ascii))) encodings.add(encoding);
            } catch (CharacterCodingException ignored) {
                // Encodings that cannot preserve the entire ASCII range are not ZIP candidates.
            }
        }
        return ORIGINAL.withEncodings(encodings).withCharsetApproximation(false);
    }

    /// Encodes a fixed expectation strictly and verifies that the encoding preserves its text.
    private static @Unmodifiable ByteBuffer encode(String name, Charset charset) throws CharacterCodingException {
        var result = charset.newEncoder().encode(CharBuffer.wrap(name)).asReadOnlyBuffer();
        assertEquals(name, charset.newDecoder().decode(result.duplicate()).toString());
        return result;
    }

    /// Prints stable CSV summaries; fallback correctness is separate from accepted selections.
    private static void print(String dataset, TreeMap<String, Counts> totals) {
        System.out.println("dataset,bucket,policy,total,correct,wrong,unknown,strict_failures,cp437_fallback_correct");
        totals.forEach((key, counts) -> System.out.printf(Locale.ROOT, "%s,%s,%d,%d,%d,%d,%d,%d%n",
                dataset, key, counts.total, counts.correct, counts.total - counts.correct - counts.unknown,
                counts.unknown, counts.invalid, counts.fallbackCorrect));
    }

    /// A known name and the encoding used only to match its original bytes, never to guide inference.
    /// @param text independently specified Unicode text
    /// @param encoding charset used by the producer for the undeclared name
    @NotNullByDefault
    private record Name(String text, String encoding) {
    }

    /// A pinned upstream fixture and its complete expected entry list.
    /// @param corpus verified download identifier
    /// @param relative path within the extracted upstream sources
    /// @param names expected names from upstream assertions or independently checked plaintext
    @NotNullByDefault
    private record Fixture(String corpus, String relative, @Unmodifiable List<Name> names) {
        /// Resolves the verified corpus directory supplied by Gradle.
        Path path() {
            return Path.of(Objects.requireNonNull(System.getProperty("arkivo." + corpus + ".testDataDirectory")))
                    .resolve(relative);
        }
    }

    /// A materialized selection, with replacement decoding distinguished from strict decoding.
    /// @param text decoded text, or null when no usable selection exists
    /// @param status diagnostic selection status
    /// @param invalid whether the original selection failed whole-field strict decoding
    @NotNullByDefault
    private record Outcome(@Nullable String text, String status, boolean invalid) {
        /// Returns an unresolved result without a fallback.
        static Outcome abstention() {
            return new Outcome(null, "abstained", false);
        }
    }

    /// Accumulates exact-text results without turning abstention into a correct selection.
    @NotNullByDefault
    private static final class Counts {
        /// Number of evaluated fields.
        private int total;
        /// Number of exactly correct accepted selections.
        private int correct;
        /// Number of unresolved fields.
        private int unknown;
        /// Number of upstream selections that require replacement decoding.
        private int invalid;
        /// Number correct after applying CP437 only to unresolved fields.
        private int fallbackCorrect;

        /// Records one outcome and separately measures the format fallback.
        void add(String expected, ByteBuffer input, Outcome outcome) {
            total++;
            if (expected.equals(outcome.text)) correct++;
            if (outcome.text == null) unknown++;
            if (outcome.invalid) invalid++;
            String finalText = outcome.text == null ? CP437.decode(input.duplicate()).toString() : outcome.text;
            if (expected.equals(finalText)) fallbackCorrect++;
        }
    }
}
