// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzip;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// Exercises every lzip 1.26 reference member through bounded incremental and stream decoding.
@NotNullByDefault
final class LzipReferenceCorpusTest {
    /// Bounds allocations and output even when testing damaged compressed data.
    private static final LzipCodec CODEC = new LzipCodec()
            .withMaximumWindowSize(65536).withMaximumOutputSize(131072);

    /// Valid complete members supplied by the reference implementation.
    private static final @Unmodifiable List<String> VALID = List.of("fox.lz", "test.txt.lz");

    /// Members rejected by the upstream integrity tests, including a nonzero initial range-coder byte.
    private static final @Unmodifiable Set<String> INVALID = Set.of(
            "fox_bcrc.lz", "fox_crc0.lz", "fox_das46.lz", "fox_de20.lz",
            "fox_mes81.lz", "fox_nz.lz", "fox_s11.lz", "fox_v2.lz");

    /// Independently decoded plaintext of the small upstream reference member.
    private static final byte @Unmodifiable [] FOX =
            "The quick brown fox jumps over the lazy dog.\n".getBytes(StandardCharsets.US_ASCII);

    /// Combines fragmented input, tiny output, and heap or direct read-only sources.
    private static Stream<Arguments> validConfigurations() {
        return VALID.stream().flatMap(name -> Stream.of(1, 7, 8192).flatMap(chunk ->
                Stream.of(1, 257).flatMap(output -> Stream.of(false, true)
                        .map(direct -> Arguments.of(name, chunk, output, direct)))));
    }

    /// Includes header, payload, and trailer errors with each source-buffer layout.
    private static Stream<Arguments> invalidConfigurations() {
        return INVALID.stream().sorted().flatMap(name -> Stream.of(1, 7, 8192).flatMap(chunk ->
                Stream.of(false, true).map(direct -> Arguments.of(name, chunk, direct))));
    }

    /// Includes every proper prefix of the small member and upstream multi-member truncation boundaries.
    private static Stream<Arguments> truncations() {
        return Stream.of(1, 8192).flatMap(chunk -> Stream.concat(
                IntStream.range(0, 80).mapToObj(length -> Arguments.of("fox.lz", length, chunk)),
                IntStream.of(6, 20, 14664, 14683, 14684, 14685, 14686, 14687, 14688)
                        .mapToObj(length -> Arguments.of("test.txt.lz", length, chunk))));
    }

    /// Supplies the same input fragments to engine-boundary and concatenated-stream tests.
    private static Stream<Arguments> memberConfigurations() {
        return Stream.of(1, 7, 8192).flatMap(chunk ->
                Stream.of(false, true).map(direct -> Arguments.of(chunk, direct)));
    }

    /// Selects failures that occur only after all original plaintext has been decoded.
    private static Stream<Arguments> trailerFailures() {
        return Stream.of("fox_bcrc.lz", "fox_crc0.lz", "fox_das46.lz", "fox_mes81.lz")
                .flatMap(name -> Stream.of(false, true).map(direct -> Arguments.of(name, direct)));
    }

    /// Pairs the valid fixtures with their independently decoded dictionary-size header fields.
    private static Stream<Arguments> dictionaries() {
        return Stream.of(Arguments.of("fox.lz", 4096), Arguments.of("test.txt.lz", 18432));
    }

    /// Checks exact plaintext, consumed bytes, canaries, and successful reset with independent reference input.
    @ParameterizedTest(name = "{0}, input={1}, output={2}, direct={3}")
    @MethodSource("validConfigurations")
    void decodesReferenceMembers(String name, int chunk, int output, boolean direct) throws IOException {
        byte[] encoded = fixture(name);
        byte[] expected = plaintext(name);
        try (var decoder = CODEC.newDecoder()) {
            DecodeResult result = decodeMember(decoder, encoded, 0, chunk, output, direct);
            assertArrayEquals(expected, result.content());
            assertEquals(encoded.length, result.consumed());
            decoder.reset();
            assertArrayEquals(FOX, decodeMember(decoder, fixture("fox.lz"), 0, chunk, output, direct).content());
        }
    }

