// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all.commonscompress;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.compressors.lz4.BlockLZ4CompressorInputStream;
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.DecodingOptions;
import org.glavo.arkivo.codec.DecompressionOutputLimitException;
import org.glavo.arkivo.codec.DecompressionWindowLimitException;
import org.glavo.arkivo.codec.bzip2.BZip2Codec;
import org.glavo.arkivo.codec.compress.UnixCompressCodec;
import org.glavo.arkivo.codec.deflate.Deflate64Codec;
import org.glavo.arkivo.codec.deflate.DeflateCodec;
import org.glavo.arkivo.codec.deflate.GzipCodec;
import org.glavo.arkivo.codec.deflate.ZlibCodec;
import org.glavo.arkivo.codec.lzma.LZMACodec;
import org.glavo.arkivo.codec.lz4.LZ4BlockCodec;
import org.glavo.arkivo.codec.lz4.LZ4BlockSize;
import org.glavo.arkivo.codec.lz4.LZ4Codec;
import org.glavo.arkivo.codec.xz.XZCodec;
import org.glavo.arkivo.codec.zstd.ZstdCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies Arkivo codecs against every applicable codec fixture in Apache Commons Compress 1.28.0.
@NotNullByDefault
final class CommonsCompressCodecCorpusTest {
    /// The SHA-256 digest of the canonical `bla.tar` payload.
    private static final String BLA_TAR_SHA256 =
            "8e081a961b6a97f68e19cf6e26f3b85e04ffef27dbe9c4ad664d79a7ced06a38";

    /// The SHA-256 digest of the two-byte `ab` payload used by concatenated-stream fixtures.
    private static final String AB_SHA256 =
            "fb8e20fc2e4c3f248c60c39bd652f3c1347298bb977b8b4d5903b85055620603";

    /// The ZIP method identifier assigned to Deflate64.
    private static final int DEFLATE64_METHOD = 9;

    /// The source-channel chunk size used to force compressed-input fragmentation.
    private static final int SMALL_SOURCE_CHUNK_SIZE = 2;

    /// The direct target-buffer size used to force decoded-output fragmentation.
    private static final int SMALL_TARGET_BUFFER_SIZE = 3;

    /// The bounded output used to exercise expansion fixtures without materializing their full contents.
    private static final long EXPANSION_FIXTURE_OUTPUT_LIMIT = 1024L * 1024L;

    /// Verifies every canonical stream through both transfer and fragmented channel paths.
    @ParameterizedTest(name = "{0}")
    @MethodSource("canonicalFixtures")
    void decodesCanonicalFixtureThroughBothChannelPaths(CodecFixture fixture) throws IOException {
        byte @Unmodifiable [] compressed = fixture.compressed();
        byte @Unmodifiable [] complete = decompressComplete(fixture.codec(), compressed);
        fixture.verify(complete);

        byte @Unmodifiable [] fragmented = decompressFragmented(fixture.codec(), compressed);
        fixture.verify(fragmented);
        assertArrayEquals(complete, fragmented, fixture.name());
    }

    /// Verifies stream adapters expose stable EOF and the zero-length read contract after complete decoding.
    @ParameterizedTest(name = "{0}")
    @MethodSource("canonicalFixtures")
    void exposesStableEndOfStreamForCanonicalFixture(CodecFixture fixture) throws IOException {
        try (InputStream input = fixture.codec().newInputStream(new ByteArrayInputStream(fixture.compressed()))) {
            byte @Unmodifiable [] decoded = readInSmallChunks(input);
            fixture.verify(decoded);
            assertEquals(-1, input.read(), fixture.name());
            assertEquals(-1, input.read(new byte[4]), fixture.name());
            assertEquals(0, input.read(new byte[0]), fixture.name());
        }
    }

    /// Verifies the large BZip2 regression without imposing tiny-buffer overhead on its complete payload.
    @ParameterizedTest(name = "{0}")
    @MethodSource("largeFixtures")
    void decodesLargeFixture(CodecFixture fixture) throws IOException {
        fixture.verify(decompressComplete(fixture.codec(), fixture.compressed()));
    }

