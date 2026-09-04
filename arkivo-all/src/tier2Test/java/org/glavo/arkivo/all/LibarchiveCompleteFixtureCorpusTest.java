// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFormat;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.archive.ArkivoStreamingReader;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.CompressionFormats;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Accounts for and safely exercises every uuencoded fixture in the pinned libarchive source release.
@NotNullByDefault
final class LibarchiveCompleteFixtureCorpusTest {
    /// The system property containing the prepared libarchive corpus directory.
    private static final String TEST_DATA_DIRECTORY_PROPERTY = "arkivo.libarchive.testDataDirectory";

    /// The exact fixture count in libarchive 3.8.7.
    private static final int EXPECTED_FIXTURE_COUNT = 385;

    /// The aggregate encoded byte size of all libarchive 3.8.7 fixtures.
    private static final long EXPECTED_ENCODED_SIZE = 9_165_843L;

    /// The SHA-256 digest of naturally sorted fixture names, each terminated by one line-feed byte.
    private static final String EXPECTED_NAME_DIGEST =
            "d21d447281e680dd2d332a3f364c1c0cd5a69e4fb1d3d90ddee9fa26de0234d1";

    /// The SHA-256 digest of every fixture name and its complete stable probe outcome.
    private static final String EXPECTED_OUTCOME_DIGEST =
            "d78f4d15b987ee21d6c2919fd23038dd80c15b01320d4d071400b3b7fd5de69c";

    /// The maximum decoded stream retained while probing a compression fixture.
    private static final long MAXIMUM_DECODED_SIZE = 64L * 1024L * 1024L;

    /// The bounded read policy used for untrusted upstream archive fixtures.
    private static final ArchiveReadOptions READ_OPTIONS = ArchiveReadOptions.DEFAULT.withLimits(
            ArchiveReadLimits.builder()
                    .maximumEntryCount(4096L)
                    .maximumEntrySize(32L * 1024L * 1024L)
                    .maximumTotalEntrySize(MAXIMUM_DECODED_SIZE)
                    .maximumMetadataSize(16L * 1024L * 1024L)
                    .maximumCompressionWindowSize(MAXIMUM_DECODED_SIZE)
                    .maximumDecoderMemorySize(128L * 1024L * 1024L)
                    .maximumDecodedArchiveSize(MAXIMUM_DECODED_SIZE)
                    .maximumOuterCompressionLayers(4L)
                    .build()
    );

    /// Verifies exact fixture membership and encoded size for the pinned source release.
    @Test
    void accountsForCompleteFixtureCorpus() throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long encodedSize = 0L;
        int fixtureCount = 0;
        try (Stream<String> fixtures = fixtureNames()) {
            for (String fixture : fixtures.toList()) {
                digest.update(fixture.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
                encodedSize = Math.addExact(encodedSize, Files.size(fixturePath(fixture)));
                fixtureCount++;
            }
        }
        assertEquals(EXPECTED_FIXTURE_COUNT, fixtureCount);
        assertEquals(EXPECTED_ENCODED_SIZE, encodedSize);
        assertEquals(EXPECTED_NAME_DIGEST, HexFormat.of().formatHex(digest.digest()));
    }

    /// Verifies the complete corpus contains detectable samples for every supported archive family.
    @Test
    void detectsEverySupportedArchiveFamily() throws IOException {
        Set<String> detected = new TreeSet<>();
        try (Stream<String> fixtures = fixtureNames()) {
            for (String fixture : fixtures.toList()) {
                byte @Unmodifiable [] content = LibarchiveUuDecoder.decode(fixturePath(fixture));
                @Nullable ArkivoFormat format = ArkivoFormats.detect(ByteBuffer.wrap(content).asReadOnlyBuffer());
                if (format != null) {
                    detected.add(format.name());
                }
            }
        }
        assertTrue(detected.containsAll(Set.of("7z", "ar", "cpio", "rar", "tar", "zip")), detected::toString);
    }

