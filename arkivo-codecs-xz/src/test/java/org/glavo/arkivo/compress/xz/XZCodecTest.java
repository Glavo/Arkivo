// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.compress.xz;

import org.glavo.arkivo.compress.CompressionCodec;
import org.glavo.arkivo.compress.CompressionCodecs;
import org.glavo.arkivo.internal.XzInputStream;
import org.glavo.arkivo.internal.XzOutputStream;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.tukaani.xz.ARM64Options;
import org.tukaani.xz.ARMOptions;
import org.tukaani.xz.ARMThumbOptions;
import org.tukaani.xz.DeltaOptions;
import org.tukaani.xz.FilterOptions;
import org.tukaani.xz.IA64Options;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.PowerPCOptions;
import org.tukaani.xz.RISCVOptions;
import org.tukaani.xz.SPARCOptions;
import org.tukaani.xz.X86Options;
import org.tukaani.xz.XZ;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests XZ codec behavior.
@NotNullByDefault
public final class XZCodecTest {
    /// Verifies that XZ compression round-trips bytes.
    @Test
    public void roundTrip() throws IOException {
        XZCodec codec = new XZCodec();
        byte[] input = "hello xz".getBytes(StandardCharsets.UTF_8);

        assertEquals(true, codec instanceof CompressionCodec);
        assertEquals(XZCodec.NAME, codec.name());
        assertEquals(true, codec.canCompress());
        assertEquals(true, codec.canDecompress());
        assertArrayEquals(input, roundTrip(codec, input));
    }

    /// Verifies that the XZ codec can be discovered through service loading.
    @Test
    public void findInstalledCodec() {
        assertEquals(XZCodec.class, Objects.requireNonNull(CompressionCodecs.find(XZCodec.NAME)).getClass());
    }

    /// Verifies XZ signature matching.
    @Test
    public void metadata() {
        XZCodec codec = new XZCodec();
        assertEquals(true, codec.matches(ByteBuffer.wrap(new byte[]{
                (byte) 0xfd, 0x37, 0x7a, 0x58, 0x5a, 0x00
        })));
        assertEquals(false, codec.matches(ByteBuffer.wrap(new byte[]{(byte) 0xfd, 0x37})));
    }

    /// Verifies Arkivo's native XZ writer through XZ for Java's independent reader.
    @Test
    public void nativeWriterInteroperability() throws IOException {
        byte[] content = patternedContent(410_321);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (XzOutputStream output = new XzOutputStream(compressed)) {
            output.write(content, 0, 19);
            for (int index = 19; index < content.length; index++) {
                output.write(content[index]);
            }
        }

        try (org.tukaani.xz.XZInputStream input = new org.tukaani.xz.XZInputStream(
                new ByteArrayInputStream(compressed.toByteArray())
        )) {
            assertArrayEquals(content, input.readAllBytes());
        }
    }

    /// Verifies every supported XZ integrity check and multiple block records.
    @Test
    public void nativeReaderSupportsChecksAndMultipleBlocks() throws IOException {
        byte[] first = patternedContent(90_123);
        byte[] second = randomContent(70_777);
        for (int checkType : new int[]{XZ.CHECK_NONE, XZ.CHECK_CRC32, XZ.CHECK_CRC64, XZ.CHECK_SHA256}) {
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (org.tukaani.xz.XZOutputStream output = new org.tukaani.xz.XZOutputStream(
                    compressed,
                    new LZMA2Options(5),
                    checkType
            )) {
                output.write(first);
                output.endBlock();
                output.write(second);
            }

            try (XzInputStream input = new XzInputStream(new ByteArrayInputStream(compressed.toByteArray()))) {
                assertArrayEquals(concatenate(first, second), input.readAllBytes());
            }
        }
    }

    /// Verifies Delta and every standardized BCJ filter through independently encoded streams.
    @Test
    public void nativeReaderSupportsEveryFilter() throws IOException {
        byte[] content = filterContent();
        List<FilterOptions> filters = List.of(
                new DeltaOptions(7),
                new X86Options(),
                new PowerPCOptions(),
                new IA64Options(),
                new ARMOptions(),
                new ARMThumbOptions(),
                new SPARCOptions(),
                new ARM64Options(),
                new RISCVOptions()
        );
        for (FilterOptions filter : filters) {
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            FilterOptions[] chain = {filter, new LZMA2Options(4)};
            try (org.tukaani.xz.XZOutputStream output = new org.tukaani.xz.XZOutputStream(
                    compressed,
                    chain,
                    XZ.CHECK_CRC64
            )) {
                output.write(content);
            }

            try (XzInputStream input = new XzInputStream(new ByteArrayInputStream(compressed.toByteArray()))) {
                assertArrayEquals(content, input.readAllBytes(), filter.getClass().getSimpleName());
            }
        }
    }

    /// Verifies concatenated streams and four-byte stream padding.
    @Test
    public void nativeReaderSupportsConcatenatedStreams() throws IOException {
        byte[] first = patternedContent(12_345);
        byte[] second = randomContent(8_765);
        byte[] firstStream = independentStream(first, XZ.CHECK_CRC32);
        byte[] secondStream = independentStream(second, XZ.CHECK_SHA256);
        ByteArrayOutputStream concatenated = new ByteArrayOutputStream();
        concatenated.write(firstStream);
        concatenated.write(new byte[4]);
        concatenated.write(secondStream);

        try (XzInputStream input = new XzInputStream(new ByteArrayInputStream(concatenated.toByteArray()))) {
            assertArrayEquals(concatenate(first, second), input.readAllBytes());
        }
    }

