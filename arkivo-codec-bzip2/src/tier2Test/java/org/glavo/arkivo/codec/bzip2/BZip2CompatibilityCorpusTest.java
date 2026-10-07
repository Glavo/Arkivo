// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bzip2;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.DecompressionOutputLimitException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the pinned bzip2-tests corpus against upstream digests and an independent decoder.
@NotNullByDefault
@Timeout(120)
final class BZip2CompatibilityCorpusTest {
    /// Every valid fixture and its independently decoded size, in bytes.
    private static final @Unmodifiable List<Fixture> VALID = List.of(
            new Fixture("commons-compress/COMPRESS-131", 539),
            new Fixture("commons-compress/bla.tar", 10240),
            new Fixture("commons-compress/bla.txt", 26),
            new Fixture("commons-compress/bla.xml", 610),
            new Fixture("commons-compress/multiple", 2),
            new Fixture("commons-compress/zip64support.tar", 106721280),
            new Fixture("dotnetzip/dancing-color.ps", 1701834),
            new Fixture("dotnetzip/sample1", 98696),
            new Fixture("dotnetzip/sample2", 212340),
            new Fixture("go/compress/Isaac.Newton-Opticks.txt", 567198),
            new Fixture("go/compress/e.txt", 100003),
            new Fixture("go/compress/pass-random1", 1024),
            new Fixture("go/compress/pass-random2", 65),
            new Fixture("go/compress/pass-sawtooth", 1048576),
            new Fixture("go/compress/random.data", 16384),
            new Fixture("go/crypto/SigVer.rsp", 201306),
            new Fixture("go/crypto/pss-vect.txt", 92293),
            new Fixture("go/regexp/re2-exhaustive.txt", 61492068),
            new Fixture("lbzip2/32767", 5),
            new Fixture("lbzip2/ch255", 46620000),
            new Fixture("lbzip2/codelen20", 1),
            new Fixture("lbzip2/concat", 5),
            new Fixture("lbzip2/empty", 0),
            new Fixture("lbzip2/fib", 900000),
            new Fixture("lbzip2/gap", 5),
            new Fixture("lbzip2/idx899999", 46619999),
            new Fixture("lbzip2/incomp-1", 10000),
            new Fixture("lbzip2/incomp-2", 10000),
            new Fixture("lbzip2/rand", 5),
            new Fixture("lbzip2/repet", 900000),
            new Fixture("lbzip2/trash", 5),
            new Fixture("pyflate/45MB-00", 45899235),
            new Fixture("pyflate/45MB-fb", 45899235),
            new Fixture("pyflate/510B", 510),
            new Fixture("pyflate/765B", 765),
            new Fixture("pyflate/aaa", 18),
            new Fixture("pyflate/empty", 0),
            new Fixture("pyflate/hello-world", 12)
    );

    /// Every fixture classified as malformed by the upstream collection.
    private static final @Unmodifiable List<String> INVALID = List.of(
            "go/compress/fail-issue5747", "lbzip2/crc1", "lbzip2/crc2", "lbzip2/cve",
            "lbzip2/cve2", "lbzip2/overrun", "lbzip2/overrun2", "lbzip2/void"
    );

    /// Checks complete sample membership, reference digests, and retained source notices.
    @Test
    void accountsForAllSamplesAndReferences() throws IOException {
        Set<String> expected = Stream.concat(
                VALID.stream().flatMap(f -> Stream.of(f.name() + ".bz2", f.name() + ".md5")),
                INVALID.stream().map(name -> name + ".bz2.bad")
        ).collect(Collectors.toSet());
        try (Stream<Path> files = Files.walk(root())) {
            Set<String> actual = files.filter(Files::isRegularFile)
                    .map(path -> root().relativize(path).toString().replace('\\', '/'))
                    .filter(name -> name.endsWith(".bz2") || name.endsWith(".bad") || name.endsWith(".md5"))
                    .collect(Collectors.toSet());
            assertEquals(expected, actual);
        }
        for (String notice : List.of("README", "UPSTREAM.properties", "commons-compress/LICENSE.txt",
                "commons-compress/NOTICE.txt", "dotnetzip/License.txt", "dotnetzip/License.zlib.txt",
                "go/LICENSE", "lbzip2/COPYING", "pyflate/README")) {
            assertTrue(Files.size(root().resolve(notice)) > 0, notice);
        }
    }