    /// Verifies the complete corpus contains detectable samples for its supported compression families.
    @Test
    void detectsSupportedCompressionFamilies() throws IOException {
        Set<String> detected = new TreeSet<>();
        try (Stream<String> fixtures = fixtureNames()) {
            for (String fixture : fixtures.toList()) {
                byte @Unmodifiable [] content = LibarchiveUuDecoder.decode(fixturePath(fixture));
                @Nullable CompressionFormat format = CompressionFormats.detect(
                        ByteBuffer.wrap(content).asReadOnlyBuffer()
                );
                if (format != null) {
                    detected.add(format.name());
                }
            }
        }
        assertTrue(
                detected.containsAll(Set.of("bzip2", "compress", "gzip", "lz4", "lzip", "xz", "zstd")),
                detected::toString
        );
    }

    /// Verifies that every fixture retains its current detection and complete-read disposition.
    ///
    /// The digest covers naturally sorted fixture names, detected format names, and independent streaming,
    /// file-system, decompression, and nested-archive results. A recognized fixture changing from successful reading
    /// to checked rejection therefore fails this test instead of being silently accepted as another valid outcome.
    @Test
    void retainsStableFixtureOutcomes() throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        Map<String, Integer> outcomeCounts = new TreeMap<>();
        try (Stream<String> fixtures = fixtureNames()) {
            for (String fixture : fixtures.toList()) {
                byte @Unmodifiable [] content = LibarchiveUuDecoder.decode(fixturePath(fixture));
                String outcome = probeFixture(content);
                outcomeCounts.merge(outcome, 1, Math::addExact);
                digest.update(fixture.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\t');
                digest.update(outcome.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
        }
        String actualDigest = HexFormat.of().formatHex(digest.digest());
        assertEquals(
                EXPECTED_OUTCOME_DIGEST,
                actualDigest,
                () -> "Complete libarchive fixture outcome counts: " + outcomeCounts
        );
    }

    /// Decodes every fixture and exercises every archive or compression stream recognized by Arkivo.
    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtureNames")
    @Timeout(10)
    void exercisesEveryRecognizedFixture(String fixture) throws IOException {
        byte @Unmodifiable [] content = LibarchiveUuDecoder.decode(fixturePath(fixture));
        probeFixture(content);
    }

    /// Returns the stable detection and complete-read outcome for one decoded fixture.
    private static String probeFixture(byte @Unmodifiable [] content) {
        @Nullable ArkivoFormat archiveFormat = ArkivoFormats.detect(ByteBuffer.wrap(content).asReadOnlyBuffer());
        if (archiveFormat != null) {
            return exerciseArchive(archiveFormat, content);
        }

        @Nullable CompressionFormat compressionFormat = CompressionFormats.detect(
                ByteBuffer.wrap(content).asReadOnlyBuffer()
        );
        if (compressionFormat != null) {
            return exerciseCompressedFixture(compressionFormat, content);
        }
        return "unrecognized";
    }

    /// Exercises advertised streaming and random-access readers and returns both dispositions.
    private static String exerciseArchive(ArkivoFormat format, byte @Unmodifiable [] content) {
        ProbeOutcome streamingOutcome = ProbeOutcome.UNAVAILABLE;
        if (format instanceof ArkivoFormat.StreamingReadable streamingFormat) {
            try (ArkivoStreamingReader reader = streamingFormat.openStreamingReader(
                    new ByteArrayInputStream(content),
                    READ_OPTIONS
            )) {
                while (reader.next()) {
                    BasicFileAttributes attributes = reader.readAttributes(BasicFileAttributes.class);
                    if (attributes.isRegularFile()) {
                        try (InputStream input = reader.openInputStream()) {
                            drain(input);
                        }
                    }
                }
                streamingOutcome = ProbeOutcome.SUCCESS;
            } catch (IOException expectedRejection) {
                streamingOutcome = ProbeOutcome.REJECTED;
            } catch (UnsupportedOperationException expectedUnsupportedFeature) {
                streamingOutcome = ProbeOutcome.UNSUPPORTED;
            }
        }

        ProbeOutcome fileSystemOutcome = ProbeOutcome.UNAVAILABLE;
        if (format instanceof ArkivoFormat.FileSystem fileSystemFormat) {
            try (ArkivoFileSystem fileSystem = fileSystemFormat.open(
                    new SeekableInMemoryByteChannel(content),
                    READ_OPTIONS
            ); Stream<Path> paths = Files.walk(fileSystem.getPath("/"))) {
                for (Path path : paths.toList()) {
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        try (InputStream input = Files.newInputStream(path)) {
                            drain(input);
                        }
                    }
                }
                fileSystemOutcome = ProbeOutcome.SUCCESS;
            } catch (IOException expectedRejection) {
                fileSystemOutcome = ProbeOutcome.REJECTED;
            } catch (UnsupportedOperationException expectedUnsupportedFeature) {
                fileSystemOutcome = ProbeOutcome.UNSUPPORTED;
            }
        }
        return "archive:" + format.name()
                + ":streaming=" + streamingOutcome.token
                + ":fileSystem=" + fileSystemOutcome.token;
    }

    /// Exercises one detected compressed stream and any archive produced within the bounded output limit.
    private static String exerciseCompressedFixture(
            CompressionFormat format,
            byte @Unmodifiable [] content
    ) {
        CompressionCodec<?> codec = format.defaultCodec()
                .withMaximumOutputSize(MAXIMUM_DECODED_SIZE)
                .withMaximumWindowSize(MAXIMUM_DECODED_SIZE)
                .withMaximumMemorySize(128L * 1024L * 1024L);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            codec.decompress(
                    java.nio.channels.Channels.newChannel(new ByteArrayInputStream(content)),
                    java.nio.channels.Channels.newChannel(output)
            );
            byte @Unmodifiable [] decoded = output.toByteArray();
            @Nullable ArkivoFormat archiveFormat = ArkivoFormats.detect(ByteBuffer.wrap(decoded).asReadOnlyBuffer());
            if (archiveFormat != null) {
                return "compression:" + format.name() + ":success:nested="
                        + exerciseArchive(archiveFormat, decoded);
            }
            return "compression:" + format.name() + ":success:nested=none";
        } catch (IOException expectedRejection) {
            return "compression:" + format.name() + ":rejected";
        } catch (UnsupportedOperationException expectedUnsupportedFeature) {
            return "compression:" + format.name() + ":unsupported";
        }
    }