    /// Verifies expansion-heavy upstream fixtures stop at Arkivo's configured output limit.
    @ParameterizedTest(name = "{0}")
    @MethodSource("expansionFixtures")
    void enforcesOutputLimitOnExpansionFixture(
            String name,
            CompressionCodec<?> codec,
            String resource
    ) throws IOException {
        byte @Unmodifiable [] compressed = CommonsCompressTestResources.read(resource);
        assertThrows(
                DecompressionOutputLimitException.class,
                () -> decompressWithOutputLimit(codec, compressed),
                name
        );
    }

    /// Verifies COMPRESS-382 rejects its oversized LZMA dictionary before attempting allocation.
    @Test
    void enforcesMemoryLimitOnCompress382LzmaDictionary() throws IOException {
        long maximumMemorySize = 100L * 1024L;
        DecompressionWindowLimitException exception = assertThrows(
                DecompressionWindowLimitException.class,
                () -> new LZMACodec().decompress(
                        Channels.newChannel(new ByteArrayInputStream(
                                CommonsCompressTestResources.read("COMPRESS-382")
                        )),
                        Channels.newChannel(new ByteArrayOutputStream()),
                        DecodingOptions.ofMaximumMemorySize(maximumMemorySize)
                )
        );
        assertEquals(maximumMemorySize, exception.maximumWindowSize());
        assertEquals(0x7400_0000L, exception.requiredWindowSize());
    }

    /// Verifies all three payloads bundled inside the upstream Zstandard TAR resource remain available.
    @Test
    void readsCompleteZstandardBundle() throws IOException {
        byte @Unmodifiable [] canonicalTar = CommonsCompressTestResources.read("bla.tar");
        byte @Unmodifiable [] compressedTar =
                CommonsCompressTestResources.readTarEntry("zstd-tests.tar", "bla.tar.zst");
        byte @Unmodifiable [] canonicalText =
                CommonsCompressTestResources.readTarEntry("zstd-tests.tar", "zstandard.testdata");
        byte @Unmodifiable [] compressedText = CommonsCompressTestResources.readTarEntry(
                "zstd-tests.tar",
                "zstandard.testdata.zst"
        );

        assertArrayEquals(canonicalTar, decompressComplete(new ZstdCodec(), compressedTar));
        assertArrayEquals(canonicalText, decompressComplete(new ZstdCodec(), compressedText));
    }

    /// Verifies Commons Compress decodes Arkivo-produced independent, linked, and raw LZ4 blocks.
    @Test
    void commonsCompressDecodesArkivoLz4Output() throws IOException {
        byte @Unmodifiable [] expected = CommonsCompressTestResources.read("bla.dump");
        for (LZ4Codec codec : List.of(
                LZ4Codec.builder()
                        .blockSize(LZ4BlockSize.KIB_64)
                        .blockChecksum(true)
                        .contentChecksum(true)
                        .build(),
                LZ4Codec.builder()
                        .blockSize(LZ4BlockSize.KIB_64)
                        .independentBlocks(false)
                        .blockChecksum(true)
                        .contentChecksum(true)
                        .build()
        )) {
            byte @Unmodifiable [] compressed = compressComplete(codec, expected);
            try (InputStream decoder = new FramedLZ4CompressorInputStream(
                    new ByteArrayInputStream(compressed)
            )) {
                assertArrayEquals(expected, decoder.readAllBytes());
            }
        }

        byte @Unmodifiable [] rawCompressed = compressComplete(new LZ4BlockCodec(), expected);
        try (InputStream decoder = new BlockLZ4CompressorInputStream(
                new ByteArrayInputStream(rawCompressed)
        )) {
            assertArrayEquals(expected, decoder.readAllBytes());
        }
    }