    /// Requires checked failures and verifies reset clears failed header, payload, and integrity state.
    @ParameterizedTest(name = "{0}, input={1}, direct={2}")
    @MethodSource("invalidConfigurations")
    void rejectsDamagedMembersAndRecovers(String name, int chunk, boolean direct) throws IOException {
        byte[] encoded = fixture(name);
        try (var decoder = CODEC.newDecoder()) {
            assertThrows(IOException.class, () -> decodeMember(decoder, encoded, 0, chunk, 17, direct));
            decoder.reset();
            assertArrayEquals(FOX, decodeMember(decoder, fixture("fox.lz"), 0, chunk, 17, direct).content());
        }
    }

    /// Retains the full plaintext and exact positions when a trailer rejects the completed payload.
    @ParameterizedTest(name = "{0}, direct={1}")
    @MethodSource("trailerFailures")
    void trailerFailureRetainsProgress(String name, boolean direct) throws IOException {
        byte[] member = fixture(name);
        ByteBuffer storage = direct ? ByteBuffer.allocateDirect(member.length + 6)
                : ByteBuffer.allocate(member.length + 6);
        storage.position(3).put(member).limit(member.length + 3).position(3);
        ByteBuffer source = storage.asReadOnlyBuffer();
        ByteBuffer target = direct ? ByteBuffer.allocateDirect(FOX.length + 7) : ByteBuffer.allocate(FOX.length + 7);
        while (target.hasRemaining()) {
            target.put((byte) 0x5a);
        }
        target.limit(FOX.length + 4).position(3);
        try (var decoder = CODEC.newDecoder()) {
            assertThrows(IOException.class, () -> decoder.finish(source, target));
            assertEquals(member.length + 3, source.position());
            assertEquals(member.length + 3, source.limit());
            assertEquals(FOX.length + 3, target.position());
            assertEquals(FOX.length + 4, target.limit());
            byte[] actual = new byte[FOX.length];
            target.duplicate().position(3).get(actual);
            assertArrayEquals(FOX, actual);
            target.clear();
            for (int i = 0; i < 3; i++) {
                assertEquals((byte) 0x5a, target.get(i));
            }
            for (int i = FOX.length + 3; i < target.capacity(); i++) {
                assertEquals((byte) 0x5a, target.get(i));
            }
        }
    }

    /// Enforces the declared dictionary size rather than the bytes actually needed by a short member.
    @ParameterizedTest(name = "{0}, dictionary={1}")
    @MethodSource("dictionaries")
    void honorsReferenceDictionaryLimit(String name, int dictionary) throws IOException {
        byte[] encoded = fixture(name);
        ByteBuffer restored = CODEC.withMaximumWindowSize(dictionary).decompress(ByteBuffer.wrap(encoded));
        assertEquals(ByteBuffer.wrap(plaintext(name)), restored);
        try (var decoder = CODEC.withMaximumWindowSize(dictionary - 1L).newDecoder()) {
            ByteBuffer source = ByteBuffer.wrap(encoded);
            ByteBuffer target = ByteBuffer.allocate(plaintext(name).length + 1);
            assertThrows(IOException.class, () -> decoder.finish(source, target));
            assertEquals(6, source.position());
            assertEquals(0, target.position());
        }
    }

    /// Accepts the exact cumulative output budget and rejects smaller budgets for original member data.
    @ParameterizedTest(name = "{0}")
    @MethodSource("validNames")
    void honorsReferenceOutputLimit(String name) throws IOException {
        byte[] encoded = fixture(name);
        byte[] expected = plaintext(name);
        assertEquals(ByteBuffer.wrap(expected), CODEC.withMaximumOutputSize(expected.length)
                .decompress(ByteBuffer.wrap(encoded)));
        for (int limit : new int[]{0, expected.length - 1}) {
            assertThrows(IOException.class, () -> CODEC.withMaximumOutputSize(limit)
                    .decompress(ByteBuffer.wrap(encoded)));
        }
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        joined.writeBytes(encoded);
        joined.writeBytes(encoded);
        byte[] twoMembers = joined.toByteArray();
        for (int limit : new int[]{expected.length, 2 * expected.length - 1}) {
            try (var input = CODEC.withMaximumOutputSize(limit)
                    .newInputStream(new ByteArrayInputStream(twoMembers))) {
                assertThrows(IOException.class, input::readAllBytes);
            }
        }
        try (var input = CODEC.withMaximumOutputSize(2L * expected.length)
                .newInputStream(new ByteArrayInputStream(twoMembers))) {
            ByteArrayOutputStream twoCopies = new ByteArrayOutputStream();
            twoCopies.writeBytes(expected);
            twoCopies.writeBytes(expected);
            assertArrayEquals(twoCopies.toByteArray(), input.readAllBytes());
        }
    }