    /// Checks all decoded bytes, including multi-block and high-expansion streams, without retaining their output.
    @ParameterizedTest(name = "{0}")
    @MethodSource("validFixtures")
    void decodesCompleteCorpus(Fixture fixture) throws Exception {
        byte[] encoded = Files.readAllBytes(root().resolve(fixture.name() + ".bz2"));
        try (InputStream input = new BZip2CompressorInputStream(
                new ByteArrayInputStream(encoded), !fixture.hasTrailingGarbage())) {
            assertDigest(fixture, digest(input));
        }
        try (CompressionDecoder decoder = BZip2Codec.DEFAULT.newDecoder()) {
            assertDigest(fixture, decode(decoder, fixture, encoded, 8192, 32768, false, false, true));
            decoder.reset();
            assertDigest(fixture, decode(decoder, fixture, encoded, 65536, 32768, true, true, true));
        }
        if (!fixture.hasTrailingGarbage()) {
            try (InputStream input = BZip2Codec.DEFAULT.newInputStream(new ByteArrayInputStream(encoded))) {
                assertDigest(fixture, digest(input));
            }
        }
    }

    /// Exercises small real files with tiny outputs, every input buffer kind, and reset between complete passes.
    @ParameterizedTest(name = "{0}, chunk={1}, direct={2}, readOnly={3}")
    @MethodSource("fragmentedFixtures")
    void decodesFragmentedBuffers(Fixture fixture, int chunk, boolean direct, boolean readOnly) throws Exception {
        byte[] encoded = Files.readAllBytes(root().resolve(fixture.name() + ".bz2"));
        try (CompressionDecoder decoder = BZip2Codec.DEFAULT.newDecoder()) {
            assertDigest(fixture, decode(decoder, fixture, encoded, chunk, 7, direct, readOnly, true));
            decoder.reset();
            assertDigest(fixture, decode(decoder, fixture, encoded, chunk, 1, direct, readOnly, true));
        }
    }

    /// Recompresses bounded real inputs at both block-size extremes and validates them independently.
    @ParameterizedTest(name = "{0}")
    @MethodSource("recompressionFixtures")
    void recompressesWithIndependentVerification(Fixture fixture) throws Exception {
        for (int level : new int[]{1, 9}) {
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (InputStream input = new BZip2CompressorInputStream(
                    Files.newInputStream(root().resolve(fixture.name() + ".bz2")), !fixture.hasTrailingGarbage());
                 OutputStream output = BZip2Codec.DEFAULT.withCompressionLevel(level).newOutputStream(compressed)) {
                input.transferTo(output);
            }
            try (InputStream input = new BZip2CompressorInputStream(
                    new ByteArrayInputStream(compressed.toByteArray()), true)) {
                assertDigest(fixture, digest(input));
            }
        }
    }

