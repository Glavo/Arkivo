// Copyright Mozilla Foundation and (c) 2026 Glavo
// SPDX-License-Identifier: MIT

package org.glavo.arkivo.encoding;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/// Exercises upstream regressions and byte-buffer lifecycle contracts.
@NotNullByDefault
class CharsetDetectorTest {
    /// Supplies the upstream text assertions without deriving expected answers from the port.
    static Stream<String[]> upstream() throws Exception {
        try (var reader = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(CharsetDetectorTest.class.getResourceAsStream("upstream.tsv")),
                StandardCharsets.UTF_8))) {
            return reader.lines().filter(line -> !line.startsWith("#"))
                    .map(line -> line.split("\t", 5)).toList().stream();
        }
    }

    /// Preserves upstream guesses for JDK-representable text at every two-chunk boundary.
    @ParameterizedTest(name = "{0}")
    @MethodSource("upstream")
    void upstreamRegressions(String id, String encoding, String input, String original, long score) throws Exception {
        assertNotEquals(Long.MIN_VALUE, score, "Upstream winner must be eligible");
        var charset = Charset.forName(encoding);
        var bytes = HexFormat.of().parseHex(input);
        if (charset.newEncoder().canEncode(original)) {
            assertEquals(original, charset.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
        }
        var detector = new CharsetDetector(true);
        for (int split = 0; split <= bytes.length; split++) {
            detector.reset();
            detector.feed(ByteBuffer.wrap(bytes, 0, split), false);
            detector.guess(false); // Observing a prefix must not change subsequent state.
            detector.feed(ByteBuffer.wrap(bytes, split, bytes.length - split), true);
            assertEquals(charset, detector.guess(false), id + " split=" + split);
        }
        detector.reset();
        for (byte value : bytes) {
            detector.feed(new byte[]{value}, false);
        }
        detector.feed(new byte[0], true);
        assertEquals(charset, detector.guess(false), id + " byte-at-a-time");
    }

    /// Covers empty streams, ASCII fallback, completion, and reset.
    @Test
    void lifecycle() {
        var detector = new CharsetDetector();
        assertEquals(StandardCharsets.UTF_8, detector.guess());
        assertEquals(Charset.forName("windows-1252"), detector.guess(false));
        assertFalse(detector.feed(new byte[0], false));
        assertFalse(detector.feed("file.txt".getBytes(StandardCharsets.US_ASCII), true));
        assertEquals(StandardCharsets.UTF_8, detector.guess());
        var untouched = ByteBuffer.wrap(new byte[]{1, 2, 3}).position(1);
        assertThrows(IllegalStateException.class, () -> detector.feed(untouched, false));
        assertEquals(1, untouched.position());
        assertThrows(IllegalStateException.class, () -> detector.feed(new byte[0], true));
        detector.reset();
        assertTrue(detector.feed("é".getBytes(StandardCharsets.UTF_8), true));
        assertEquals(StandardCharsets.UTF_8, detector.guess());
    }

    /// Does not retain the array or buffer used for a prefix.
    @Test
    void inputOwnershipAndBufferKinds() {
        var expected = Charset.forName("windows-1251");
        var bytes = "Это тест кодировки символов.".getBytes(expected);
        for (int kind = 0; kind < 3; kind++) {
            ByteBuffer source = kind == 0 ? ByteBuffer.allocate(bytes.length + 4)
                    : ByteBuffer.allocateDirect(bytes.length + 4);
            source.position(2).put(bytes).flip().position(2);
            if (kind == 2) {
                source = source.asReadOnlyBuffer();
            }
            source.mark();
            assertEquals(expected, CharsetDetector.detect(source));
            assertEquals(2, source.position());
            source.reset();
            var detector = new CharsetDetector();
            assertTrue(detector.feed(source, false));
            assertEquals(source.limit(), source.position());
            detector.feed(new byte[0], true);
            assertEquals(expected, detector.guess());
        }
        var detector = new CharsetDetector();
        var prefix = new byte[]{(byte) 0xE2};
        detector.feed(prefix, false);
        prefix[0] = 0;
        detector.feed(new byte[]{(byte) 0x82, (byte) 0xAC}, true);
        assertEquals(StandardCharsets.UTF_8, detector.guess());
    }

    /// Rejects malformed UTF-8, including truncation detected only at completion.
    @Test
    void utf8Validation() {
        for (var hex : new String[]{"c080", "eda080", "f4908080", "80", "f5808080", "e282", "c2"}) {
            var detector = new CharsetDetector();
            detector.feed(HexFormat.of().parseHex(hex), true);
            assertNotEquals(StandardCharsets.UTF_8, detector.guess(), hex);
        }
        var detector = new CharsetDetector();
        detector.feed(HexFormat.of().parseHex("e282"), false);
        assertEquals(StandardCharsets.UTF_8, detector.guess());
        detector.feed(new byte[0], true);
        assertNotEquals(StandardCharsets.UTF_8, detector.guess());
        assertEquals(StandardCharsets.UTF_8, CharsetDetector.detect("𠀀😀".getBytes(StandardCharsets.UTF_8)));
    }

    /// Applies the ISO-2022-JP setting across resets and escape-sequence boundaries.
    @Test
    void iso2022Jp() {
        var charset = Charset.forName("ISO-2022-JP");
        var bytes = "日本語".getBytes(charset);
        var disabled = new CharsetDetector();
        disabled.feed(bytes, true);
        assertEquals(StandardCharsets.UTF_8, disabled.guess());
        assertEquals(Charset.forName("windows-1252"), disabled.guess(false));
        var enabled = new CharsetDetector(true);
        for (int i = 0; i < 2; i++) {
            for (byte value : bytes) {
                assertFalse(enabled.feed(new byte[]{value}, false));
            }
            enabled.feed(new byte[0], true);
            assertEquals(charset, enabled.guess());
            enabled.reset();
        }
        enabled.feed(new byte[]{27, '$'}, true);
        assertNotEquals(charset, enabled.guess());
    }

    /// Keeps prefix context for ordinals and apostrophes.
    @Test
    void asciiContext() {
        for (String hex : new String[]{"6e2eba31", "206e2eba31", "72726e2eba31", "4992", "446f6eb47420"}) {
            var detector = new CharsetDetector();
            for (byte value : HexFormat.of().parseHex(hex)) {
                detector.feed(new byte[]{value}, false);
            }
            detector.feed(new byte[0], true);
            assertEquals(Charset.forName("windows-1252"), detector.guess(false), hex);
        }
    }

    /// Produces the same answer for all partitions of arbitrary bytes.
    @Test
    void randomChunking() {
        var random = new Random(0xC4A2DE7L);
        for (int iteration = 0; iteration < 300; iteration++) {
            var bytes = new byte[random.nextInt(192)];
            random.nextBytes(bytes);
            var whole = new CharsetDetector(true);
            whole.feed(bytes, true);
            var chunked = new CharsetDetector(true);
            for (int offset = 0; offset < bytes.length; ) {
                int length = Math.min(bytes.length - offset, random.nextInt(7) + 1);
                chunked.feed(ByteBuffer.wrap(bytes, offset, length), false);
                offset += length;
            }
            chunked.feed(new byte[0], true);
            assertEquals(whole.guess(), chunked.guess());
            assertEquals(whole.guess(false), chunked.guess(false));
        }
    }

    /// Keeps detector state independent when immutable tables are shared.
    @Test
    void independentConcurrentDetectors() throws Exception {
        var bytes = "这是一个字符编码测试。".getBytes(Charset.forName("GB18030"));
        var executor = Executors.newFixedThreadPool(4);
        try {
            Callable<Charset> operation = () -> CharsetDetector.detect(bytes);
            for (var result : executor.invokeAll(java.util.Collections.nCopies(100, operation))) {
                assertEquals(Charset.forName("GB18030"), result.get());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /// Rejects null without changing a detector's lifecycle.
    @Test
    void nullInput() {
        var detector = new CharsetDetector();
        assertThrows(NullPointerException.class, () -> detector.feed((byte[]) null, true));
        assertThrows(NullPointerException.class, () -> detector.feed((ByteBuffer) null, true));
        assertThrows(NullPointerException.class, () -> CharsetDetector.detect((byte[]) null));
        assertThrows(NullPointerException.class, () -> CharsetDetector.detect((ByteBuffer) null));
        assertFalse(detector.feed(new byte[0], true));
    }
}
