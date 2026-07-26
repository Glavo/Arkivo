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

/// Tests raw Deflate, zlib, and gzip interoperability with JZlib.
@NotNullByDefault
public final class JZlibInteroperabilityTest {
    /// Verifies both encoding directions for every supported JZlib wrapper.
    @Test
    public void interoperatesAcrossWrappers() throws IOException {
        for (FormatCase format : formatCases()) {
            for (byte[] input : testCases()) {
                CompressionCodec<?> codec = codec(format.wrapperType(), input.length);

                byte[] encodedByJZlib = compressWithJZlib(format.wrapperType(), input);
                assertArrayEquals(
                        input,
                        decompressWithArkivo(codec, encodedByJZlib),
                        context(format, input)
                );

                byte[] encodedByArkivo = compressWithArkivo(codec, input);
                assertArrayEquals(
                        input,
                        decompressWithJZlib(format.wrapperType(), encodedByArkivo),
                        context(format, input)
                );
            }
        }
    }

    /// Compresses bytes with a JZlib wrapper while exercising fragmented writes and output buffers.
    private static byte[] compressWithJZlib(
            JZlib.WrapperType wrapperType,
            byte[] input
    ) throws IOException {
        Deflater deflater = new Deflater(
                6,
                JZlib.MAX_WBITS,
                8,
                wrapperType
        );
        try {
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            try (OutputStream output = new DeflaterOutputStream(encoded, deflater, 17, false)) {
                writeInChunks(output, input, 11);
            }
            return encoded.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /// Compresses bytes with Arkivo while exercising fragmented writes.
    private static byte[] compressWithArkivo(
            CompressionCodec<?> codec,
            byte[] input
    ) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (OutputStream output = codec.newOutputStream(encoded)) {
            writeInChunks(output, input, 13);
        }
        return encoded.toByteArray();
    }

    /// Decompresses one complete stream with Arkivo.
    private static byte[] decompressWithArkivo(CompressionCodec<?> codec, byte[] encoded) throws IOException {
        try (InputStream input = codec.newInputStream(new ByteArrayInputStream(encoded))) {
            return input.readAllBytes();
        }
    }

    /// Decompresses one complete stream with JZlib using a small internal buffer.
    private static byte[] decompressWithJZlib(
            JZlib.WrapperType wrapperType,
            byte[] encoded
    ) throws IOException {
        Inflater inflater = new Inflater(wrapperType);
        try {
            try (InputStream input = new InflaterInputStream(
                    new ByteArrayInputStream(encoded),
                    inflater,
                    19,
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

    /// Creates the Arkivo codec corresponding to one JZlib wrapper.
    private static CompressionCodec<?> codec(
            JZlib.WrapperType wrapperType,
            int maximumOutputSize
    ) {
        return switch (wrapperType) {
            case NONE -> DeflateCodec.DEFAULT
                    .withCompressionLevel(6)
                    .withMaximumOutputSize(maximumOutputSize);
            case ZLIB -> ZlibCodec.DEFAULT
                    .withCompressionLevel(6)
                    .withMaximumOutputSize(maximumOutputSize);
            case GZIP -> GzipCodec.DEFAULT
                    .withCompressionLevel(6)
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

    /// Returns representative empty, literal, copy, and incompressible inputs.
    private static byte[][] testCases() {
        return new byte[][]{
                new byte[0],
                "hello world!".getBytes(StandardCharsets.UTF_8),
                "XXXXabcdabcdABCDABCDwxyzwzyz123".getBytes(StandardCharsets.UTF_8),
                "XXXXabcdefgh abcdefgh abcdefgh abcdefgh abcdefgh abcdefgh ABC"
                        .getBytes(StandardCharsets.UTF_8),
                ascendingBytes(),
                "Arkivo Deflate copy sequence ".repeat(257).getBytes(StandardCharsets.UTF_8),
                randomBytes()
        };
    }

    /// Returns 256 bytes whose unsigned values increase from zero through 255.
    private static byte[] ascendingBytes() {
        byte[] result = new byte[256];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) index;
        }
        return result;
    }

    /// Returns a deterministic 65,537-byte pseudo-random vector.
    private static byte[] randomBytes() {
        byte[] result = new byte[65_537];
        new Random(0x4446_4c54L).nextBytes(result);
        return result;
    }

    /// Describes a format and vector in assertion failures.
    private static String context(FormatCase format, byte[] input) {
        return format.name() + ", decoded size " + input.length;
    }

    /// Describes one JZlib wrapper and its assertion label.
    ///
    /// @param name        the human-readable format name
    /// @param wrapperType the exact JZlib wrapper mode
    @NotNullByDefault
    private record FormatCase(String name, JZlib.WrapperType wrapperType) {
    }
}