    /// Verifies independently supplied upstream Zstandard frames decode when concatenated.
    @Test
    void decodesConcatenatedZstandardFrames() throws IOException {
        byte @Unmodifiable [] firstCompressed =
                CommonsCompressTestResources.readTarEntry("zstd-tests.tar", "bla.tar.zst");
        byte @Unmodifiable [] secondCompressed = CommonsCompressTestResources.readTarEntry(
                "zstd-tests.tar",
                "zstandard.testdata.zst"
        );
        byte @Unmodifiable [] firstExpected = CommonsCompressTestResources.read("bla.tar");
        byte @Unmodifiable [] secondExpected = CommonsCompressTestResources.readTarEntry(
                "zstd-tests.tar",
                "zstandard.testdata"
        );
        byte @Unmodifiable [] compressed = concatenate(firstCompressed, secondCompressed);
        byte @Unmodifiable [] expected = concatenate(firstExpected, secondExpected);

        assertArrayEquals(expected, decompressComplete(new ZstdCodec(), compressed));
        assertArrayEquals(expected, decompressFragmented(new ZstdCodec(), compressed));
    }

    /// Verifies every upstream Deflate64 regression through transfer and fragmented channel paths.
    @ParameterizedTest(name = "{0}")
    @MethodSource("deflate64Fixtures")
    void decodesDeflate64Regression(Deflate64Fixture fixture) throws IOException {
        byte @Unmodifiable [] complete = decompressComplete(new Deflate64Codec(), fixture.compressed());
        fixture.verify(complete);

        byte @Unmodifiable [] fragmented = decompressFragmented(new Deflate64Codec(), fixture.compressed());
        fixture.verify(fragmented);
        assertArrayEquals(complete, fragmented, fixture.name());
    }

    /// Verifies representative malformed streams fail with checked I/O errors on both decoding paths.
    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedFixtures")
    void rejectsMalformedFixture(
            String name,
            CompressionCodec<?> codec,
            byte @Unmodifiable [] malformed
    ) {
        assertThrows(IOException.class, () -> decompressComplete(codec, malformed), name);
        assertThrows(IOException.class, () -> decompressFragmented(codec, malformed), name);
    }

    /// Verifies a non-BZip2 corpus file is rejected as BZip2 rather than producing arbitrary output.
    @Test
    void rejectsWrongFormatAsBZip2() throws IOException {
        byte @Unmodifiable [] zip = CommonsCompressTestResources.read("bla.zip");
        assertThrows(IOException.class, () -> decompressComplete(new BZip2Codec(), zip));
        assertThrows(IOException.class, () -> decompressFragmented(new BZip2Codec(), zip));
    }

