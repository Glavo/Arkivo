// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.encoding.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/// Checks model fidelity and the boundary between scoring and strict validation.
@NotNullByDefault
class ModelTest {
    /// Matches numerical scores exported by the unmodified upstream scoring implementation.
    @Test
    void upstreamCandidateScores() throws Exception {
        try (var reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                ModelTest.class.getResourceAsStream("/org/glavo/arkivo/encoding/upstream.tsv")),
                StandardCharsets.UTF_8))) {
            for (String line : reader.lines().filter(value -> !value.startsWith("#")).toList()) {
                var fields = line.split("\t", 5);
                Candidate candidate = candidate(fields[1]);
                var bytes = HexFormat.of().parseHex(fields[2]);
                // The upstream public entry point skips ASCII except for two bytes of context.
                int start = 0;
                while (start < bytes.length && bytes[start] >= 0 && bytes[start] != 27) {
                    start++;
                }
                for (int i = Math.max(0, start - 2); i < bytes.length; i++) {
                    candidate.accept(bytes[i] & 255);
                }
                candidate.finish();
                assertEquals(Long.parseLong(fields[4]), candidate.eligibleScore(), fields[0]);
            }
        }
    }

    /// Creates the first upstream candidate for a fixture's encoding.
    private static Candidate candidate(String name) {
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "gb18030" -> new GbkCandidate();
            case "big5-hkscs" -> new Big5Candidate();
            case "windows-31j" -> new ShiftJisCandidate();
            case "euc-jp" -> new EucJpCandidate();
            case "x-windows-949" -> new EucKrCandidate();
            case "iso-2022-jp" -> new DecodedCandidate("ISO-2022-JP");
            default -> {
                for (var data : Models.SINGLE_BYTE_DATA) {
                    if (data.charsetName().equalsIgnoreCase(name)) {
                        yield switch (name.toLowerCase(java.util.Locale.ROOT)) {
                            case "windows-1251", "koi8-u", "iso-8859-5", "ibm866",
                                 "windows-1253", "iso-8859-7" -> new NonLatinCasedCandidate(data);
                            case "windows-1256" -> new ArabicFrenchCandidate(data);
                            case "windows-1255" -> new LogicalCandidate(data);
                            case "iso-8859-8" -> new VisualCandidate(data);
                            case "iso-8859-6", "x-windows-874" -> new CaselessCandidate(data);
                            default -> new LatinCandidate(data);
                        };
                    }
                }
                throw new AssertionError("Missing model: " + name);
            }
        };
    }

    /// Matches a digest independently extracted from upstream Rust array literals.
    @Test
    void tablePayloadMatchesUpstream() throws Exception {
        var properties = new Properties();
        try (var input = Objects.requireNonNull(Models.class.getResourceAsStream("models.properties"))) {
            properties.load(input);
        }
        var digest = MessageDigest.getInstance("SHA-256");
        int length = 0;
        for (String key : new TreeSet<>(properties.stringPropertyNames())) {
            byte[] bytes = HexFormat.of().parseHex(properties.getProperty(key));
            digest.update(bytes);
            length += bytes.length;
        }
        assertEquals(28191, length);
        assertEquals("a3b28c492caf9b1649af772c94b06dc2d73e9cd34621956beaacbac954cc30ed",
                HexFormat.of().formatHex(digest.digest()));
        assertEquals(20, Models.SINGLE_BYTE_DATA.length);
        for (var model : Models.SINGLE_BYTE_DATA) {
            assertEquals(128, model.lower().length);
            assertEquals(128, model.upper().length);
            assertEquals(2 * model.ascii() * model.nonAscii() + model.nonAscii() * model.nonAscii(),
                    model.probabilities().length);
            for (int value = 0; value < 256; value++) {
                int expected = (value < 128 ? model.lower()[value] : model.upper()[value - 128]) & 255;
                if (!valid(Charset.forName(model.charsetName()), new byte[]{(byte) value})) {
                    expected = 255;
                }
                assertEquals(expected, model.classify(value));
            }
        }
    }

    /// Excludes optional encodings absent from the installed charset providers.
    @Test
    void unavailableCharset() {
        var candidate = new DecodedCandidate("x-arkivo-unavailable-charset");
        assertNull(candidate.charset);
        candidate.accept(0xFF);
        candidate.finish();
        assertEquals(Candidate.INVALID, candidate.eligibleScore());
    }

    /// Ensures that no byte admitted by a single-byte model is unmappable in its JDK charset.
    @Test
    void singleByteValidity() {
        for (var data : Models.SINGLE_BYTE_DATA) {
            var charset = Charset.forName(data.charsetName());
            for (int value = 0; value < 256; value++) {
                if (data.classify(value) != 255) {
                    assertTrue(valid(charset, new byte[]{(byte) value}),
                            data.charsetName() + " byte=" + value);
                }
            }
        }
    }

    /// Keeps score saturation distinct from the invalid-candidate sentinel.
    @Test
    void saturatedScores() {
        var candidate = new DecodedCandidate("UTF-8");
        candidate.score = Long.MAX_VALUE - 2;
        candidate.add(5);
        assertEquals(Long.MAX_VALUE, candidate.score);
        candidate.score = Long.MIN_VALUE + 2;
        candidate.add(-5);
        assertEquals(Long.MIN_VALUE + 1, candidate.score);
        candidate.add(Candidate.INVALID);
        candidate.add(100);
        assertEquals(Candidate.INVALID, candidate.score);
    }

    /// Rejects extensions that the corresponding strict JDK decoder cannot read.
    @Test
    void malformedCandidatesStayRejected() {
        Candidate[] candidates = {new GbkCandidate(), new ShiftJisCandidate(), new EucJpCandidate(),
                new Big5Candidate(), new EucKrCandidate()};
        for (var candidate : candidates) {
            candidate.accept(0xFF);
            candidate.finish();
            assertEquals(Candidate.INVALID, candidate.score, candidate.getClass().getSimpleName());
            for (int i = 0; i < 100; i++) {
                candidate.accept('A');
            }
            assertEquals(Candidate.INVALID, candidate.score);
        }
    }

    /// Validates four-byte Chinese sequences, supplementary characters, and split escape sequences.
    @Test
    void multibyteValidation() throws Exception {
        for (String name : new String[]{"UTF-8", "GB18030", "Big5-HKSCS", "windows-31j",
                "EUC-JP", "x-windows-949", "ISO-2022-JP"}) {
            var charset = Charset.forName(name);
            for (String hex : new String[]{"", "41", "1b", "1b2442", "1b244221", "1b244221211b2842",
                    "f09f9880", "81308130", "95328236", "a440", "a4", "82a0", "8fa2af",
                    "b0a1", "ff", "c080", "eda080", "81ff", "1b244241ff"}) {
                var bytes = HexFormat.of().parseHex(hex);
                assertEquals(valid(charset, bytes), incrementalValid(charset, bytes), name + " " + hex);
            }
        }
    }

    /// Returns whether the strict JDK decoder accepts a complete byte array.
    static boolean valid(Charset charset, byte[] bytes) {
        try {
            charset.newDecoder().decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException exception) {
            return false;
        }
    }

    /// Returns whether the scoring bridge accepts the same array one byte at a time.
    static boolean incrementalValid(Charset charset, byte[] bytes) {
        var decoder = new ByteDecoder(charset);
        for (byte value : bytes) {
            if (decoder.accept(value & 255) < 0) {
                return false;
            }
        }
        return decoder.finish();
    }
}