    /// Checks malformed streams under fragmented input and proves reset removes their failed state.
    @ParameterizedTest(name = "{0}, chunk={1}, direct={2}")
    @MethodSource("invalidFixtures")
    void rejectsMalformedCorpusAndResets(String name, int chunk, boolean direct) throws Exception {
        byte[] encoded = Files.readAllBytes(root().resolve(name + ".bz2.bad"));
        Fixture valid = new Fixture("pyflate/hello-world", 12);
        byte[] validEncoded = Files.readAllBytes(root().resolve(valid.name() + ".bz2"));
        try (CompressionDecoder decoder = BZip2Codec.DEFAULT.newDecoder()) {
            for (int repeat = 0; repeat < 2; repeat++) {
                IOException failure = assertThrows(IOException.class,
                        () -> decode(decoder, new Fixture(name, 128L * 1024 * 1024), encoded,
                                chunk, 257, direct, true, false));
                if (name.equals("lbzip2/void")) {
                    assertInstanceOf(EOFException.class, failure);
                } else {
                    assertFalse(failure instanceof EOFException, failure.toString());
                }
                decoder.reset();
                assertDigest(valid, decode(decoder, valid, validEncoded, 1, 3, direct, true, true));
                decoder.reset();
            }
        }
    }

    /// Rejects garbage between or after frames instead of silently scanning for another stream header.
    @ParameterizedTest
    @ValueSource(strings = {"lbzip2/gap", "lbzip2/trash"})
    void rejectsTrailingGarbageInStreamAdapter(String name) throws IOException {
        try (InputStream input = BZip2Codec.DEFAULT.newInputStream(
                Files.newInputStream(root().resolve(name + ".bz2")))) {
            assertThrows(IOException.class, () -> input.transferTo(OutputStream.nullOutputStream()));
        }
    }

    /// Checks every byte truncation of small single-frame upstream samples, followed by a clean reset.
    @ParameterizedTest(name = "{0}, direct={1}")
    @MethodSource("truncationFixtures")
    void rejectsEveryTruncatedPrefix(Fixture fixture, boolean direct) throws Exception {
        byte[] encoded = Files.readAllBytes(root().resolve(fixture.name() + ".bz2"));
        try (CompressionDecoder decoder = BZip2Codec.DEFAULT.newDecoder()) {
            for (int length = 0; length < encoded.length; length++) {
                byte[] prefix = Arrays.copyOf(encoded, length);
                assertThrows(EOFException.class,
                        () -> decode(decoder, fixture, prefix, 7, 257, direct, true, false),
                        fixture + " prefix=" + length);
                decoder.reset();
            }
            assertDigest(fixture, decode(decoder, fixture, encoded, 7, 31, direct, true, true));
        }
    }

    /// Stops high-expansion samples at the configured output bound and checks recovery after the limit failure.
    @ParameterizedTest(name = "{0}, limit={1}")
    @MethodSource("limitedFixtures")
    void boundsLargeExpansion(Fixture fixture, int limit) throws Exception {
        byte[] encoded = Files.readAllBytes(root().resolve(fixture.name() + ".bz2"));
        ByteBuffer source = ByteBuffer.wrap(encoded).asReadOnlyBuffer();
        ByteBuffer target = ByteBuffer.allocate(257);
        try (CompressionDecoder decoder = BZip2Codec.DEFAULT.withMaximumOutputSize(limit).newDecoder()) {
            long[] produced = {0};
            DecompressionOutputLimitException failure = assertThrows(DecompressionOutputLimitException.class, () -> {
                while (true) {
                    target.clear();
                    try {
                        assertEquals(CodecOutcome.NEEDS_OUTPUT, decoder.finish(source, target));
                    } finally {
                        produced[0] += target.position();
                    }
                }
            });
            assertEquals(limit, failure.maximum());
            assertEquals(limit, produced[0]);
            assertTrue(failure.actual() > limit);
            decoder.reset();
            Fixture empty = new Fixture("pyflate/empty", 0);
            assertDigest(empty, decode(decoder, empty,
                    Files.readAllBytes(root().resolve(empty.name() + ".bz2")), 1, 1, false, true, true));
        }
    }