    /// Supplies canonical codec fixtures paired with independently fixed output sizes and SHA-256 digests.
    private static Stream<CodecFixture> canonicalFixtures() throws IOException {
        byte @Unmodifiable [] zstdTar =
                CommonsCompressTestResources.readTarEntry("zstd-tests.tar", "bla.tar.zst");
        byte @Unmodifiable [] zstdText =
                CommonsCompressTestResources.readTarEntry("zstd-tests.tar", "zstandard.testdata.zst");
        return Stream.of(
                fixture("raw-deflate/bla.tar.deflate", new DeflateCodec(), "bla.tar.deflate", 10_240, BLA_TAR_SHA256),
                fixture("zlib/bla.tar.deflatez", new ZlibCodec(), "bla.tar.deflatez", 10_240, BLA_TAR_SHA256),
                fixture("gzip/bla.tgz", new GzipCodec(), "bla.tgz", 10_240, BLA_TAR_SHA256),
                fixture(
                        "gzip/concatenated-cli-members",
                        new GzipCodec(),
                        "org/apache/commons/compress/gzip/members.gz",
                        14,
                        "354a2c81e652af74a4d359dbb39e394ae885d81973236f21964603af45bb77ae"
                ),
                fixture(
                        "gzip/concatenated-empty-members",
                        new GzipCodec(),
                        "org/apache/commons/compress/gzip/members-size-0.gz",
                        0,
                        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
                ),
                fixture("gzip/multiple.gz", new GzipCodec(), "multiple.gz", 2, AB_SHA256),
                fixture(
                        "gzip/lorem-ipsum.txt.gz",
                        new GzipCodec(),
                        "lorem-ipsum.txt.gz",
                        144_060,
                        "a00c4f3f36515c96b2faef71c054e7f3e86a4f0f4ed4824cb7c5293bb455d28a"
                ),
                fixture(
                        "gzip/COMPRESS-245.tar.gz",
                        new GzipCodec(),
                        "COMPRESS-245.tar.gz",
                        624_640,
                        "7af6b2eccf3461fdf4d75773cfdee0dfdec9262653a6d5262717296ec38b0862"
                ),
                fixture(
                        "gzip/COMPRESS-666/compress-666.tar.gz",
                        new GzipCodec(),
                        "COMPRESS-666/compress-666.tar.gz",
                        12_800,
                        "5f33c2458e7f521345b27b555167690d5eae2d064d30fe8a181af992a9db348a"
                ),
                fixture(
                        "gzip/pack200/annotations.pack.gz",
                        new GzipCodec(),
                        "pack200/annotations.pack.gz",
                        1_237,
                        "dfb42e6e0a056f9c67bf29a789ec17bec765611c6667ac4d23a4779565dd17e2"
                ),
                fixture(
                        "gzip/pack200/annotationsRI.pack.gz",
                        new GzipCodec(),
                        "pack200/annotationsRI.pack.gz",
                        1_434,
                        "80ed4558b86732d7ceeee1ca2abbdd1c527f778a99d34f50b56f0b25399e4090"
                ),
                fixture(
                        "gzip/pack200/jndi-e1.pack.gz",
                        new GzipCodec(),
                        "pack200/jndi-e1.pack.gz",
                        153_104,
                        "4b45056331e5014859191698ee8c364e6d0a154f468bd49ded38f6c0fc726b79"
                ),
                fixture(
                        "gzip/pack200/JustResources.pack.gz",
                        new GzipCodec(),
                        "pack200/JustResources.pack.gz",
                        51,
                        "38b8e51db4bd257484c07be4f2db7d57caecd7aa035955a22cc5428bc54dc074"
                ),
                fixture(
                        "gzip/pack200/LargeClass.pack.gz",
                        new GzipCodec(),
                        "pack200/LargeClass.pack.gz",
                        7_908,
                        "0d281fb5ff208037d2dd28a708db9567bffd9ebf9669ea8cf08d2aea242e2985"
                ),
                fixture(
                        "gzip/pack200/pack200-e1.pack.gz",
                        new GzipCodec(),
                        "pack200/pack200-e1.pack.gz",
                        37_103,
                        "e0b616c6d3f0ac1d54d94bd9028363060dfe17d13adf9e3aa7ca057db2be9701"
                ),
                fixture(
                        "gzip/pack200/pack200.pack.gz",
                        new GzipCodec(),
                        "pack200/pack200.pack.gz",
                        24_489,
                        "dc626efaebe72ab885a1f59d560bdeca0bce8d940ee16e13c982e11acf9ed83e"
                ),
                fixture(
                        "gzip/pack200/simple-E0.pack.gz",
                        new GzipCodec(),
                        "pack200/simple-E0.pack.gz",
                        1_004,
                        "28327ec3c61c31439aebcea8c9c88d93301c118cd3f478f236d61bc723fba78d"
                ),
                fixture(
                        "gzip/pack200/sql-e1.pack.gz",
                        new GzipCodec(),
                        "pack200/sql-e1.pack.gz",
                        132_952,
                        "3e5ddd3031aeba0667ca5c0788237e11465e7682770ac4516c4c9be59c020a03"
                ),
                fixture(
                        "gzip/pack200/sql.pack.gz",
                        new GzipCodec(),
                        "pack200/sql.pack.gz",
                        126_305,
                        "1f68bf3f50e599d8a16a411aa31d9ac4db44a24634d05c12db803e0b6069a0b3"
                ),
                fixture("bzip2/bla.tar.bz2", new BZip2Codec(), "bla.tar.bz2", 10_240, BLA_TAR_SHA256),
                fixture(
                        "bzip2/bla.txt.bz2",
                        new BZip2Codec(),
                        "bla.txt.bz2",
                        26,
                        "dfbf546fd25eb26a6f9103850dcde5f2a6d635429d6a7d9cb1f0d599aaf2db74"
                ),
                fixture(
                        "bzip2/bla.xml.bz2",
                        new BZip2Codec(),
                        "bla.xml.bz2",
                        610,
                        "506c377ae6116a19e020954828e03175d91a67c49e83e2fdd14b7ba65f64b19d"
                ),
                fixture(
                        "bzip2/COMPRESS-131.bz2",
                        new BZip2Codec(),
                        "COMPRESS-131.bz2",
                        539,
                        "e7b2e9876fb31d172dc26d3fa373b80f93e8e93019070b65d2ca06f09616d207"
                ),
                fixture("bzip2/multiple.bz2", new BZip2Codec(), "multiple.bz2", 2, AB_SHA256),
                fixture(
                        "bzip2/lbzip2_32767.bz2",
                        new BZip2Codec(),
                        "lbzip2_32767.bz2",
                        5,
                        "13b896d551a100401b0d3982e0729efc2e8d7aeb09a36c0a51e48ec2bd15ea8b"
                ),
                fixture(
                        "compress/bla.tar.Z",
                        new UnixCompressCodec(),
                        "bla.tar.Z",
                        10_240,
                        BLA_TAR_SHA256
                ),
                fixture("lzma/bla.tar.lzma", new LZMACodec(), "bla.tar.lzma", 10_240, BLA_TAR_SHA256),
                fixture("lz4/bla.tar.lz4", new LZ4Codec(), "bla.tar.lz4", 10_240, BLA_TAR_SHA256),
                fixture(
                        "lz4/bla.dump.lz4",
                        new LZ4Codec(),
                        "bla.dump.lz4",
                        92_160,
                        "996ddd41f26eb211736b8f8dc06d905d697f05ce8b963792cc65a86e07bd0e6d"
                ),
                fixture(
                        "lz4-block/bla.tar.block_lz4",
                        new LZ4BlockCodec(),
                        "bla.tar.block_lz4",
                        10_240,
                        BLA_TAR_SHA256
                ),
                fixture("xz/bla.tar.xz", new XZCodec(), "bla.tar.xz", 10_240, BLA_TAR_SHA256),
                fixture("xz/multiple.xz", new XZCodec(), "multiple.xz", 2, AB_SHA256),
                new CodecFixture("zstd/bla.tar.zst", new ZstdCodec(), zstdTar, 10_240, BLA_TAR_SHA256),
                new CodecFixture(
                        "zstd/zstandard.testdata.zst",
                        new ZstdCodec(),
                        zstdText,
                        97,
                        "53fc680b53e12be2d7642d509b51ef427a3c1715896a3db0ece848620b267bc1"
                )
        );
    }