    /// Stable classification of one advertised archive access path.
    @NotNullByDefault
    private enum ProbeOutcome {
        /// The format does not advertise this access path.
        UNAVAILABLE("unavailable"),

        /// The fixture was read completely through this access path.
        SUCCESS("success"),

        /// The parser rejected the fixture with a checked I/O failure.
        REJECTED("rejected"),

        /// The fixture requires a recognized but unsupported optional feature.
        UNSUPPORTED("unsupported");

        /// Stable lowercase token included in the aggregate digest.
        private final String token;

        /// Creates one outcome with its stable digest token.
        ProbeOutcome(String token) {
            this.token = token;
        }
    }

    /// Consumes an entry body without retaining it in memory.
    private static void drain(InputStream input) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        while (true) {
            int read = input.read(buffer);
            if (read < 0) {
                return;
            }
            if (read == 0) {
                throw new IOException("Archive entry stream made no progress");
            }
        }
    }

    /// Returns sorted fixture names from the prepared complete corpus.
    private static Stream<String> fixtureNames() throws IOException {
        Path root = fixtureRoot();
        return Files.list(root)
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .map(path -> path.getFileName().toString())
                .filter(name -> name.endsWith(".uu"))
                .sorted();
    }

    /// Resolves one fixture below the prepared corpus directory.
    private static Path fixturePath(String fixture) {
        return fixtureRoot().resolve(fixture);
    }

    /// Returns the prepared fixture directory.
    private static Path fixtureRoot() {
        String directory = System.getProperty(TEST_DATA_DIRECTORY_PROPERTY);
        if (directory == null || directory.isBlank()) {
            throw new IllegalStateException("Missing system property: " + TEST_DATA_DIRECTORY_PROPERTY);
        }
        return Path.of(directory).resolve("fixtures");
    }
}
