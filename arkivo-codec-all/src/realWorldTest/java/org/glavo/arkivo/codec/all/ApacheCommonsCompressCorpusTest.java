// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.all;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.deflate.DeflateCodec;
import org.glavo.arkivo.codec.deflate64.Deflate64Codec;
import org.glavo.arkivo.codec.gzip.GzipCodec;
import org.glavo.arkivo.codec.zlib.ZlibCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies Deflate-family codecs against pinned Apache Commons Compress 1.28.0 fixtures.
@NotNullByDefault
public final class ApacheCommonsCompressCorpusTest {
    /// The system property containing the prepared Apache Commons Compress corpus directory.
    private static final String TEST_DATA_DIRECTORY_PROPERTY = "arkivo.commonsCompress.testDataDirectory";

    /// The ZIP compression method number assigned to Deflate64.
    private static final int DEFLATE64_METHOD = 9;

    /// Verifies raw Deflate and zlib streams independently decode to the same upstream TAR bytes.
    @Test
    public void decompressesRawDeflateAndZlibFixtures() throws IOException {
        byte[] expected = Files.readAllBytes(resource("bla.tar"));

        assertArrayEquals(expected, decompress(new DeflateCodec(), resource("bla.tar.deflate")));
        assertArrayEquals(expected, decompress(new ZlibCodec(), resource("bla.tar.deflatez")));
    }

    /// Verifies concatenated members produced by gzip 1.13 are all decoded in order.
    @Test
    public void decompressesGzipCliConcatenatedMembers() throws IOException {
        assertArrayEquals(
                "Hello1\nHello2\n".getBytes(StandardCharsets.ISO_8859_1),
                decompress(new GzipCodec(), resource("org/apache/commons/compress/gzip/members.gz"))
        );
    }

    /// Verifies concatenated zero-length members produced by gzip 1.13 decode successfully.
    @Test
    public void decompressesGzipCliEmptyMembers() throws IOException {
        assertArrayEquals(
                new byte[0],
                decompress(new GzipCodec(), resource("org/apache/commons/compress/gzip/members-size-0.gz"))
        );
    }

    /// Verifies the original COMPRESS-380 Deflate64 regression against its exact upstream input.
    @Test
    public void decompressesDeflate64KnownOutput() throws IOException {
        Path archive = resource("COMPRESS-380/COMPRESS-380.zip");
        byte[] expected = Files.readAllBytes(resource("COMPRESS-380/COMPRESS-380-input"));

        assertArrayEquals(expected, decompressDeflate64Entry(archive, "input2"));
    }

    /// Verifies a Deflate64 entry whose ZIP local header uses a data descriptor.
    @Test
    public void decompressesDeflate64DataDescriptorEntry() throws IOException {
        byte[] expected = "Manifest-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

        assertArrayEquals(
                expected,
                decompressDeflate64Entry(
                        resource("COMPRESS-380/COMPRESS-380-dd.zip"),
                        "META-INF/MANIFEST.MF"
                )
        );
    }

    /// Verifies the read-beyond-memory Deflate64 regression against an independent Commons Compress decode.
    @Test
    public void matchesIndependentDeflate64RegressionDecode() throws IOException {
        Path archive = resource("COMPRESS-380/COMPRESS-380-readbeyondmemory.zip");
        try (ZipFile zipFile = ZipFile.builder().setPath(archive).get()) {
            ZipArchiveEntry entry = requireEntry(zipFile, "public.png");
            byte[] expected;
            try (InputStream input = zipFile.getInputStream(entry)) {
                expected = input.readAllBytes();
            }

            assertEquals(17_051, expected.length);
            assertArrayEquals(expected, decompressRawEntry(zipFile, entry));
        }
    }

    /// Verifies preparation retains the upstream license, notice, and exact download lock manifest.
    @Test
    public void retainsUpstreamProvenance() {
        assertTrue(Files.isRegularFile(corpusPath("LICENSE.txt")));
        assertTrue(Files.isRegularFile(corpusPath("NOTICE.txt")));
        assertTrue(Files.isRegularFile(corpusPath("UPSTREAM.properties")));
    }

    /// Decompresses one named Deflate64 ZIP entry through Arkivo without using Commons Compress's decoder.
    private static byte[] decompressDeflate64Entry(Path archive, String entryName) throws IOException {
        try (ZipFile zipFile = ZipFile.builder().setPath(archive).get()) {
            return decompressRawEntry(zipFile, requireEntry(zipFile, entryName));
        }
    }

    /// Decompresses the raw compressed bytes of one Deflate64 entry through Arkivo.
    private static byte[] decompressRawEntry(ZipFile zipFile, ZipArchiveEntry entry) throws IOException {
        assertEquals(DEFLATE64_METHOD, entry.getMethod(), entry.getName());
        try (InputStream raw = Objects.requireNonNull(
                zipFile.getRawInputStream(entry),
                "Missing raw ZIP input for " + entry.getName()
        )) {
            return decompress(new Deflate64Codec(), raw);
        }
    }

    /// Returns one required ZIP entry.
    private static ZipArchiveEntry requireEntry(ZipFile zipFile, String entryName) {
        return Objects.requireNonNull(zipFile.getEntry(entryName), "Missing ZIP entry: " + entryName);
    }

    /// Decompresses one fixture path through the channel-first codec API.
    private static byte[] decompress(CompressionCodec codec, Path compressed) throws IOException {
        try (InputStream input = Files.newInputStream(compressed)) {
            return decompress(codec, input);
        }
    }

    /// Decompresses one stream through the channel-first codec API.
    private static byte[] decompress(CompressionCodec codec, InputStream compressed) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        codec.decompress(
                Channels.newChannel(compressed),
                Channels.newChannel(output)
        );
        return output.toByteArray();
    }

    /// Returns an upstream test resource below the configured corpus directory.
    private static Path resource(String relativePath) {
        return corpusPath("src/test/resources/" + relativePath);
    }

    /// Returns a path below the configured corpus directory.
    private static Path corpusPath(String relativePath) {
        @Nullable String configured = System.getProperty(TEST_DATA_DIRECTORY_PROPERTY);
        if (configured == null) {
            throw new IllegalStateException("Missing system property: " + TEST_DATA_DIRECTORY_PROPERTY);
        }
        return Path.of(configured).resolve(relativePath);
    }
}