    /// Returns the names of members with independently known plaintext.
    private static Stream<String> validNames() {
        return VALID.stream();
    }

    /// Rejects incomplete first or later members without discarding valid preceding members.
    @ParameterizedTest(name = "{0}, prefix={1}, input={2}")
    @MethodSource("truncations")
    void rejectsTruncatedMembers(String name, int length, int chunk) throws IOException {
        byte[] member = fixture(name);
        ByteArrayOutputStream members = new ByteArrayOutputStream();
        for (int i = 0; i < 3; i++) {
            members.writeBytes(member);
        }
        byte[] truncated = Arrays.copyOf(members.toByteArray(), length);
        try (var decoder = CODEC.newDecoder()) {
            int offset = 0;
            while (length - offset >= member.length) {
                DecodeResult result = decodeMember(decoder, truncated, offset, chunk, 257, true);
                assertArrayEquals(plaintext(name), result.content());
                assertEquals(member.length, result.consumed());
                offset += result.consumed();
                decoder.reset();
            }
            int incompleteMember = offset;
            assertThrows(IOException.class, () ->
                    decodeMember(decoder, truncated, incompleteMember, chunk, 257, true));
            decoder.reset();
            assertArrayEquals(FOX, decodeMember(decoder, fixture("fox.lz"), 0, chunk, 257, true).content());
        }
    }

    /// Checks exact frame boundaries before other members and before unrecognized trailing bytes.
    @ParameterizedTest(name = "input={0}, direct={1}")
    @MethodSource("memberConfigurations")
    void preservesMemberBoundaries(int chunk, boolean direct) throws IOException {
        List<String> names = List.of("fox.lz", "test.txt.lz", "fox.lz");
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (String name : names) {
            joined.writeBytes(fixture(name));
        }
        byte[] trailing = "LZIP\001".getBytes(StandardCharsets.US_ASCII);
        joined.writeBytes(trailing);
        byte[] encoded = joined.toByteArray();
        int offset = 0;
        try (var decoder = CODEC.newDecoder()) {
            for (String name : names) {
                DecodeResult result = decodeMember(decoder, encoded, offset, chunk, 257, direct);
                assertArrayEquals(plaintext(name), result.content());
                assertEquals(fixture(name).length, result.consumed());
                offset += result.consumed();
                decoder.reset();
            }
        }
        assertArrayEquals(trailing, Arrays.copyOfRange(encoded, offset, encoded.length));
    }

