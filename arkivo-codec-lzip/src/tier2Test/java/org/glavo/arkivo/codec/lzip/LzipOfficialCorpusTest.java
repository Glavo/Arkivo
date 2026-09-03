// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzip;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies Arkivo against every lzip fixture in the official XZ Utils 5.8.3 decoder corpus.
@NotNullByDefault
public final class LzipOfficialCorpusTest {
    /// The system property containing the extracted official corpus directory.
    private static final String TEST_DATA_DIRECTORY_PROPERTY = "arkivo.lzip.testDataDirectory";

    /// The maximum output accepted from one official corpus file.
    private static final long MAXIMUM_OUTPUT_SIZE = 1_024L;

    /// The decoded content of the first member in the official two-member fixture.
    private static final byte @Unmodifiable [] FIRST_MEMBER_CONTENT =
            "Hello\n".getBytes(StandardCharsets.US_ASCII);

    /// The decoded content of the second member in the official two-member fixture.
    private static final byte @Unmodifiable [] SECOND_MEMBER_CONTENT =
            "World!\n".getBytes(StandardCharsets.US_ASCII);

    /// The complete decoded content shared by the official valid fixtures.
    private static final byte @Unmodifiable [] MEMBER_CONTENT =
            "Hello\nWorld!\n".getBytes(StandardCharsets.US_ASCII);

    /// The exact compressed size of the first official version-one member.
    private static final int FIRST_MEMBER_COMPRESSED_SIZE = 42;

    /// Every official lzip fixture classified by this test suite.
    private static final @Unmodifiable Set<String> CLASSIFIED_FIXTURES = Set.of(
            "bad-1-v0-uncomp-size.lz",
            "bad-1-v1-crc32.lz",
            "bad-1-v1-dict-1.lz",
            "bad-1-v1-dict-2.lz",
            "bad-1-v1-magic-1.lz",
            "bad-1-v1-magic-2.lz",
            "bad-1-v1-member-size.lz",
            "bad-1-v1-trailing-magic.lz",
            "bad-1-v1-uncomp-size.lz",
            "good-1-v0-trailing-1.lz",
            "good-1-v0.lz",
            "good-1-v1-trailing-1.lz",
            "good-1-v1-trailing-2.lz",
            "good-1-v1.lz",
            "good-2-v0-v1.lz",
            "good-2-v1-v0.lz",
            "good-2-v1-v1.lz",
            "unsupported-1-v234.lz"
    );

    /// Verifies complete supported version-one files against independently fixed decoded bytes.
    @ParameterizedTest(name = "{0}")
    @MethodSource("completeVersionOneFixtures")
    public void decompressesOfficialVersionOneFiles(String name) throws IOException {
        assertArrayEquals(
                MEMBER_CONTENT,
                decompressComplete(corpusFile(name)),
                name
        );
    }

    /// Verifies one-frame decoding stops exactly between the two independently encoded official members.
    @Test
    public void preservesOfficialVersionOneMemberBoundary() throws IOException {
        ByteBuffer source = ByteBuffer.wrap(Files.readAllBytes(corpusFile("good-2-v1-v1.lz")));
        ByteBuffer firstTarget = ByteBuffer.allocate(FIRST_MEMBER_CONTENT.length);

        new LzipCodec().decompressFrame(source, firstTarget);

        firstTarget.flip();
        assertArrayEquals(FIRST_MEMBER_CONTENT, bytes(firstTarget));
        assertEquals(FIRST_MEMBER_COMPRESSED_SIZE, source.position());

        ByteBuffer secondTarget = ByteBuffer.allocate(SECOND_MEMBER_CONTENT.length);
        new LzipCodec().decompressFrame(source, secondTarget);

        secondTarget.flip();
        assertArrayEquals(SECOND_MEMBER_CONTENT, bytes(secondTarget));
        assertFalse(source.hasRemaining());
    }

    /// Verifies one-frame decoding stops before every byte of official trailing data.
    @ParameterizedTest(name = "{0}")
    @MethodSource("trailingVersionOneFixtures")
    public void preservesOfficialTrailingData(TrailingFixture fixture) throws IOException {
        byte @Unmodifiable [] encoded = Files.readAllBytes(corpusFile(fixture.name()));
        ByteBuffer source = ByteBuffer.wrap(encoded);
        ByteBuffer target = ByteBuffer.allocate(MEMBER_CONTENT.length);

        new LzipCodec().decompressFrame(source, target);

        target.flip();
        assertArrayEquals(MEMBER_CONTENT, bytes(target), fixture.name());
        assertArrayEquals(
                fixture.trailingBytes(),
                bytes(source.slice()),
                fixture.name()
        );
        assertEquals(encoded.length - fixture.trailingBytes().length, source.position(), fixture.name());
    }