    /// Supplies large fixtures whose decoded payloads are verified through the complete transfer path.
    private static Stream<CodecFixture> largeFixtures() throws IOException {
        return Stream.of(fixture(
                "bzip2/COMPRESS-651/my10m.tar.bz2",
                new BZip2Codec(),
                "org/apache/commons/compress/COMPRESS-651/my10m.tar.bz2",
                10_496_000,
                "4fd268d8534932fd793e274c201d509fc28162b9cdeeb2f649dd03e3bb011df8"
        ));
    }

    /// Supplies expansion-heavy codec fixtures that are unsafe to materialize fully in routine Tier 2 tests.
    private static Stream<Arguments> expansionFixtures() {
        return Stream.of(
                Arguments.of("gzip/8.posix.tar.gz", new GzipCodec(), "8.posix.tar.gz"),
                Arguments.of("gzip/8.star.tar.gz", new GzipCodec(), "8.star.tar.gz"),
                Arguments.of("bzip2/zip64support.tar.bz2", new BZip2Codec(), "zip64support.tar.bz2")
        );
    }

    /// Supplies the original COMPRESS-380 Deflate64 regression entries.
    private static Stream<Deflate64Fixture> deflate64Fixtures() throws IOException {
        return Stream.of(
                deflate64Fixture("COMPRESS-380/COMPRESS-380.zip", "input2"),
                deflate64Fixture("COMPRESS-380/COMPRESS-380-dd.zip", "META-INF/MANIFEST.MF"),
                deflate64Fixture("COMPRESS-380/COMPRESS-380-readbeyondmemory.zip", "public.png")
        );
    }

