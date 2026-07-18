// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bzip2;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies Arkivo against the official bzip2 1.0.8 reference samples.
@NotNullByDefault
public final class BZip2OfficialCorpusTest {
    /// The system property containing the extracted official corpus directory.
    private static final String TEST_DATA_DIRECTORY_PROPERTY = "arkivo.bzip2.testDataDirectory";

    /// Verifies every official compressed sample exactly matches its reference output.
    @ParameterizedTest(name = "{0}")
    @MethodSource("sampleNames")
    public void decompressesOfficialReferenceSample(String sampleName) throws IOException {
        byte @Unmodifiable [] expected = Files.readAllBytes(corpusPath(sampleName + ".ref"));
        byte @Unmodifiable [] compressed = Files.readAllBytes(corpusPath(sampleName + ".bz2"));

        assertArrayEquals(expected, decompress(compressed), sampleName);
    }

    /// Verifies official reference inputs round-trip at the minimum and maximum BZip2 block-size levels.
    @ParameterizedTest(name = "{0}")
    @MethodSource("sampleNames")
    public void roundTripsOfficialReferenceSample(String sampleName) throws IOException {
        byte @Unmodifiable [] input = Files.readAllBytes(corpusPath(sampleName + ".ref"));
        for (long level : new long[]{1L, 9L}) {
            BZip2Codec codec = new BZip2Codec().withCompressionLevel(level);
            assertArrayEquals(input, roundTrip(input, codec), sampleName + " level " + level);
        }
    }

    /// Verifies the decoder consumes concatenated official streams in order.
    @Test
    public void decompressesConcatenatedOfficialStreams() throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        for (String sampleName : sampleNames().toList()) {
            compressed.write(Files.readAllBytes(corpusPath(sampleName + ".bz2")));
            expected.write(Files.readAllBytes(corpusPath(sampleName + ".ref")));
        }

        assertArrayEquals(expected.toByteArray(), decompress(compressed.toByteArray()));
    }

    /// Verifies extraction retains the upstream license, README, and exact download lock manifest.
    @Test
    public void retainsUpstreamProvenance() {
        assertTrue(Files.isRegularFile(corpusPath("LICENSE")));
        assertTrue(Files.isRegularFile(corpusPath("README")));
        assertTrue(Files.isRegularFile(corpusPath("UPSTREAM.properties")));
    }

    /// Returns official bzip2 reference sample stems.
    private static Stream<String> sampleNames() {
        return Stream.of("sample1", "sample2", "sample3");
    }

    /// Compresses and decompresses bytes through independently configured channel operations.
    private static byte @Unmodifiable [] roundTrip(
            byte @Unmodifiable [] input,
            BZip2Codec codec
    ) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        codec.compress(
                Channels.newChannel(new ByteArrayInputStream(input)),
                Channels.newChannel(compressed)
        );
        return decompress(compressed.toByteArray());
    }

    /// Decompresses one complete BZip2 byte sequence through the Arkivo channel API.
    private static byte @Unmodifiable [] decompress(byte @Unmodifiable [] compressed) throws IOException {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        new BZip2Codec().decompress(
                Channels.newChannel(new ByteArrayInputStream(compressed)),
                Channels.newChannel(decoded)
        );
        return decoded.toByteArray();
    }

    /// Returns a path below the configured extracted corpus directory.
    private static Path corpusPath(String relativePath) {
        @Nullable String configured = System.getProperty(TEST_DATA_DIRECTORY_PROPERTY);
        if (configured == null) {
            throw new IllegalStateException("Missing system property: " + TEST_DATA_DIRECTORY_PROPERTY);
        }
        return Path.of(configured).resolve(relativePath);
    }
}