    /// Verifies that an empty native stream has a valid zero-record Index.
    @Test
    public void nativeWriterProducesValidEmptyStream() throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (XzOutputStream ignored = new XzOutputStream(compressed)) {
        }

        try (org.tukaani.xz.XZInputStream input = new org.tukaani.xz.XZInputStream(
                new ByteArrayInputStream(compressed.toByteArray())
        )) {
            assertArrayEquals(new byte[0], input.readAllBytes());
        }
        try (XzInputStream input = new XzInputStream(new ByteArrayInputStream(compressed.toByteArray()))) {
            assertArrayEquals(new byte[0], input.readAllBytes());
        }
    }

    /// Verifies that single-stream mode leaves following container bytes unread.
    @Test
    public void nativeSingleStreamDoesNotConsumeFollowingBytes() throws IOException {
        byte[] content = patternedContent(32_123);
        byte[] stream = independentStream(content, XZ.CHECK_CRC64);
        byte[] trailer = {0x50, 0x4b, 0x07, 0x08, 0x11, 0x22, 0x33, 0x44};
        ByteArrayInputStream source = new ByteArrayInputStream(concatenate(stream, trailer));
        try (XzInputStream input = new XzInputStream(source, false)) {
            assertArrayEquals(content, input.readAllBytes());
        }
        assertArrayEquals(trailer, source.readAllBytes());
    }

    /// Verifies strict Stream Header, block-check, and Index CRC validation.
    @Test
    public void nativeReaderRejectsCorruptedStructures() throws IOException {
        byte[] content = patternedContent(45_678);
        byte[] valid = independentStream(content, XZ.CHECK_CRC64);

        byte[] headerCorrupt = valid.clone();
        headerCorrupt[8] ^= 1;
        assertThrows(IOException.class, () -> readNative(headerCorrupt));

        int footerOffset = valid.length - 12;
        long backward = littleEndian(valid, footerOffset + 4, 4);
        int indexSize = Math.toIntExact((backward + 1L) * 4L);
        int indexOffset = footerOffset - indexSize;

        byte[] checkCorrupt = valid.clone();
        checkCorrupt[indexOffset - 1] ^= 1;
        assertThrows(IOException.class, () -> readNative(checkCorrupt));

        byte[] indexCorrupt = valid.clone();
        indexCorrupt[footerOffset - 1] ^= 1;
        assertThrows(IOException.class, () -> readNative(indexCorrupt));
    }

    /// Compresses and decompresses the given bytes.
    private static byte[] roundTrip(CompressionCodec codec, byte[] input) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (OutputStream output = codec.compressTo(compressed)) {
            output.write(input);
        }

        try (InputStream inputStream = codec.decompressFrom(new ByteArrayInputStream(compressed.toByteArray()))) {
            return inputStream.readAllBytes();
        }
    }

    /// Returns one independently encoded XZ stream.
    private static byte[] independentStream(byte[] content, int checkType) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (org.tukaani.xz.XZOutputStream output = new org.tukaani.xz.XZOutputStream(
                compressed,
                new LZMA2Options(4),
                checkType
        )) {
            output.write(content);
        }
        return compressed.toByteArray();
    }

    /// Reads one byte array through the native concatenated-stream decoder.
    private static byte[] readNative(byte[] compressed) throws IOException {
        try (XzInputStream input = new XzInputStream(new ByteArrayInputStream(compressed))) {
            return input.readAllBytes();
        }
    }

    /// Returns an unsigned little-endian test value.
    private static long littleEndian(byte[] bytes, int offset, int length) {
        long value = 0L;
        for (int index = 0; index < length; index++) {
            value |= (long) Byte.toUnsignedInt(bytes[offset + index]) << (index * 8);
        }
        return value;
    }

    /// Returns deterministic content containing both repetition and literal-heavy ranges.
    private static byte[] patternedContent(int size) {
        byte[] content = new byte[size];
        byte[] phrase = "Arkivo native XZ interoperability block\n".getBytes(StandardCharsets.UTF_8);
        for (int index = 0; index < content.length; index++) {
            content[index] = index % 4093 < phrase.length
                    ? phrase[index % phrase.length]
                    : (byte) (index * 31 + index / 257);
        }
        return content;
    }

    /// Returns deterministic pseudo-random content.
    private static byte[] randomContent(int size) {
        byte[] content = new byte[size];
        new Random(0x585a41524b49564fL).nextBytes(content);
        return content;
    }

    /// Returns content containing branch-like words for every BCJ architecture.
    private static byte[] filterContent() {
        byte[] unit = {
                (byte) 0xe8, 0x10, 0x20, 0x30, 0x00,
                0x48, 0x00, 0x00, 0x01,
                0x10, 0x00, 0x00, 0x50, 0x00, 0x00, 0x40,
                0x00, 0x00, 0x00, (byte) 0xeb,
                0x00, (byte) 0xf0, 0x00, (byte) 0xf8,
                0x40, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, (byte) 0x94,
                0x00, 0x00, 0x00, (byte) 0x90,
                (byte) 0xef, 0x00, 0x00, 0x00,
                0x17, 0x05, 0x00, 0x00, 0x67, (byte) 0x80, 0x05, 0x00
        };
        byte[] content = new byte[unit.length * 512 + 17];
        for (int offset = 0; offset + unit.length <= content.length; offset += unit.length) {
            System.arraycopy(unit, 0, content, offset, unit.length);
        }
        return content;
    }

    /// Concatenates two byte arrays.
    private static byte[] concatenate(byte[] first, byte[] second) {
        byte[] combined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, combined, first.length, second.length);
        return combined;
    }
}