    /// Verifies every officially malformed lzip file fails with a checked decoding error.
    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedFixtureNames")
    public void rejectsOfficialMalformedMember(String name) {
        assertThrows(IOException.class, () -> decompressComplete(corpusFile(name)), name);
    }

    /// Verifies obsolete version-zero members and undefined versions are rejected as unsupported dialects.
    @ParameterizedTest(name = "{0}")
    @MethodSource("unsupportedFixtureNames")
    public void rejectsUnsupportedOfficialMemberVersion(String name) {
        assertThrows(IOException.class, () -> decompressComplete(corpusFile(name)), name);
    }

    /// Verifies the pinned corpus inventory remains exhaustively classified and retains its provenance files.
    @Test
    public void classifiesCompleteOfficialCorpusAndProvenance() throws IOException {
        @Unmodifiable Set<String> actual;
        try (Stream<Path> files = Files.list(corpusPath("tests/files"))) {
            actual = files
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".lz"))
                    .collect(Collectors.toUnmodifiableSet());
        }

        assertEquals(CLASSIFIED_FIXTURES, actual);
        assertTrue(Files.isRegularFile(corpusPath("COPYING")));
        assertTrue(Files.isRegularFile(corpusPath("tests/files/README")));
        assertTrue(Files.isRegularFile(corpusPath("UPSTREAM.properties")));
    }

    /// Returns supported complete version-one files.
    private static Stream<String> completeVersionOneFixtures() {
        return Stream.of(
                "good-1-v1.lz",
                "good-2-v1-v1.lz"
        );
    }

    /// Returns supported version-one files carrying caller-visible trailing bytes.
    private static Stream<TrailingFixture> trailingVersionOneFixtures() {
        return Stream.of(
                new TrailingFixture(
                        "good-1-v1-trailing-1.lz",
                        "Trailing garbage\n".getBytes(StandardCharsets.US_ASCII)
                ),
                new TrailingFixture(
                        "good-1-v1-trailing-2.lz",
                        "LZITrailing garbage\n".getBytes(StandardCharsets.US_ASCII)
                ),
                new TrailingFixture(
                        "bad-1-v1-trailing-magic.lz",
                        "LZIP".getBytes(StandardCharsets.US_ASCII)
                )
        );
    }

    /// Returns every officially malformed lzip file name.
    private static @Unmodifiable List<String> malformedFixtureNames() throws IOException {
        try (Stream<Path> files = Files.list(corpusPath("tests/files"))) {
            return files
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("bad-") && name.endsWith(".lz"))
                    .sorted()
                    .toList();
        }
    }

    /// Returns historical or undefined lzip versions intentionally unsupported by the current format.
    private static Stream<String> unsupportedFixtureNames() {
        return Stream.of(
                "good-1-v0.lz",
                "good-1-v0-trailing-1.lz",
                "good-2-v0-v1.lz",
                "good-2-v1-v0.lz",
                "unsupported-1-v234.lz"
        );
    }

    /// Decompresses all complete version-one members through the public allocating API.
    private static byte @Unmodifiable [] decompressComplete(Path sourcePath) throws IOException {
        ByteBuffer source = ByteBuffer.wrap(Files.readAllBytes(sourcePath));
        ByteBuffer decoded = new LzipCodec()
                .withMaximumOutputSize(MAXIMUM_OUTPUT_SIZE)
                .decompress(source);
        return bytes(decoded);
    }

    /// Returns one official corpus file.
    private static Path corpusFile(String name) {
        return corpusPath("tests/files").resolve(name);
    }

    /// Returns a path below the configured extracted corpus directory.
    private static Path corpusPath(String relativePath) {
        @Nullable String configured = System.getProperty(TEST_DATA_DIRECTORY_PROPERTY);
        if (configured == null) {
            throw new IllegalStateException("Missing system property: " + TEST_DATA_DIRECTORY_PROPERTY);
        }
        return Path.of(configured).resolve(relativePath);
    }

    /// Copies the remaining bytes of a buffer without changing the original buffer state.
    private static byte @Unmodifiable [] bytes(ByteBuffer buffer) {
        ByteBuffer source = buffer.slice();
        byte[] bytes = new byte[source.remaining()];
        source.get(bytes);
        return bytes;
    }

    /// Describes one supported member followed by caller-visible bytes.
    ///
    /// @param name the official corpus file name
    /// @param trailingBytes the expected trailing byte sequence
    @NotNullByDefault
    private record TrailingFixture(String name, byte @Unmodifiable [] trailingBytes) {
        /// Copies the expected trailing bytes at construction.
        private TrailingFixture {
            trailingBytes = trailingBytes.clone();
        }

        /// Returns a copy of the expected trailing bytes.
        @Override
        public byte @Unmodifiable [] trailingBytes() {
            return trailingBytes.clone();
        }

        /// Returns the corpus file name used as the parameterized-test display value.
        @Override
        public String toString() {
            return name;
        }
    }
}