    /// Supplies representative corruptions for every container codec family covered by the corpus.
    private static Stream<Arguments> malformedFixtures() throws IOException {
        Deflate64Fixture deflate64 = deflate64Fixture("COMPRESS-380/COMPRESS-380.zip", "input2");
        return Stream.of(
                malformed("zlib/checksum", new ZlibCodec(), CommonsCompressTestResources.read("bla.tar.deflatez"), false),
                malformed("gzip/truncated", new GzipCodec(), CommonsCompressTestResources.read("bla.tgz"), true),
                malformed("bzip2/truncated", new BZip2Codec(), CommonsCompressTestResources.read("bla.txt.bz2"), true),
                malformed("lzma/truncated", new LZMACodec(), CommonsCompressTestResources.read("bla.tar.lzma"), true),
                malformed("xz/truncated", new XZCodec(), CommonsCompressTestResources.read("bla.tar.xz"), true),
                malformed("lz4/truncated", new LZ4Codec(), CommonsCompressTestResources.read("bla.tar.lz4"), true),
                Arguments.of(
                        "lz4/invalid-zero-offset",
                        new LZ4Codec(),
                        CommonsCompressTestResources.read("COMPRESS-490/ArithmeticException.lz4")
                ),
                Arguments.of(
                        "lz4/invalid-back-reference-at-start",
                        new LZ4Codec(),
                        CommonsCompressTestResources.read("COMPRESS-490/ArrayIndexOutOfBoundsException1.lz4")
                ),
                Arguments.of(
                        "lz4/invalid-offset-too-large",
                        new LZ4Codec(),
                        CommonsCompressTestResources.read("COMPRESS-490/ArrayIndexOutOfBoundsException2.lz4")
                ),
                malformed(
                        "zstd/truncated",
                        new ZstdCodec(),
                        CommonsCompressTestResources.readTarEntry("zstd-tests.tar", "zstandard.testdata.zst"),
                        true
                ),
                malformed("deflate64/truncated", new Deflate64Codec(), deflate64.compressed(), true)
        );
    }

    /// Creates one canonical fixture from a corpus resource.
    private static CodecFixture fixture(
            String name,
            CompressionCodec<?> codec,
            String resource,
            int expectedSize,
            String expectedSha256
    ) throws IOException {
        return new CodecFixture(
                name,
                codec,
                CommonsCompressTestResources.read(resource),
                expectedSize,
                expectedSha256
        );
    }

    /// Creates one malformed-fixture argument by truncating or corrupting the final byte.
    private static Arguments malformed(
            String name,
            CompressionCodec<?> codec,
            byte @Unmodifiable [] valid,
            boolean truncate
    ) {
        assertTrue(valid.length > 1, name);
        byte[] malformed = truncate ? Arrays.copyOf(valid, valid.length - 1) : valid.clone();
        if (!truncate) {
            malformed[malformed.length - 1] ^= 1;
        }
        return Arguments.of(name, codec, malformed);
    }

    /// Reads one raw Deflate64 ZIP entry and its independently stored size and CRC metadata.
    private static Deflate64Fixture deflate64Fixture(String archiveResource, String entryName) throws IOException {
        try (ZipFile zipFile = ZipFile.builder()
                .setPath(CommonsCompressTestResources.resource(archiveResource))
                .get()) {
            ZipArchiveEntry entry = Objects.requireNonNull(
                    zipFile.getEntry(entryName),
                    "Missing ZIP entry: " + entryName
            );
            if (entry.getMethod() != DEFLATE64_METHOD) {
                throw new IOException("Expected Deflate64 ZIP entry: " + archiveResource + "!" + entryName);
            }
            try (InputStream raw = Objects.requireNonNull(
                    zipFile.getRawInputStream(entry),
                    "Missing raw ZIP input: " + entryName
            )) {
                return new Deflate64Fixture(
                        archiveResource + "!" + entryName,
                        raw.readAllBytes(),
                        Math.toIntExact(entry.getSize()),
                        entry.getCrc()
                );
            }
        }
    }

