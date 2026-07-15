// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.xz;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.ChecksumMode;
import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CodecResult;
import org.glavo.arkivo.codec.CodecStatus;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionCodecs;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.DecodeDirective;
import org.glavo.arkivo.codec.DecompressionWindowLimitException;
import org.glavo.arkivo.codec.StandardCodecOptions;
import org.glavo.arkivo.codec.lzma.LZMAOptions;
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
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /// Verifies Arkivo's pure Java XZ writer through XZ for Java's independent reader.
    @Test
    public void pureJavaWriterInteroperability() throws IOException {
        byte[] content = patternedContent(410_321);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (OutputStream output = new XZCodec().compressTo(compressed)) {
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
    public void pureJavaReaderSupportsChecksAndMultipleBlocks() throws IOException {
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

            byte[] encoded = compressed.toByteArray();
            byte[] expected = concatenate(first, second);
            try (InputStream input = new XZCodec().decompressFrom(new ByteArrayInputStream(encoded))) {
                assertArrayEquals(expected, input.readAllBytes());
            }
            assertArrayEquals(expected, readCodec(encoded));
        }
    }

    /// Verifies Delta and every standardized BCJ filter through independently encoded streams.
    @Test
    public void pureJavaReaderSupportsEveryFilter() throws IOException {
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

            byte[] encoded = compressed.toByteArray();
            try (InputStream input = new XZCodec().decompressFrom(new ByteArrayInputStream(encoded))) {
                assertArrayEquals(content, input.readAllBytes(), filter.getClass().getSimpleName());
            }
            assertArrayEquals(content, readCodec(encoded), filter.getClass().getSimpleName());
        }
    }

    /// Verifies XZ encoding with Delta, every standardized BCJ filter, and combined chains.
    @Test
    public void pureJavaWriterSupportsEveryFilterChain() throws IOException {
        XZCodec codec = new XZCodec();
        assertTrue(codec.capabilities().compressionOptions().contains(XZCodec.FILTER_CHAIN));
        byte[] content = filterContent();

        List<XZFilter> individualFilters = new ArrayList<>();
        individualFilters.add(new XZDeltaFilter(7L));
        for (XZBCJFilter.Architecture architecture : XZBCJFilter.Architecture.values()) {
            assertEquals(0x04L + architecture.ordinal(), architecture.identifier());
            individualFilters.add(new XZBCJFilter(architecture, 32L));
        }
        individualFilters.add(new XZBCJFilter(
                XZBCJFilter.Architecture.X86,
                0xffff_ffffL
        ));

        for (XZFilter filter : individualFilters) {
            XZFilterChain chain = new XZFilterChain(List.of(filter));
            byte[] encoded = encodeWithFilterChain(codec, content, chain);
            assertEquals(1, Byte.toUnsignedInt(encoded[13]) & 3, filter.toString());
            assertArrayEquals(content, readCodec(encoded), filter.toString());
            try (org.tukaani.xz.XZInputStream input = new org.tukaani.xz.XZInputStream(
                    new ByteArrayInputStream(encoded)
            )) {
                assertArrayEquals(content, input.readAllBytes(), filter.toString());
            }
        }

        ArrayList<XZFilter> mutable = new ArrayList<>(List.of(
                new XZDeltaFilter(4L),
                new XZBCJFilter(XZBCJFilter.Architecture.X86, 32L),
                new XZDeltaFilter(7L)
        ));
        XZFilterChain combined = new XZFilterChain(mutable);
        mutable.clear();
        assertEquals(3, combined.filters().size());

        byte[] combinedEncoded = encodeWithFilterChain(codec, content, combined);
        assertEquals(3, Byte.toUnsignedInt(combinedEncoded[13]) & 3);
        assertArrayEquals(content, readCodec(combinedEncoded));
        try (org.tukaani.xz.XZInputStream input = new org.tukaani.xz.XZInputStream(
                new ByteArrayInputStream(combinedEncoded)
        )) {
            assertArrayEquals(content, input.readAllBytes());
        }

        assertThrows(IllegalArgumentException.class, () -> new XZDeltaFilter(0L));
        assertThrows(IllegalArgumentException.class, () -> new XZDeltaFilter(257L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new XZBCJFilter(XZBCJFilter.Architecture.IA64, 4L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new XZBCJFilter(XZBCJFilter.Architecture.X86, 0x1_0000_0000L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new XZFilterChain(List.of(
                        new XZDeltaFilter(1L),
                        new XZDeltaFilter(2L),
                        new XZDeltaFilter(3L),
                        new XZDeltaFilter(4L)
                ))
        );
    }
    /// Verifies configurable multi-Block output, Index records, and Block-local filter state.
    @Test
    public void pureJavaWriterSupportsConfiguredBlockSize() throws IOException {
        XZCodec codec = new XZCodec();
        assertTrue(codec.capabilities().compressionOptions().contains(XZCodec.BLOCK_SIZE));

        byte[] content = filterContent();
        long blockSize = 4_096L;
        CodecOptions options = CodecOptions.builder()
                .set(XZCodec.BLOCK_SIZE, blockSize)
                .set(XZCodec.CHECK_TYPE, XZCheckType.SHA256)
                .set(XZCodec.FILTER_CHAIN, new XZFilterChain(List.of(
                        new XZDeltaFilter(4L),
                        new XZBCJFilter(XZBCJFilter.Architecture.X86, 32L)
                )))
                .set(LZMAOptions.LITERAL_CONTEXT_BITS, 2L)
                .set(LZMAOptions.LITERAL_POSITION_BITS, 1L)
                .set(LZMAOptions.POSITION_BITS, 3L)
                .build();
        byte[] encoded = encode(codec, content, options);

        ByteBuffer allocatingSource = ByteBuffer.allocateDirect(content.length).put(content).flip();
        ByteBuffer allocatingEncoded = codec.compress(allocatingSource, options);
        ByteBuffer allocatingDecoded = codec.decompress(allocatingEncoded, content.length);
        byte[] allocatingActual = new byte[allocatingDecoded.remaining()];
        allocatingDecoded.get(allocatingActual);
        assertArrayEquals(content, allocatingActual);

        long[] blockSizes = xzIndexUncompressedSizes(encoded);
        assertEquals((content.length + blockSize - 1L) / blockSize, blockSizes.length);
        long remaining = content.length;
        for (long actual : blockSizes) {
            long expected = Math.min(blockSize, remaining);
            assertEquals(expected, actual);
            remaining -= actual;
        }
        assertEquals(0L, remaining);
        assertArrayEquals(content, readCodec(encoded));
        try (org.tukaani.xz.XZInputStream input = new org.tukaani.xz.XZInputStream(
                new ByteArrayInputStream(encoded)
        )) {
            assertArrayEquals(content, input.readAllBytes());
        }

        byte[] exactBoundary = Arrays.copyOf(content, 8_192);
        byte[] exactEncoded = encode(
                codec,
                exactBoundary,
                CodecOptions.builder().set(XZCodec.BLOCK_SIZE, blockSize).build()
        );
        assertArrayEquals(new long[]{blockSize, blockSize}, xzIndexUncompressedSizes(exactEncoded));

        byte[] tiny = Arrays.copyOf(content, 17);
        byte[] tinyEncoded = encode(
                codec,
                tiny,
                CodecOptions.builder().set(XZCodec.BLOCK_SIZE, 1L).build()
        );
        assertEquals(tiny.length, xzIndexUncompressedSizes(tinyEncoded).length);
        assertArrayEquals(tiny, readCodec(tinyEncoded));

        byte[] unbounded = encode(
                codec,
                content,
                CodecOptions.builder().set(XZCodec.BLOCK_SIZE, 0L).build()
        );
        assertArrayEquals(new long[]{content.length}, xzIndexUncompressedSizes(unbounded));

        CodecOptions invalid = CodecOptions.builder().set(XZCodec.BLOCK_SIZE, -1L).build();
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.openEncoder(
                        Channels.newChannel(new ByteArrayOutputStream()),
                        invalid,
                        ChannelOwnership.RETAIN
                )
        );
    }
    /// Verifies concatenated streams and four-byte stream padding.
    @Test
    public void pureJavaReaderSupportsConcatenatedStreams() throws IOException {
        byte[] first = patternedContent(12_345);
        byte[] second = randomContent(8_765);
        byte[] firstStream = independentStream(first, XZ.CHECK_CRC32);
        byte[] secondStream = independentStream(second, XZ.CHECK_SHA256);
        ByteArrayOutputStream concatenated = new ByteArrayOutputStream();
        concatenated.write(firstStream);
        concatenated.write(new byte[4]);
        concatenated.write(secondStream);

        byte[] encoded = concatenated.toByteArray();
        try (InputStream input = new XZCodec().decompressFrom(new ByteArrayInputStream(encoded))) {
            assertArrayEquals(concatenate(first, second), input.readAllBytes());
        }
        assertArrayEquals(concatenate(first, second), readCodec(encoded));
    }

    /// Verifies that an empty pure Java stream has a valid zero-record Index.
    @Test
    public void pureJavaWriterProducesValidEmptyStream() throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (OutputStream ignored = new XZCodec().compressTo(compressed)) {
        }

        try (org.tukaani.xz.XZInputStream input = new org.tukaani.xz.XZInputStream(
                new ByteArrayInputStream(compressed.toByteArray())
        )) {
            assertArrayEquals(new byte[0], input.readAllBytes());
        }
        byte[] encoded = compressed.toByteArray();
        try (InputStream input = new XZCodec().decompressFrom(new ByteArrayInputStream(encoded))) {
            assertArrayEquals(new byte[0], input.readAllBytes());
        }
        assertArrayEquals(new byte[0], readCodec(encoded));
    }

    /// Verifies frame-boundary decoding preserves following container bytes across read-ahead.
    @Test
    public void pureJavaSingleFramePreservesFollowingBytes() throws IOException {
        byte[] content = patternedContent(32_123);
        byte[] stream = independentStream(content, XZ.CHECK_CRC64);
        byte[] trailer = {0x50, 0x4b, 0x07, 0x08, 0x11, 0x22, 0x33, 0x44};
        ReadableByteChannel source = Channels.newChannel(new ByteArrayInputStream(concatenate(stream, trailer)));
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        try (CompressionDecoder decoder = new XZCodec().openDecoder(
                source,
                CodecOptions.EMPTY,
                ChannelOwnership.RETAIN
        )) {
            ByteBuffer target = ByteBuffer.allocate(4096);
            CodecStatus status;
            do {
                target.clear();
                CodecResult result = decoder.decode(target, DecodeDirective.STOP_AT_FRAME);
                decoded.write(target.array(), 0, target.position());
                status = result.status();
            } while (status != CodecStatus.FRAME_FINISHED);
            assertEquals(stream.length, decoder.inputBytes());

            ByteArrayOutputStream remainder = new ByteArrayOutputStream();
            ByteBuffer prefetched = decoder.unconsumedInput();
            assertEquals(decoder.sourceBytes() - decoder.inputBytes(), prefetched.remaining());
            byte[] prefetchedBytes = new byte[prefetched.remaining()];
            prefetched.get(prefetchedBytes);
            remainder.write(prefetchedBytes);
            ByteBuffer remaining = ByteBuffer.allocate(64);
            int count;
            while ((count = source.read(remaining)) >= 0) {
                if (count == 0) {
                    continue;
                }
                remaining.flip();
                byte[] bytes = new byte[remaining.remaining()];
                remaining.get(bytes);
                remainder.write(bytes);
                remaining.clear();
            }
            assertArrayEquals(trailer, remainder.toByteArray());
        }
        assertArrayEquals(content, decoded.toByteArray());
    }

    /// Verifies strict Stream Header, block-check, and Index CRC validation.
    @Test
    public void pureJavaReaderRejectsCorruptedStructures() throws IOException {
        byte[] content = patternedContent(45_678);
        byte[] valid = independentStream(content, XZ.CHECK_CRC64);

        byte[] headerCorrupt = valid.clone();
        headerCorrupt[8] ^= 1;
        assertThrows(IOException.class, () -> readStreamAdapter(headerCorrupt));
        assertThrows(IOException.class, () -> readCodec(headerCorrupt));

        int footerOffset = valid.length - 12;
        long backward = littleEndian(valid, footerOffset + 4, 4);
        int indexSize = Math.toIntExact((backward + 1L) * 4L);
        int indexOffset = footerOffset - indexSize;

        byte[] checkCorrupt = valid.clone();
        checkCorrupt[indexOffset - 1] ^= 1;
        assertThrows(IOException.class, () -> readStreamAdapter(checkCorrupt));
        assertThrows(IOException.class, () -> readCodec(checkCorrupt));

        byte[] indexCorrupt = valid.clone();
        indexCorrupt[footerOffset - 1] ^= 1;
        assertThrows(IOException.class, () -> readStreamAdapter(indexCorrupt));
        assertThrows(IOException.class, () -> readCodec(indexCorrupt));
    }

    /// Verifies that checksum policy controls Block Checks without weakening XZ structure validation.
    @Test
    public void checksumVerificationPolicy() throws IOException {
        XZCodec codec = new XZCodec();
        assertTrue(codec.capabilities().decompressionOptions().contains(StandardCodecOptions.CHECKSUM));

        byte[] content = patternedContent(45_678);
        byte[] valid = independentStream(content, XZ.CHECK_CRC32);
        int footerOffset = valid.length - 12;
        int indexSize = Math.toIntExact((littleEndian(valid, footerOffset + 4, 4) + 1L) * 4L);
        int indexOffset = footerOffset - indexSize;

        CodecOptions enabled = CodecOptions.builder()
                .set(StandardCodecOptions.CHECKSUM, ChecksumMode.ENABLED)
                .build();
        CodecOptions disabled = CodecOptions.builder()
                .set(StandardCodecOptions.CHECKSUM, ChecksumMode.DISABLED)
                .build();

        byte[] checkCorrupt = valid.clone();
        checkCorrupt[indexOffset - 1] ^= 1;
        assertThrows(IOException.class, () -> readCodec(checkCorrupt));
        assertThrows(IOException.class, () -> readCodec(checkCorrupt, enabled));
        assertArrayEquals(content, readCodec(checkCorrupt, disabled));

        byte[] unknownCheck = valid.clone();
        unknownCheck[7] = 2;
        rewriteCrc32(unknownCheck, 6, 2, 8);
        unknownCheck[footerOffset + 9] = 2;
        rewriteCrc32(unknownCheck, footerOffset + 4, 6, footerOffset);
        assertThrows(IOException.class, () -> readCodec(unknownCheck));
        assertThrows(IOException.class, () -> readCodec(unknownCheck, enabled));
        assertArrayEquals(content, readCodec(unknownCheck, disabled));

        byte[] headerCorrupt = valid.clone();
        headerCorrupt[8] ^= 1;
        assertThrows(IOException.class, () -> readCodec(headerCorrupt, disabled));

        byte[] indexCorrupt = valid.clone();
        indexCorrupt[footerOffset - 1] ^= 1;
        assertThrows(IOException.class, () -> readCodec(indexCorrupt, disabled));
    }

    /// Verifies direct channel options, counters, and endpoint ownership.
    @Test
    public void directChannelsExposeOptionsCountersAndOwnership() throws IOException {
        byte[] content = patternedContent(240_321);
        ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
        WritableByteChannel compressedTarget = Channels.newChannel(compressedBytes);
        CodecOptions options = CodecOptions.builder()
                .set(XZCodec.DICTIONARY_SIZE, 1L << 16)
                .set(XZCodec.CHECK_TYPE, XZCheckType.SHA256)
                .build();
        CompressionEncoder encoder = new XZCodec().openEncoder(
                compressedTarget,
                options,
                ChannelOwnership.RETAIN
        );
        ByteBuffer source = ByteBuffer.allocateDirect(content.length).put(content).flip();
        assertEquals(content.length, encoder.write(source));
        encoder.finish();
        assertEquals(content.length, encoder.inputBytes());
        assertEquals(compressedBytes.size(), encoder.outputBytes());
        assertFalse(encoder.isOpen());
        assertTrue(compressedTarget.isOpen());
        byte[] encoded = compressedBytes.toByteArray();
        assertEquals(XZ.CHECK_SHA256, Byte.toUnsignedInt(encoded[7]));
        try (org.tukaani.xz.XZInputStream independent = new org.tukaani.xz.XZInputStream(
                new ByteArrayInputStream(encoded)
        )) {
            assertArrayEquals(content, independent.readAllBytes());
        }

        ReadableByteChannel compressedSource = Channels.newChannel(new ByteArrayInputStream(encoded));
        CompressionDecoder decoder = new XZCodec().openDecoder(
                compressedSource,
                CodecOptions.EMPTY,
                ChannelOwnership.CLOSE
        );
        ByteBuffer decoded = ByteBuffer.allocateDirect(content.length);
        while (decoded.hasRemaining()) {
            assertTrue(decoder.read(decoded) > 0);
        }
        assertEquals(-1, decoder.read(ByteBuffer.allocateDirect(1)));
        assertEquals(encoded.length, decoder.inputBytes());
        assertEquals(content.length, decoder.outputBytes());
        decoder.close();
        assertFalse(compressedSource.isOpen());
        decoded.flip();
        byte[] actual = new byte[decoded.remaining()];
        decoded.get(actual);
        assertArrayEquals(content, actual);

        CodecOptions disabled = CodecOptions.builder()
                .set(StandardCodecOptions.CHECKSUM, ChecksumMode.DISABLED)
                .build();
        ByteArrayOutputStream uncheckedBytes = new ByteArrayOutputStream();
        try (CompressionEncoder unchecked = new XZCodec().openEncoder(
                Channels.newChannel(uncheckedBytes),
                disabled,
                ChannelOwnership.RETAIN
        )) {
            unchecked.finish();
        }
        assertEquals(XZ.CHECK_NONE, Byte.toUnsignedInt(uncheckedBytes.toByteArray()[7]));

        CodecOptions conflicting = CodecOptions.builder()
                .set(StandardCodecOptions.CHECKSUM, ChecksumMode.DISABLED)
                .set(XZCodec.CHECK_TYPE, XZCheckType.CRC32)
                .build();
        assertThrows(IllegalArgumentException.class, () -> new XZCodec().openEncoder(
                Channels.newChannel(new ByteArrayOutputStream()),
                conflicting,
                ChannelOwnership.RETAIN
        ));
    }

    /// Verifies shared LZMA model options reach XZ LZMA2 chunks and remain interoperable.
    @Test
    public void advancedLzmaOptionsControlXzBlocks() throws IOException {
        XZCodec codec = new XZCodec();
        assertTrue(codec.capabilities().compressionOptions().containsAll(Set.of(
                XZCodec.DICTIONARY_SIZE,
                LZMAOptions.LITERAL_CONTEXT_BITS,
                LZMAOptions.LITERAL_POSITION_BITS,
                LZMAOptions.POSITION_BITS
        )));

        byte[] content = patternedContent(240_321);
        int literalContextBits = 2;
        int literalPositionBits = 1;
        int positionBits = 3;
        CodecOptions options = CodecOptions.builder()
                .set(XZCodec.DICTIONARY_SIZE, 1L << 17)
                .set(LZMAOptions.LITERAL_CONTEXT_BITS, (long) literalContextBits)
                .set(LZMAOptions.LITERAL_POSITION_BITS, (long) literalPositionBits)
                .set(LZMAOptions.POSITION_BITS, (long) positionBits)
                .build();

        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        codec.compress(
                Channels.newChannel(new ByteArrayInputStream(content)),
                Channels.newChannel(compressed),
                options
        );
        byte[] encoded = compressed.toByteArray();
        int firstChunkOffset = 24;
        assertTrue(Byte.toUnsignedInt(encoded[firstChunkOffset]) >= 0xe0);
        assertEquals(
                (positionBits * 5 + literalPositionBits) * 9 + literalContextBits,
                Byte.toUnsignedInt(encoded[firstChunkOffset + 5])
        );
        try (org.tukaani.xz.XZInputStream input = new org.tukaani.xz.XZInputStream(
                new ByteArrayInputStream(encoded)
        )) {
            assertArrayEquals(content, input.readAllBytes());
        }

        CodecOptions invalid = CodecOptions.builder()
                .set(LZMAOptions.LITERAL_CONTEXT_BITS, 4L)
                .set(LZMAOptions.LITERAL_POSITION_BITS, 1L)
                .build();
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.openEncoder(
                        Channels.newChannel(new ByteArrayOutputStream()),
                        invalid,
                        ChannelOwnership.RETAIN
                )
        );
    }

    /// Verifies that the decoder enforces the LZMA2 dictionary size declared by an XZ block.
    @Test
    public void decoderEnforcesDeclaredDictionarySize() throws IOException {
        long dictionarySize = 1L << 16;
        byte[] content = patternedContent(240_321);
        ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
        XZCodec codec = new XZCodec();
        codec.compress(
                Channels.newChannel(new ByteArrayInputStream(content)),
                Channels.newChannel(compressedBytes),
                CodecOptions.builder().set(XZCodec.DICTIONARY_SIZE, dictionarySize).build()
        );

        ByteArrayOutputStream decodedBytes = new ByteArrayOutputStream();
        codec.decompress(
                Channels.newChannel(new ByteArrayInputStream(compressedBytes.toByteArray())),
                Channels.newChannel(decodedBytes),
                CodecOptions.builder().set(StandardCodecOptions.MAX_WINDOW_SIZE, dictionarySize).build()
        );
        assertArrayEquals(content, decodedBytes.toByteArray());

        DecompressionWindowLimitException exception = assertThrows(
                DecompressionWindowLimitException.class,
                () -> codec.decompress(
                        Channels.newChannel(new ByteArrayInputStream(compressedBytes.toByteArray())),
                        Channels.newChannel(new ByteArrayOutputStream()),
                        CodecOptions.builder()
                                .set(StandardCodecOptions.MAX_WINDOW_SIZE, dictionarySize - 1L)
                                .build()
                )
        );
        assertEquals(dictionarySize - 1L, exception.maximumWindowSize());
        assertEquals(dictionarySize, exception.requiredWindowSize());
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

    /// Encodes bytes with one public XZ preprocessing-filter chain.
    private static byte[] encodeWithFilterChain(
            XZCodec codec,
            byte[] content,
            XZFilterChain filterChain
    ) throws IOException {
        return encode(
                codec,
                content,
                CodecOptions.builder()
                        .set(XZCodec.FILTER_CHAIN, filterChain)
                        .build()
        );
    }

    /// Encodes bytes with explicit XZ operation options.
    private static byte[] encode(
            XZCodec codec,
            byte[] content,
            CodecOptions options
    ) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        codec.compress(
                Channels.newChannel(new ByteArrayInputStream(content)),
                Channels.newChannel(compressed),
                options
        );
        return compressed.toByteArray();
    }

    /// Returns the uncompressed sizes recorded by an XZ stream's final Index.
    private static long[] xzIndexUncompressedSizes(byte[] encoded) {
        int footerOffset = encoded.length - 12;
        long backwardSize = littleEndian(encoded, footerOffset + 4, Integer.BYTES);
        int indexSize = Math.toIntExact((backwardSize + 1L) * 4L);
        int[] cursor = {footerOffset - indexSize};
        assertEquals(0, Byte.toUnsignedInt(encoded[cursor[0]++]));
        int recordCount = Math.toIntExact(readVli(encoded, cursor));
        long[] sizes = new long[recordCount];
        for (int record = 0; record < recordCount; record++) {
            readVli(encoded, cursor);
            sizes[record] = readVli(encoded, cursor);
        }
        return sizes;
    }

    /// Reads one canonical XZ variable-length integer from test bytes.
    private static long readVli(byte[] bytes, int[] cursor) {
        long value = 0L;
        for (int index = 0; index < 9; index++) {
            int current = Byte.toUnsignedInt(bytes[cursor[0]++]);
            value |= (long) (current & 0x7f) << (index * 7);
            if ((current & 0x80) == 0) {
                return value;
            }
        }
        throw new AssertionError("Invalid XZ test VLI");
    }
    /// Reads one byte array through the public InputStream adapter.
    private static byte[] readStreamAdapter(byte[] compressed) throws IOException {
        try (InputStream input = new XZCodec().decompressFrom(new ByteArrayInputStream(compressed))) {
            return input.readAllBytes();
        }
    }

    /// Reads one byte array through the public channel-first codec.
    private static byte[] readCodec(byte[] compressed) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new XZCodec().decompress(
                Channels.newChannel(new ByteArrayInputStream(compressed)),
                Channels.newChannel(output)
        );
        return output.toByteArray();
    }

    /// Reads one byte array through the public channel-first codec with explicit options.
    private static byte[] readCodec(byte[] compressed, CodecOptions options) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new XZCodec().decompress(
                Channels.newChannel(new ByteArrayInputStream(compressed)),
                Channels.newChannel(output),
                options
        );
        return output.toByteArray();
    }

    /// Rewrites one stored little-endian CRC-32 for a test byte range.
    private static void rewriteCrc32(byte[] bytes, int offset, int length, int storedOffset) {
        CRC32 crc32 = new CRC32();
        crc32.update(bytes, offset, length);
        long value = crc32.getValue();
        for (int index = 0; index < Integer.BYTES; index++) {
            bytes[storedOffset + index] = (byte) (value >>> (index * 8));
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
        byte[] phrase = "Arkivo pure Java XZ interoperability block\n".getBytes(StandardCharsets.UTF_8);
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