    /// Decodes complete frames with fresh input storage, preserving all bytes beyond the last expected frame.
    private static Digest decode(CompressionDecoder decoder, Fixture fixture, byte @Unmodifiable [] encoded,
                                 int chunk, int outputSize, boolean direct, boolean readOnly,
                                 boolean appendSentinel) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        int end = fixture.hasTrailingGarbage() ? 45 : encoded.length;
        byte[] sourceBytes = Arrays.copyOf(encoded, encoded.length + (appendSentinel ? 3 : 0));
        if (appendSentinel) {
            sourceBytes[encoded.length] = 0x12;
            sourceBytes[encoded.length + 1] = 0x34;
            sourceBytes[encoded.length + 2] = 0x56;
        }
        ByteBuffer storage = direct ? ByteBuffer.allocateDirect(Math.min(chunk, sourceBytes.length) + 4)
                : ByteBuffer.allocate(Math.min(chunk, sourceBytes.length) + 4);
        ByteBuffer source = storage.duplicate().limit(0);
        ByteBuffer target = direct ? ByteBuffer.allocateDirect(outputSize + 4) : ByteBuffer.allocate(outputSize + 4);
        int supplied = 0;
        int consumed = 0;
        long produced = 0;
        boolean probeEmpty = true;
        while (true) {
            if (!source.hasRemaining()) {
                // Poison returned input storage before reusing it, including after NEEDS_OUTPUT.
                storage.clear();
                while (storage.hasRemaining()) storage.put((byte) 0xa5);
                storage.clear().position(2);
                int count = Math.min(chunk, sourceBytes.length - supplied);
                storage.put(sourceBytes, supplied, count).flip().position(2);
                source = readOnly ? storage.asReadOnlyBuffer() : storage.duplicate();
                supplied += count;
            }
            target.clear();
            while (target.hasRemaining()) target.put((byte) 0x5a);
            target.position(2).limit(probeEmpty ? 2 : outputSize + 2);
            boolean wasEmptyProbe = probeEmpty;
            probeEmpty = false;
            int before = source.position();
            int sourceLimit = source.limit();
            int targetLimit = target.limit();
            CodecOutcome outcome = supplied == sourceBytes.length
                    ? decoder.finish(source, target) : decoder.decode(source, target);
            consumed += source.position() - before;
            assertEquals(sourceLimit, source.limit());
            assertEquals(targetLimit, target.limit());
            assertEquals((byte) 0x5a, target.get(0));
            assertEquals((byte) 0x5a, target.get(1));
            assertEquals((byte) 0x5a, target.duplicate().clear().get(outputSize + 2));
            assertEquals((byte) 0x5a, target.duplicate().clear().get(outputSize + 3));
            int outputCount = target.position() - 2;
            produced += outputCount;
            assertTrue(produced <= fixture.size(), "Output exceeds expected size: " + fixture);
            digest.update(target.flip().position(2));
            if (outcome == CodecOutcome.FINISHED) {
                if (consumed < end) {
                    assertTrue(consumed > 0);
                    decoder.reset();
                    continue;
                }
                assertEquals(end, consumed);
                byte[] remainder = new byte[source.remaining()];
                source.duplicate().get(remainder);
                assertArrayEquals(Arrays.copyOfRange(sourceBytes, consumed, supplied), remainder);
                int position = source.position();
                assertEquals(CodecOutcome.FINISHED, decoder.decode(source, ByteBuffer.allocate(0)));
                assertEquals(position, source.position());
                return new Digest(produced, HexFormat.of().formatHex(digest.digest()));
            }
            if (outcome == CodecOutcome.NEEDS_INPUT) {
                assertFalse(source.hasRemaining());
                assertTrue(supplied < sourceBytes.length);
            } else {
                assertEquals(CodecOutcome.NEEDS_OUTPUT, outcome);
                assertEquals(wasEmptyProbe ? 0 : outputSize, outputCount);
            }
        }
    }

    /// Computes a bounded streaming digest without allocating a buffer proportional to decoded size.
    private static Digest digest(InputStream input) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        byte[] buffer = new byte[32768];
        long size = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            assertTrue(count > 0);
            size += count;
            assertTrue(size <= 128L * 1024 * 1024, "Unexpected expansion beyond corpus bound");
            digest.update(buffer, 0, count);
        }
        return new Digest(size, HexFormat.of().formatHex(digest.digest()));
    }

    /// Checks the upstream MD5 identity and independently measured decoded size.
    private static void assertDigest(Fixture fixture, Digest actual) throws IOException {
        String reference = Files.readString(root().resolve(fixture.name() + ".md5")).trim();
        assertTrue(reference.matches("[0-9a-f]{32}\\s+-"), reference);
        assertEquals(new Digest(fixture.size(), reference.substring(0, 32)), actual, fixture.name());
    }

    /// Returns all valid fixtures, including the two streams followed by garbage.
    private static Stream<Fixture> validFixtures() {
        return VALID.stream();
    }

    /// Returns the small cases for exhaustive buffer schedules without multiplying large expansion work.
    private static Stream<Arguments> fragmentedFixtures() {
        return VALID.stream().filter(f -> f.size() <= 16384).flatMap(f -> Stream.of(1, 2, 7, 8192, 65536,
                Integer.MAX_VALUE).flatMap(chunk -> Stream.of(false, true).flatMap(direct -> Stream.of(false, true)
                .map(readOnly -> Arguments.of(f, chunk, direct, readOnly)))));
    }

    /// Returns corpus inputs small enough for routine recompression at both extreme block sizes.
    private static Stream<Fixture> recompressionFixtures() {
        return VALID.stream().filter(f -> f.size() <= 2 * 1024 * 1024);
    }

    /// Returns short single-frame samples; concatenation boundaries are not truncated encodings.
    private static Stream<Arguments> truncationFixtures() throws IOException {
        List<Fixture> fixtures = VALID.stream().filter(f -> f.size() <= 16384 && !f.hasTrailingGarbage()
                && !f.name().equals("lbzip2/concat") && !f.name().equals("commons-compress/multiple")).toList();
        Stream.Builder<Arguments> arguments = Stream.builder();
        for (Fixture fixture : fixtures) {
            if (Files.size(root().resolve(fixture.name() + ".bz2")) <= 600) {
                arguments.add(Arguments.of(fixture, false));
                arguments.add(Arguments.of(fixture, true));
            }
        }
        return arguments.build();
    }

    /// Returns all large-expansion fixtures with limits spanning empty, tiny, and buffered output.
    private static Stream<Arguments> limitedFixtures() {
        return VALID.stream().filter(f -> f.size() > 2 * 1024 * 1024)
                .flatMap(f -> Stream.of(0, 1, 31, 8192).map(limit -> Arguments.of(f, limit)));
    }

    /// Returns every malformed sample with tiny, intermediate, and complete-buffer input.
    private static Stream<Arguments> invalidFixtures() {
        return INVALID.stream().flatMap(name -> Stream.of(1, 7, Integer.MAX_VALUE)
                .flatMap(chunk -> Stream.of(false, true).map(direct -> Arguments.of(name, chunk, direct))));
    }

    /// Returns the Gradle-prepared source corpus directory.
    private static Path root() {
        return Path.of(Objects.requireNonNull(System.getProperty("arkivo.bzip2.compatibilityDirectory"),
                "Missing bzip2 compatibility corpus directory"));
    }

    /// Identifies a fixture and the expected total decoded byte count.
    ///
    /// @param name the relative path without the compressed-file suffix
    /// @param size the decoded byte count
    @NotNullByDefault
    private record Fixture(String name, long size) {
        /// Returns whether only the first frame precedes intentionally non-BZip2 bytes.
        boolean hasTrailingGarbage() {
            return name.equals("lbzip2/gap") || name.equals("lbzip2/trash");
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /// Holds a decoded length and the digest of those bytes.
    ///
    /// @param size the decoded byte count
    /// @param md5 the lowercase hexadecimal MD5 identity
    @NotNullByDefault
    private record Digest(long size, String md5) {
    }
}
