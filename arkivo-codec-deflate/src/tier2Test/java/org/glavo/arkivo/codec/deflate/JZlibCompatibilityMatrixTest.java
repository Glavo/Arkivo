// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

import com.jcraft.jzlib.Deflater;
import com.jcraft.jzlib.DeflaterOutputStream;
import com.jcraft.jzlib.Inflater;
import com.jcraft.jzlib.InflaterInputStream;
import com.jcraft.jzlib.JZlib;
import org.glavo.arkivo.codec.CompressionCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/// Extends JZlib interoperability across compression levels and Deflate history-window boundaries.
@NotNullByDefault
public final class JZlibCompatibilityMatrixTest {
    /// Verifies stored, fast, default, and maximum-compression streams in both encoding directions.
    @Test
    public void interoperatesAcrossCompressionLevels() throws IOException {
        int[] compressionLevels = {0, 1, 6, 9};
        byte[][] inputs = {
                repeatedBytes(),
                randomBytes(65_537, 0x4a5a_4c49L)
        };

        for (FormatCase format : formatCases()) {
            for (int compressionLevel : compressionLevels) {
                for (byte[] input : inputs) {
                    assertInteroperable(format, input, compressionLevel);
                }
            }
        }
    }

    /// Verifies lengths immediately around the 32 KiB history window and 64 KiB stream boundaries.
    @Test
    public void interoperatesAtWindowBoundaries() throws IOException {
        int[] sizes = {
                0, 1, 257,
                32_767, 32_768, 32_769,
                65_535, 65_536, 65_537,
                262_161
        };

        for (FormatCase format : formatCases()) {
            for (int size : sizes) {
                assertInteroperable(format, windowBoundaryBytes(size), 6);
            }
        }
    }

    /// Verifies both encoding directions for one format, level, and input vector.
    private static void assertInteroperable(
            FormatCase format,
            byte[] input,
            int compressionLevel
    ) throws IOException {
        CompressionCodec<?> codec = codec(format.wrapperType(), input.length, compressionLevel);
        int writeSize = Math.max(1, Math.min(1_021, input.length / 7 + 1));

        byte[] encodedByJZlib = compressWithJZlib(
                format.wrapperType(),
                input,
                compressionLevel,
                23 + compressionLevel,
                writeSize
        );
        assertArrayEquals(
                input,
                decompressWithArkivo(codec, encodedByJZlib),
                context(format, input, compressionLevel)
        );

        byte[] encodedByArkivo = compressWithArkivo(codec, input, writeSize);
        assertArrayEquals(
                input,
                decompressWithJZlib(format.wrapperType(), encodedByArkivo, 17 + compressionLevel),
                context(format, input, compressionLevel)
        );
    }

    /// Compresses bytes with JZlib using bounded input and output chunks.
    private static byte[] compressWithJZlib(
            JZlib.WrapperType wrapperType,
            byte[] input,
            int compressionLevel,
            int bufferSize,
            int writeSize
    ) throws IOException {
        Deflater deflater = new Deflater(
                compressionLevel,
                JZlib.MAX_WBITS,
                8,
                wrapperType
        );
        try {
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            try (OutputStream output = new DeflaterOutputStream(encoded, deflater, bufferSize, false)) {
                writeInChunks(output, input, writeSize);
            }
            return encoded.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /// Compresses bytes with Arkivo using bounded input chunks.
    private static byte[] compressWithArkivo(
            CompressionCodec<?> codec,
            byte[] input,
            int writeSize
    ) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (OutputStream output = codec.newOutputStream(encoded)) {
            writeInChunks(output, input, writeSize);
        }
        return encoded.toByteArray();
    }

    /// Decompresses one complete stream with Arkivo.
    private static byte[] decompressWithArkivo(CompressionCodec<?> codec, byte[] encoded) throws IOException {
        try (InputStream input = codec.newInputStream(new ByteArrayInputStream(encoded))) {
            return input.readAllBytes();
        }
    }

    /// Decompresses one complete stream with JZlib using a bounded internal buffer.
    private static byte[] decompressWithJZlib(
            JZlib.WrapperType wrapperType,
            byte[] encoded,
            int bufferSize
    ) throws IOException {
        Inflater inflater = new Inflater(wrapperType);
        try {
            try (InputStream input = new InflaterInputStream(
                    new ByteArrayInputStream(encoded),
                    inflater,
                    bufferSize,
                    true
            )) {
                return input.readAllBytes();
            }
        } finally {
            inflater.end();
        }
    }

    /// Writes all bytes in bounded chunks.
    private static void writeInChunks(OutputStream output, byte[] input, int chunkSize) throws IOException {
        for (int offset = 0; offset < input.length; offset += chunkSize) {
            int length = Math.min(chunkSize, input.length - offset);
            output.write(input, offset, length);
        }
    }

    /// Creates the Arkivo codec corresponding to one JZlib wrapper and compression level.
    private static CompressionCodec<?> codec(
            JZlib.WrapperType wrapperType,
            int maximumOutputSize,
            int compressionLevel
    ) {
        return switch (wrapperType) {
            case NONE -> DeflateCodec.DEFAULT
                    .withCompressionLevel(compressionLevel)
                    .withMaximumOutputSize(maximumOutputSize);
            case ZLIB -> ZlibCodec.DEFAULT
                    .withCompressionLevel(compressionLevel)
                    .withMaximumOutputSize(maximumOutputSize);
            case GZIP -> GzipCodec.DEFAULT
                    .withCompressionLevel(compressionLevel)
                    .withMaximumOutputSize(maximumOutputSize);
            case ANY -> throw new IllegalArgumentException("ANY is a decoder-only wrapper type");
        };
    }

    /// Returns the independently wrapped formats covered by the compatibility matrix.
    private static @Unmodifiable List<FormatCase> formatCases() {
        return List.of(
                new FormatCase("raw Deflate", JZlib.W_NONE),
                new FormatCase("zlib", JZlib.W_ZLIB),
                new FormatCase("gzip", JZlib.W_GZIP)
        );
    }

    /// Returns a deterministic 65,537-byte input with both short and long copy opportunities.
    private static byte[] repeatedBytes() {
        byte[] result = new byte[65_537];
        byte[] pattern = "Arkivo-Deflate-copy-pattern/".getBytes(StandardCharsets.US_ASCII);
        for (int index = 0; index < result.length; index++) {
            result[index] = pattern[index % pattern.length];
        }
        return result;
    }

    /// Returns an input that repeats bytes exactly one Deflate window later when its size permits.
    private static byte[] windowBoundaryBytes(int size) {
        byte[] result = randomBytes(size, 0x5749_4e44L + size);
        for (int index = 32_768; index < result.length; index++) {
            result[index] = result[index - 32_768];
        }
        return result;
    }

    /// Returns deterministic pseudo-random bytes.
    private static byte[] randomBytes(int size, long seed) {
        byte[] result = new byte[size];
        new Random(seed).nextBytes(result);
        return result;
    }

    /// Describes a format, level, and vector in assertion failures.
    private static String context(FormatCase format, byte[] input, int compressionLevel) {
        return format.name() + ", compression level " + compressionLevel + ", decoded size " + input.length;
    }

    /// Describes one JZlib wrapper and its assertion label.
    ///
    /// @param name        the human-readable format name
    /// @param wrapperType the exact JZlib wrapper mode
    @NotNullByDefault
    private record FormatCase(String name, JZlib.WrapperType wrapperType) {
    }
}