    /// Decompresses a complete byte array through the channel transfer API.
    private static byte @Unmodifiable [] decompressComplete(
            CompressionCodec<?> codec,
            byte @Unmodifiable [] compressed
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        codec.decompress(
                Channels.newChannel(new ByteArrayInputStream(compressed)),
                Channels.newChannel(output)
        );
        return output.toByteArray();
    }

    /// Compresses a complete byte array through the channel transfer API.
    private static byte @Unmodifiable [] compressComplete(
            CompressionCodec<?> codec,
            byte @Unmodifiable [] source
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        codec.compress(
                Channels.newChannel(new ByteArrayInputStream(source)),
                Channels.newChannel(output)
        );
        return output.toByteArray();
    }

    /// Decompresses with the expansion-fixture output bound and discards the partial output.
    private static void decompressWithOutputLimit(
            CompressionCodec<?> codec,
            byte @Unmodifiable [] compressed
    ) throws IOException {
        codec.decompress(
                Channels.newChannel(new ByteArrayInputStream(compressed)),
                Channels.newChannel(new ByteArrayOutputStream()),
                DecodingOptions.ofMaximumOutputSize(EXPANSION_FIXTURE_OUTPUT_LIMIT)
        );
    }

    /// Decompresses bytes through a fragmented source and tiny direct target buffers.
    private static byte @Unmodifiable [] decompressFragmented(
            CompressionCodec<?> codec,
            byte @Unmodifiable [] compressed
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ChunkedReadableByteChannel source = new ChunkedReadableByteChannel(
                compressed,
                SMALL_SOURCE_CHUNK_SIZE
        ); ReadableByteChannel decoder = codec.newReadableByteChannel(source)) {
            ByteBuffer target = ByteBuffer.allocateDirect(SMALL_TARGET_BUFFER_SIZE);
            int zeroProgressReads = 0;
            while (true) {
                int read = decoder.read(target);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    zeroProgressReads++;
                    if (zeroProgressReads > 32) {
                        throw new IOException("Codec decoder repeatedly made no progress");
                    }
                    continue;
                }
                zeroProgressReads = 0;
                target.flip();
                byte[] bytes = new byte[target.remaining()];
                target.get(bytes);
                output.write(bytes);
                target.clear();
            }
        }
        return output.toByteArray();
    }

    /// Reads an input stream with a deliberately small heap buffer.
    private static byte @Unmodifiable [] readInSmallChunks(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[5];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                throw new IOException("Codec input stream made no progress");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    /// Returns the lowercase SHA-256 digest of bytes.
    private static String sha256(byte @Unmodifiable [] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is unavailable", exception);
        }
    }

    /// Returns the unsigned CRC-32 value of bytes.
    private static long crc32(byte @Unmodifiable [] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return crc.getValue();
    }

    /// Concatenates two byte arrays into a fresh array.
    private static byte @Unmodifiable [] concatenate(
            byte @Unmodifiable [] first,
            byte @Unmodifiable [] second
    ) {
        byte[] result = Arrays.copyOf(first, Math.addExact(first.length, second.length));
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    /// Describes one compressed fixture with independently fixed decoded-content metadata.
    @NotNullByDefault
    private static final class CodecFixture {
        /// The parameterized-test display name.
        private final String name;

        /// The Arkivo codec used to decode this fixture.
        private final CompressionCodec<?> codec;

        /// A private immutable copy of the compressed bytes.
        private final byte @Unmodifiable [] compressed;

        /// The expected decoded byte count.
        private final int expectedSize;

        /// The expected lowercase decoded SHA-256 digest.
        private final String expectedSha256;

        /// Creates one immutable codec fixture.
        private CodecFixture(
                String name,
                CompressionCodec<?> codec,
                byte @Unmodifiable [] compressed,
                int expectedSize,
                String expectedSha256
        ) {
            this.name = Objects.requireNonNull(name, "name");
            this.codec = Objects.requireNonNull(codec, "codec");
            this.compressed = compressed.clone();
            this.expectedSize = expectedSize;
            this.expectedSha256 = Objects.requireNonNull(expectedSha256, "expectedSha256");
        }

        /// Returns the display name.
        private String name() {
            return name;
        }

        /// Returns the configured codec.
        private CompressionCodec<?> codec() {
            return codec;
        }

        /// Returns a fresh copy of the compressed bytes.
        private byte @Unmodifiable [] compressed() {
            return compressed.clone();
        }

        /// Verifies decoded size and SHA-256 metadata.
        private void verify(byte @Unmodifiable [] decoded) {
            assertEquals(expectedSize, decoded.length, name);
            assertEquals(expectedSha256, sha256(decoded), name);
        }

        /// Returns the concise parameterized-test display name.
        @Override
        public String toString() {
            return name;
        }
    }

    /// Describes one raw Deflate64 ZIP fixture with central-directory output metadata.
    @NotNullByDefault
    private static final class Deflate64Fixture {
        /// The parameterized-test display name.
        private final String name;

        /// A private immutable copy of the raw compressed entry bytes.
        private final byte @Unmodifiable [] compressed;

        /// The uncompressed size stored in the ZIP central directory.
        private final int expectedSize;

        /// The unsigned CRC-32 stored in the ZIP central directory.
        private final long expectedCrc32;

        /// Creates one immutable Deflate64 fixture.
        private Deflate64Fixture(
                String name,
                byte @Unmodifiable [] compressed,
                int expectedSize,
                long expectedCrc32
        ) {
            this.name = Objects.requireNonNull(name, "name");
            this.compressed = compressed.clone();
            this.expectedSize = expectedSize;
            this.expectedCrc32 = expectedCrc32;
        }

        /// Returns the display name.
        private String name() {
            return name;
        }

        /// Returns a fresh copy of the raw compressed entry bytes.
        private byte @Unmodifiable [] compressed() {
            return compressed.clone();
        }

        /// Verifies decoded size and CRC-32 against the ZIP central directory.
        private void verify(byte @Unmodifiable [] decoded) {
            assertEquals(expectedSize, decoded.length, name);
            assertEquals(expectedCrc32, crc32(decoded), name);
        }

        /// Returns the concise parameterized-test display name.
        @Override
        public String toString() {
            return name;
        }
    }

    /// Presents an in-memory compressed stream through bounded source reads.
    @NotNullByDefault
    private static final class ChunkedReadableByteChannel implements ReadableByteChannel {
        /// The unread compressed bytes.
        private final ByteBuffer source;

        /// The maximum bytes returned by one read operation.
        private final int maximumChunkSize;

        /// Whether the channel remains open.
        private boolean open = true;

        /// Creates a channel over a private byte-array copy.
        private ChunkedReadableByteChannel(byte @Unmodifiable [] bytes, int maximumChunkSize) {
            if (maximumChunkSize <= 0) {
                throw new IllegalArgumentException("maximumChunkSize must be positive");
            }
            source = ByteBuffer.wrap(bytes.clone());
            this.maximumChunkSize = maximumChunkSize;
        }

        /// Reads at most the configured compressed-input chunk size.
        @Override
        public int read(ByteBuffer target) throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
            if (!source.hasRemaining()) {
                return -1;
            }
            if (!target.hasRemaining()) {
                return 0;
            }

            int count = Math.min(Math.min(source.remaining(), target.remaining()), maximumChunkSize);
            int originalLimit = source.limit();
            source.limit(source.position() + count);
            target.put(source);
            source.limit(originalLimit);
            return count;
        }

        /// Returns whether the channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes the channel.
        @Override
        public void close() {
            open = false;
        }
    }
}