    /// Joins independently encoded members through a short-reading stream, including conservative available().
    @ParameterizedTest(name = "input={0}, zeroAvailable={1}")
    @MethodSource("memberConfigurations")
    void readsConcatenatedMembers(int chunk, boolean zeroAvailable) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        for (String name : List.of("fox.lz", "test.txt.lz", "fox.lz")) {
            encoded.writeBytes(fixture(name));
            expected.writeBytes(plaintext(name));
        }
        try (var input = CODEC.newInputStream(new FragmentedInput(encoded.toByteArray(), chunk, zeroAvailable))) {
            assertArrayEquals(expected.toByteArray(), input.readAllBytes());
            assertEquals(-1, input.read());
            assertEquals(-1, input.read());
        }
    }

    /// Detects unclassified fixtures and preserves source notices alongside the extracted corpus.
    @Test
    void classifiesCompleteCorpus() throws IOException {
        Set<String> expected = Stream.concat(VALID.stream(), INVALID.stream()).collect(Collectors.toSet());
        try (var files = Files.list(corpus().resolve("testsuite"))) {
            assertEquals(expected, files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".lz")).collect(Collectors.toSet()));
        }
        assertEquals(80, fixture("fox.lz").length);
        assertEquals(7341, fixture("test.txt.lz").length);
        assertEquals(36301, plaintext("test.txt.lz").length);
        for (String name : List.of("COPYING", "README", "main.cc", "testsuite/check.sh", "UPSTREAM.properties")) {
            assertTrue(Files.isRegularFile(corpus().resolve(name)), name);
        }
    }

    /// Drives one frame while preserving trailing bytes and detecting writes outside the selected output region.
    private static DecodeResult decodeMember(CompressionDecoder decoder, byte[] encoded, int offset,
                                             int chunk, int outputSize, boolean direct) throws IOException {
        ByteBuffer sourceStorage = direct ? ByteBuffer.allocateDirect(chunk + 6) : ByteBuffer.allocate(chunk + 6);
        ByteBuffer target = direct ? ByteBuffer.allocateDirect(outputSize + 6) : ByteBuffer.allocate(outputSize + 6);
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        int position = offset;
        for (int calls = 0; calls < encoded.length + 131073; calls++) {
            int offered = Math.min(chunk, encoded.length - position);
            sourceStorage.clear().position(3);
            sourceStorage.put(encoded, position, offered).limit(3 + offered).position(3);
            ByteBuffer source = sourceStorage.asReadOnlyBuffer();
            target.clear();
            while (target.hasRemaining()) {
                target.put((byte) 0x5a);
            }
            target.limit(3 + outputSize).position(3);
            CodecOutcome outcome = position + offered == encoded.length
                    ? decoder.finish(source, target) : decoder.decode(source, target);
            int consumed = source.position() - 3;
            int produced = target.position() - 3;
            position += consumed;
            assertEquals(3 + offered, source.limit());
            assertEquals(3 + outputSize, target.limit());
            for (int i = 3; i < 3 + produced; i++) {
                decoded.write(target.get(i));
            }
            target.clear();
            for (int i = 0; i < 3; i++) {
                assertEquals((byte) 0x5a, target.get(i));
                assertEquals((byte) 0x5a, target.get(3 + outputSize + i));
            }
            // Destroy the offered data after every return; the decoder must not retain the caller's buffer.
            sourceStorage.clear();
            while (sourceStorage.hasRemaining()) {
                sourceStorage.put((byte) 0xcc);
            }
            assertTrue(decoded.size() <= 131072, "Unbounded output from damaged input");
            if (outcome == CodecOutcome.FINISHED) {
                return new DecodeResult(decoded.toByteArray(), position - offset);
            }
            assertTrue(consumed > 0 || produced > 0, "Decoder made no progress");
            if (outcome == CodecOutcome.NEEDS_INPUT) {
                assertEquals(offered, consumed);
            } else {
                assertEquals(CodecOutcome.NEEDS_OUTPUT, outcome);
                assertEquals(outputSize, produced);
            }
        }
        return fail("Decoder exceeded the bounded progress count");
    }

    /// Returns the independent plaintext paired with one valid compressed fixture.
    private static byte @Unmodifiable [] plaintext(String name) throws IOException {
        return name.equals("fox.lz") ? FOX : Files.readAllBytes(corpus().resolve("testsuite/test.txt"));
    }

    /// Reads one original compressed fixture without modifying the cached corpus.
    private static byte[] fixture(String name) throws IOException {
        return Files.readAllBytes(corpus().resolve("testsuite").resolve(name));
    }

    /// Resolves the Gradle-provided, verified reference corpus directory.
    private static Path corpus() {
        @Nullable String directory = System.getProperty("arkivo.lzip.referenceDirectory");
        if (directory == null) {
            throw new IllegalStateException("Missing system property: arkivo.lzip.referenceDirectory");
        }
        return Path.of(directory);
    }

    /// Records one frame's output and exact input extent.
    ///
    /// @param content the decoded bytes, never modified after publication
    /// @param consumed the number of compressed bytes consumed from the supplied offset
    @NotNullByDefault
    private record DecodeResult(byte @Unmodifiable [] content, int consumed) {
    }

    /// Restricts physical reads without equating available() with end-of-input.
    @NotNullByDefault
    private static final class FragmentedInput extends ByteArrayInputStream {
        /// The largest physical read returned to the adapter.
        private final int chunk;

        /// Whether available() deliberately underestimates the remaining input.
        private final boolean zeroAvailable;

        /// Wraps the complete independently encoded stream.
        private FragmentedInput(byte[] bytes, int chunk, boolean zeroAvailable) {
            super(bytes);
            this.chunk = chunk;
            this.zeroAvailable = zeroAvailable;
        }

        /// Limits each bulk read to the configured fragment size.
        @Override
        public synchronized int read(byte[] bytes, int offset, int length) {
            return super.read(bytes, offset, Math.min(length, chunk));
        }

        /// Reports either the real remaining byte count or a conservative zero.
        @Override
        public synchronized int available() {
            return zeroAvailable ? 0 : super.available();
        }
    }
}
