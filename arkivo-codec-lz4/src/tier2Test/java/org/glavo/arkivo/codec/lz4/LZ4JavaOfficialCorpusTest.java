// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4;

import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;
import net.jpountz.lz4.LZ4SafeDecompressor;
import net.jpountz.xxhash.XXHashFactory;
import org.glavo.arkivo.codec.CompressionCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies raw-block and standard-frame interoperability with lz4-java 1.8.0 and its Calgary corpus.
@NotNullByDefault
public final class LZ4JavaOfficialCorpusTest {
    /// The system property containing the extracted lz4-java source-release directory.
    private static final String TEST_DATA_DIRECTORY_PROPERTY = "arkivo.lz4Java.testDataDirectory";

    /// The pure Java oracle factory used by all compatibility checks.
    private static final LZ4Factory LZ4_JAVA = LZ4Factory.safeInstance();

    /// The pure Java xxHash oracle factory used by standard-frame checks.
    private static final XXHashFactory XX_HASH_JAVA = XXHashFactory.safeInstance();

    /// Verifies both implementations can decode each other's raw blocks for every official Calgary file.
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"book1", "geo", "pic"})
    public void officialCalgaryFilesInteroperateAsRawBlocks(String fileName) throws IOException {
        byte @Unmodifiable [] input = calgaryFile(fileName);
        assertCorpusIdentity(fileName, input);
        LZ4BlockCodec codec = new LZ4BlockCodec().withMaximumBlockSize(input.length);

        byte @Unmodifiable [] arkivoCompressed = compressArkivo(codec, input);
        assertArrayEquals(
                input,
                LZ4_JAVA.safeDecompressor().decompress(arkivoCompressed, input.length),
                fileName + " Arkivo to lz4-java"
        );

        assertLZ4JavaRawEncodingDecodes(codec, input, LZ4_JAVA.fastCompressor(), "fast");
        assertLZ4JavaRawEncodingDecodes(codec, input, LZ4_JAVA.highCompressor(), "high");
    }

    /// Verifies all standard block sizes and checksum features in both frame directions.
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"book1", "geo", "pic"})
    public void officialCalgaryFilesInteroperateAsFrames(String fileName) throws IOException {
        byte @Unmodifiable [] input = calgaryFile(fileName);
        for (LZ4BlockSize blockSize : LZ4BlockSize.values()) {
            LZ4Codec codec = LZ4Codec.builder()
                    .blockSize(blockSize)
                    .blockChecksum(true)
                    .contentChecksum(true)
                    .build();

            byte @Unmodifiable [] arkivoFrame = compressArkivo(codec, input);
            assertArrayEquals(
                    input,
                    decompressLZ4JavaFrame(arkivoFrame),
                    fileName + " Arkivo " + blockSize
            );

            byte @Unmodifiable [] lz4JavaFrame = compressLZ4JavaFrame(
                    input,
                    lz4JavaBlockSize(blockSize),
                    input.length
            );
            assertArrayEquals(
                    input,
                    decompressArkivo(codec, lz4JavaFrame),
                    fileName + " lz4-java " + blockSize
            );
        }
    }

    /// Verifies lz4-java's issue 12 regression vector without vendoring its byte array in the repository.
    @Test
    public void officialIssue12RegressionVectorInteroperates() throws IOException {
        byte @Unmodifiable [] input = issue12RegressionData();
        assertEquals(1519, input.length);
        assertEquals(
                "e2264c1a61e14dfc711b27fac6cd05763fb175f10388db48eaaafdb539754be3",
                sha256(input)
        );
        LZ4BlockCodec codec = new LZ4BlockCodec().withMaximumBlockSize(input.length);

        byte @Unmodifiable [] arkivoCompressed = compressArkivo(codec, input);
        assertArrayEquals(input, LZ4_JAVA.safeDecompressor().decompress(arkivoCompressed, input.length));
        assertLZ4JavaRawEncodingDecodes(codec, input, LZ4_JAVA.fastCompressor(), "issue 12 fast");
        assertLZ4JavaRawEncodingDecodes(codec, input, LZ4_JAVA.highCompressor(), "issue 12 high");
    }

    /// Verifies the complete deterministic size matrix from lz4-java's frame stream tests.
    @Test
    public void officialFrameSizeMatrixInteroperates() throws IOException {
        int @Unmodifiable [] sizes = frameTestSizes();
        for (int index = 0; index < sizes.length; index++) {
            int size = sizes[index];
            byte @Unmodifiable [] input = frameTestData(size);
            LZ4Codec codec = LZ4Codec.builder()
                    .blockSize(LZ4BlockSize.MIB_4)
                    .blockChecksum(true)
                    .contentChecksum(true)
                    .build();

            assertArrayEquals(
                    input,
                    decompressLZ4JavaFrame(compressArkivo(codec, input)),
                    "Arkivo frame size " + size
            );

            long declaredSize = (index & 1) == 0 ? input.length : CompressionCodec.UNKNOWN_SIZE;
            assertArrayEquals(
                    input,
                    decompressArkivo(
                            codec,
                            compressLZ4JavaFrame(
                                    input,
                                    LZ4FrameOutputStream.BLOCKSIZE.SIZE_4MB,
                                    declaredSize
                            )
                    ),
                    "lz4-java frame size " + size
            );
        }
    }

    /// Verifies both implementations consume concatenated standard frames in order.
    @Test
    public void concatenatedFramesInteroperate() throws IOException {
        byte @Unmodifiable [] first = frameTestData(1025);
        byte @Unmodifiable [] second = frameTestData(131_072);
        byte @Unmodifiable [] expected = concatenate(first, second);
        LZ4Codec codec = new LZ4Codec();

        byte @Unmodifiable [] arkivoFrames = concatenate(
                compressArkivo(codec, first),
                compressArkivo(codec, second)
        );
        assertArrayEquals(expected, decompressLZ4JavaFrame(arkivoFrames));

        byte @Unmodifiable [] lz4JavaFrames = concatenate(
                compressLZ4JavaFrame(
                        first,
                        LZ4FrameOutputStream.BLOCKSIZE.SIZE_64KB,
                        CompressionCodec.UNKNOWN_SIZE
                ),
                compressLZ4JavaFrame(
                        second,
                        LZ4FrameOutputStream.BLOCKSIZE.SIZE_256KB,
                        second.length
                )
        );
        assertArrayEquals(expected, decompressArkivo(codec, lz4JavaFrames));
    }

    /// Verifies a valid frame whose declared content size is wrong is rejected.
    @Test
    public void rejectsIncorrectLZ4JavaContentSize() throws IOException {
        byte @Unmodifiable [] input = frameTestData(4097);
        byte @Unmodifiable [] frame = compressLZ4JavaFrame(
                input,
                LZ4FrameOutputStream.BLOCKSIZE.SIZE_64KB,
                input.length + 1L
        );

        assertThrows(IOException.class, () -> decompressArkivo(new LZ4Codec(), frame));
    }

    /// Verifies extraction retains the upstream license, README, and exact download lock manifest.
    @Test
    public void retainsUpstreamProvenance() {
        assertTrue(Files.isRegularFile(corpusPath("LICENSE.txt")));
        assertTrue(Files.isRegularFile(corpusPath("UPSTREAM.properties")));
        assertTrue(Files.isRegularFile(corpusPath("src/test/net/jpountz/lz4/LZ4Test.java")));
        assertTrue(Files.isRegularFile(corpusPath("src/test-resources/calgary/README")));
    }

    /// Verifies one lz4-java raw encoding can be decoded by Arkivo.
    private static void assertLZ4JavaRawEncodingDecodes(
            LZ4BlockCodec codec,
            byte @Unmodifiable [] input,
            LZ4Compressor compressor,
            String compressorName
    ) throws IOException {
        byte @Unmodifiable [] compressed = compressor.compress(input);
        assertArrayEquals(
                input,
                decompressArkivo(codec, compressed),
                compressorName + " compressor"
        );
    }

    /// Returns and verifies one official Calgary corpus file.
    private static byte @Unmodifiable [] calgaryFile(String fileName) throws IOException {
        return Files.readAllBytes(corpusPath("src/test-resources/calgary/" + fileName));
    }

    /// Loads the issue 12 byte vector from the pinned upstream Java test source.
    private static byte @Unmodifiable [] issue12RegressionData() throws IOException {
        String source = Files.readString(
                corpusPath("src/test/net/jpountz/lz4/LZ4Test.java"),
                StandardCharsets.UTF_8
        );
        String methodMarker = "public void testRoundtripIssue12()";
        String initializerMarker = "new byte[]{";
        int methodPosition = source.indexOf(methodMarker);
        if (methodPosition < 0) {
            throw new IOException("Cannot locate the issue 12 test in the pinned lz4-java source");
        }
        int initializerPosition = source.indexOf(initializerMarker, methodPosition);
        if (initializerPosition < 0) {
            throw new IOException("Cannot locate the issue 12 vector in the pinned lz4-java source");
        }
        int valuesStart = initializerPosition + initializerMarker.length();
        int valuesEnd = source.indexOf("};", valuesStart);
        if (valuesEnd < 0) {
            throw new IOException("Cannot locate the end of the issue 12 vector in the pinned lz4-java source");
        }

        String @Unmodifiable [] values = source.substring(valuesStart, valuesEnd).split(",");
        byte[] bytes = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            bytes[index] = (byte) Integer.parseInt(values[index].trim());
        }
        return bytes;
    }

    /// Verifies one Calgary file's exact size and SHA-256 identity.
    private static void assertCorpusIdentity(String fileName, byte @Unmodifiable [] bytes) {
        long expectedSize = switch (fileName) {
            case "book1" -> 768_771L;
            case "geo" -> 102_400L;
            case "pic" -> 513_216L;
            default -> throw new IllegalArgumentException("Unknown Calgary file: " + fileName);
        };
        String expectedSha256 = switch (fileName) {
            case "book1" -> "9ffa47cd93bccd732f20e0c304203cfbc1b8a91bedac536e2d8f6051003d9951";
            case "geo" -> "913ff6f45610599020c02f543a0d5a1f46cf772412e25a568b683d23db8c447d";
            case "pic" -> "0ec3a75089bb52342813496b17e51377bc9eba3cb519a444d67025354841d650";
            default -> throw new IllegalArgumentException("Unknown Calgary file: " + fileName);
        };

        assertEquals(expectedSize, bytes.length, fileName);
        assertEquals(expectedSha256, sha256(bytes), fileName);
    }

    /// Compresses one complete byte sequence through an Arkivo codec stream adapter.
    private static byte @Unmodifiable [] compressArkivo(
            CompressionCodec<?> codec,
            byte @Unmodifiable [] input
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (OutputStream encoder = codec.newOutputStream(output)) {
            encoder.write(input);
        }
        return output.toByteArray();
    }

    /// Decompresses one complete byte sequence through an Arkivo codec stream adapter.
    private static byte @Unmodifiable [] decompressArkivo(
            CompressionCodec<?> codec,
            byte @Unmodifiable [] compressed
    ) throws IOException {
        try (InputStream decoder = codec.newInputStream(new ByteArrayInputStream(compressed))) {
            return decoder.readAllBytes();
        }
    }

    /// Compresses one standard frame with pure Java lz4-java components and optional content-size metadata.
    private static byte @Unmodifiable [] compressLZ4JavaFrame(
            byte @Unmodifiable [] input,
            LZ4FrameOutputStream.BLOCKSIZE blockSize,
            long declaredSize
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LZ4FrameOutputStream encoder;
        if (declaredSize >= 0L) {
            encoder = new LZ4FrameOutputStream(
                    output,
                    blockSize,
                    declaredSize,
                    LZ4_JAVA.fastCompressor(),
                    XX_HASH_JAVA.hash32(),
                    LZ4FrameOutputStream.FLG.Bits.BLOCK_INDEPENDENCE,
                    LZ4FrameOutputStream.FLG.Bits.BLOCK_CHECKSUM,
                    LZ4FrameOutputStream.FLG.Bits.CONTENT_CHECKSUM,
                    LZ4FrameOutputStream.FLG.Bits.CONTENT_SIZE
            );
        } else {
            encoder = new LZ4FrameOutputStream(
                    output,
                    blockSize,
                    declaredSize,
                    LZ4_JAVA.fastCompressor(),
                    XX_HASH_JAVA.hash32(),
                    LZ4FrameOutputStream.FLG.Bits.BLOCK_INDEPENDENCE,
                    LZ4FrameOutputStream.FLG.Bits.BLOCK_CHECKSUM,
                    LZ4FrameOutputStream.FLG.Bits.CONTENT_CHECKSUM
            );
        }
        try (encoder) {
            encoder.write(input);
        }
        return output.toByteArray();
    }

    /// Decompresses concatenated standard frames with pure Java lz4-java components.
    private static byte @Unmodifiable [] decompressLZ4JavaFrame(
            byte @Unmodifiable [] compressed
    ) throws IOException {
        LZ4SafeDecompressor decompressor = LZ4_JAVA.safeDecompressor();
        try (InputStream decoder = new LZ4FrameInputStream(
                new ByteArrayInputStream(compressed),
                decompressor,
                XX_HASH_JAVA.hash32()
        )) {
            return decoder.readAllBytes();
        }
    }

    /// Maps one Arkivo block-size descriptor to its lz4-java equivalent.
    private static LZ4FrameOutputStream.BLOCKSIZE lz4JavaBlockSize(LZ4BlockSize blockSize) {
        return switch (blockSize) {
            case KIB_64 -> LZ4FrameOutputStream.BLOCKSIZE.SIZE_64KB;
            case KIB_256 -> LZ4FrameOutputStream.BLOCKSIZE.SIZE_256KB;
            case MIB_1 -> LZ4FrameOutputStream.BLOCKSIZE.SIZE_1MB;
            case MIB_4 -> LZ4FrameOutputStream.BLOCKSIZE.SIZE_4MB;
        };
    }

    /// Returns the exact fixed and deterministic-random sizes used by lz4-java's frame stream suite.
    private static int @Unmodifiable [] frameTestSizes() {
        int[] sizes = {
                0,
                1,
                1 << 10,
                (1 << 10) + 1,
                1 << 16,
                1 << 17,
                1 << 20,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0
        };
        Random random = new Random(78_370_789_134L);
        for (int index = 7; index < sizes.length; index++) {
            sizes[index] = Math.abs(random.nextInt()) % (1 << 22);
        }
        return sizes;
    }

    /// Recreates lz4-java's deterministic frame stream payload for one size.
    private static byte @Unmodifiable [] frameTestData(int size) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(size);
        Random random = new Random(5378L);
        int remaining = size;
        while (remaining > 0) {
            byte[] chunk = new byte[Math.min(remaining, 1 << 10)];
            random.nextBytes(chunk);
            ByteBuffer words = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN);
            while (words.remaining() >= Integer.BYTES) {
                words.putInt(0xdead_beef);
            }
            output.writeBytes(chunk);
            remaining -= chunk.length;
        }
        return output.toByteArray();
    }

    /// Concatenates two immutable byte sequences.
    private static byte @Unmodifiable [] concatenate(
            byte @Unmodifiable [] first,
            byte @Unmodifiable [] second
    ) {
        byte[] combined = new byte[Math.addExact(first.length, second.length)];
        System.arraycopy(first, 0, combined, 0, first.length);
        System.arraycopy(second, 0, combined, first.length, second.length);
        return combined;
    }

    /// Returns a path below the configured extracted lz4-java source-release directory.
    private static Path corpusPath(String relativePath) {
        @Nullable String configured = System.getProperty(TEST_DATA_DIRECTORY_PROPERTY);
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("Missing system property: " + TEST_DATA_DIRECTORY_PROPERTY);
        }
        Path root = Path.of(configured).toAbsolutePath().normalize();
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("lz4-java corpus path escapes its root: " + relativePath);
        }
        return resolved;
    }

    /// Returns the lowercase SHA-256 representation of bytes.
    private static String sha256(byte @Unmodifiable [] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is unavailable", exception);
        }
    }
}
