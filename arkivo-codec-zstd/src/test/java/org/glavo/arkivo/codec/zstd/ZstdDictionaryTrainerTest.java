// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CompressionDictionary;
import org.glavo.arkivo.codec.StandardCodecOptions;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies direct-buffer Zstandard dictionary training and dictionary metadata.
@NotNullByDefault
final class ZstdDictionaryTrainerTest {
    /// The combined training sample capacity used by integration tests.
    private static final int SAMPLE_CAPACITY = 128 * 1_024;

    /// The maximum trained dictionary size used by integration tests.
    private static final int DICTIONARY_CAPACITY = 4 * 1_024;

    /// Trains dictionaries in both modes and uses the current result through buffer and channel APIs.
    @Test
    void trainsReusableDictionary() throws Exception {
        ZstdCodec codec = new ZstdCodec();
        ZstdDictionaryTrainer trainer = populatedTrainer();
        assertEquals(SAMPLE_CAPACITY, trainer.sampleCapacity());
        assertEquals(DICTIONARY_CAPACITY, trainer.dictionaryCapacity());
        assertEquals(codec.defaultCompressionLevel(), trainer.compressionLevel());
        assertEquals(256, trainer.sampleCount());
        assertTrue(trainer.sampleBytes() > DICTIONARY_CAPACITY);
        assertEquals(SAMPLE_CAPACITY - trainer.sampleBytes(), trainer.remainingSampleCapacity());

        CompressionDictionary dictionary = trainer.train();
        assertTrue(dictionary.size() > 0);
        assertTrue(dictionary.size() <= DICTIONARY_CAPACITY);
        assertTrue(dictionary.id() >= 0L);
        assertEquals(dictionary.id(), codec.dictionaryId(dictionary.buffer()));

        CompressionDictionary legacyDictionary = trainer.train(true);
        assertTrue(legacyDictionary.size() > 0);
        assertTrue(legacyDictionary.id() >= 0L);
        assertArrayEquals(dictionary.bytes(), trainer.train().bytes());
        assertFalse(java.util.Arrays.equals(dictionary.bytes(), legacyDictionary.bytes()));

        byte[] payload = sample(91);
        CodecOptions options = CodecOptions.builder()
                .set(StandardCodecOptions.DICTIONARY, dictionary)
                .build();

        ByteBuffer source = ByteBuffer.allocateDirect(payload.length);
        source.put(payload).flip();
        ByteBuffer compressed = codec.compress(source, options);
        assertEquals(3, firstLiteralType(codec, compressed.duplicate()));
        assertEquals(0xfc, firstSequenceModes(codec, compressed.duplicate()));
        ByteBuffer decoded = codec.decompress(compressed, payload.length, options);
        byte[] bufferOutput = new byte[decoded.remaining()];
        decoded.get(bufferOutput);
        assertArrayEquals(payload, bufferOutput);

        ByteArrayOutputStream channelCompressed = new ByteArrayOutputStream();
        codec.compress(
                Channels.newChannel(new ByteArrayInputStream(payload)),
                Channels.newChannel(channelCompressed),
                options
        );
        ByteArrayOutputStream channelDecoded = new ByteArrayOutputStream();
        codec.decompress(
                Channels.newChannel(new ByteArrayInputStream(channelCompressed.toByteArray())),
                Channels.newChannel(channelDecoded),
                options
        );
        assertArrayEquals(payload, channelDecoded.toByteArray());

        try (ZstdDecompressCtx context = new ZstdDecompressCtx()) {
            context.loadDict(dictionary.bytes());
            assertArrayEquals(
                    payload,
                    context.decompress(channelCompressed.toByteArray(), payload.length)
            );
        }

        byte[] nativeCompressed;
        try (ZstdCompressCtx context = new ZstdCompressCtx()) {
            context.loadDict(dictionary.bytes());
            nativeCompressed = context.compress(payload);
        }
        ByteArrayOutputStream nativeDecoded = new ByteArrayOutputStream();
        codec.decompress(
                Channels.newChannel(new ByteArrayInputStream(nativeCompressed)),
                Channels.newChannel(nativeDecoded),
                options
        );
        assertArrayEquals(payload, nativeDecoded.toByteArray());
    }

    /// Verifies sample acceptance advances buffers only after capacity checks succeed.
    @Test
    void preservesRejectedSampleState() {
        ZstdDictionaryTrainer trainer = new ZstdDictionaryTrainer(4L, 256L, 1L);
        ByteBuffer accepted = ByteBuffer.wrap(new byte[]{9, 1, 2, 3, 9});
        accepted.position(1);
        accepted.limit(4);
        assertTrue(trainer.tryAddSample(accepted));
        assertEquals(4, accepted.position());
        assertEquals(1, trainer.sampleCount());
        assertEquals(3L, trainer.sampleBytes());

        ByteBuffer rejected = ByteBuffer.wrap(new byte[]{4, 5});
        int rejectedPosition = rejected.position();
        assertFalse(trainer.tryAddSample(rejected));
        assertEquals(rejectedPosition, rejected.position());
        assertEquals(1, trainer.sampleCount());
        assertEquals(3L, trainer.sampleBytes());
        assertThrows(BufferOverflowException.class, () -> trainer.addSample(rejected));
        assertEquals(rejectedPosition, rejected.position());

        assertThrows(IllegalArgumentException.class, () -> trainer.addSample(ByteBuffer.allocate(0)));
    }

    /// Verifies channel samples honor declared boundaries and commit only complete input.
    @Test
    void acceptsChannelSamples() throws Exception {
        ZstdDictionaryTrainer trainer = new ZstdDictionaryTrainer(6L, 256L);
        try (ReadableByteChannel source = Channels.newChannel(
                new ByteArrayInputStream(new byte[]{1, 2, 3, 4})
        )) {
            trainer.addSample(source, 3L);
            ByteBuffer trailing = ByteBuffer.allocate(1);
            assertEquals(1, source.read(trailing));
            trailing.flip();
            assertEquals(4, trailing.get());
        }
        assertEquals(1, trainer.sampleCount());
        assertEquals(3L, trainer.sampleBytes());

        ZstdDictionaryTrainer truncated = new ZstdDictionaryTrainer(6L, 256L);
        try (ReadableByteChannel source = Channels.newChannel(
                new ByteArrayInputStream(new byte[]{1, 2})
        )) {
            assertThrows(EOFException.class, () -> truncated.addSample(source, 3L));
        }
        assertEquals(0, truncated.sampleCount());
        assertEquals(0L, truncated.sampleBytes());
    }

    /// Verifies trainer configuration and native training failures are reported precisely.
    @Test
    void validatesTrainingState() {
        ZstdCodec codec = new ZstdCodec();
        assertThrows(IllegalArgumentException.class, () -> new ZstdDictionaryTrainer(0L, 1L));
        assertThrows(IllegalArgumentException.class, () -> new ZstdDictionaryTrainer(1L, 0L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ZstdDictionaryTrainer((long) Integer.MAX_VALUE + 1L, 1L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ZstdDictionaryTrainer(1L, 1L, codec.minimumCompressionLevel() - 1L)
        );

        ZstdDictionaryTrainer empty = new ZstdDictionaryTrainer(1L, 1L);
        assertThrows(IllegalStateException.class, empty::train);
        empty.addSample(ByteBuffer.wrap(new byte[]{1}));
        ZstdDictionaryTrainingException exception =
                assertThrows(ZstdDictionaryTrainingException.class, empty::train);
        assertTrue(exception.errorCode() != 0L);
    }

    /// Verifies dictionary identifier inspection supports ranges and raw dictionaries without moving source state.
    @Test
    void identifiesDictionaryRanges() {
        ZstdCodec codec = new ZstdCodec();
        ByteBuffer raw = ByteBuffer.allocateDirect(8);
        raw.put(new byte[]{9, 1, 2, 3, 4, 5, 9});
        raw.flip();
        raw.position(1);
        raw.limit(6);
        int position = raw.position();
        int limit = raw.limit();

        assertEquals(CompressionDictionary.UNKNOWN_ID, codec.dictionaryId(raw));
        assertEquals(position, raw.position());
        assertEquals(limit, raw.limit());
    }

    /// Creates a trainer populated with related but distinct text records.
    private static ZstdDictionaryTrainer populatedTrainer() {
        ZstdDictionaryTrainer trainer = new ZstdDictionaryTrainer(SAMPLE_CAPACITY, DICTIONARY_CAPACITY);
        for (int index = 0; index < 256; index++) {
            ByteBuffer sample = index % 2 == 0
                    ? ByteBuffer.wrap(sample(index))
                    : directSample(index);
            trainer.addSample(sample);
            assertEquals(sample.limit(), sample.position());
        }
        return trainer;
    }

    /// Returns the literal-section type from the first compressed block.
    private static int firstLiteralType(ZstdCodec codec, ByteBuffer frame) throws Exception {
        int frameStart = frame.position();
        ZstdStandardFrameInfo info = (ZstdStandardFrameInfo) codec.frameInfo(frame.duplicate());
        int blockOffset = frameStart + info.headerSize();
        int blockHeader = Byte.toUnsignedInt(frame.get(blockOffset))
                | Byte.toUnsignedInt(frame.get(blockOffset + 1)) << 8
                | Byte.toUnsignedInt(frame.get(blockOffset + 2)) << 16;
        if ((blockHeader >>> 1 & 3) != 2) {
            throw new AssertionError("Expected a compressed Zstandard block");
        }
        return Byte.toUnsignedInt(frame.get(blockOffset + 3)) & 3;
    }

    /// Returns the three sequence-table modes from the first compressed block.
    private static int firstSequenceModes(ZstdCodec codec, ByteBuffer frame) throws Exception {
        int frameStart = frame.position();
        ZstdStandardFrameInfo info = (ZstdStandardFrameInfo) codec.frameInfo(frame.duplicate());
        int blockOffset = frameStart + info.headerSize();
        int blockHeader = Byte.toUnsignedInt(frame.get(blockOffset))
                | Byte.toUnsignedInt(frame.get(blockOffset + 1)) << 8
                | Byte.toUnsignedInt(frame.get(blockOffset + 2)) << 16;
        if ((blockHeader >>> 1 & 3) != 2) {
            throw new AssertionError("Expected a compressed Zstandard block");
        }

        int position = blockOffset + 3;
        position += encodedLiteralSectionSize(frame, position);
        int firstCount = Byte.toUnsignedInt(frame.get(position++));
        int sequenceCount;
        if (firstCount < 128) {
            sequenceCount = firstCount;
        } else if (firstCount < 255) {
            sequenceCount = (firstCount - 128) << 8
                    | Byte.toUnsignedInt(frame.get(position++));
        } else {
            sequenceCount = (Byte.toUnsignedInt(frame.get(position))
                    | Byte.toUnsignedInt(frame.get(position + 1)) << 8) + 0x7f00;
            position += 2;
        }
        if (sequenceCount == 0) {
            throw new AssertionError("Expected Zstandard sequences");
        }
        return Byte.toUnsignedInt(frame.get(position));
    }

    /// Returns the encoded byte size of one literal section.
    private static int encodedLiteralSectionSize(ByteBuffer frame, int offset) {
        int first = Byte.toUnsignedInt(frame.get(offset));
        int type = first & 3;
        int sizeFormat = first >>> 2 & 3;
        if (type <= 1) {
            int headerSize;
            int regeneratedSize;
            if (sizeFormat == 0 || sizeFormat == 2) {
                headerSize = 1;
                regeneratedSize = first >>> 3;
            } else if (sizeFormat == 1) {
                headerSize = 2;
                regeneratedSize = (first
                        | Byte.toUnsignedInt(frame.get(offset + 1)) << 8) >>> 4;
            } else {
                headerSize = 3;
                regeneratedSize = (first
                        | Byte.toUnsignedInt(frame.get(offset + 1)) << 8
                        | Byte.toUnsignedInt(frame.get(offset + 2)) << 16) >>> 4;
            }
            return headerSize + (type == 0 ? regeneratedSize : 1);
        }

        if (sizeFormat <= 1) {
            int header = first
                    | Byte.toUnsignedInt(frame.get(offset + 1)) << 8
                    | Byte.toUnsignedInt(frame.get(offset + 2)) << 16;
            return 3 + (header >>> 14 & 0x3ff);
        }
        if (sizeFormat == 2) {
            long header = Integer.toUnsignedLong(
                    first
                            | Byte.toUnsignedInt(frame.get(offset + 1)) << 8
                            | Byte.toUnsignedInt(frame.get(offset + 2)) << 16
                            | frame.get(offset + 3) << 24
            );
            return 4 + (int) (header >>> 18 & 0x3fff);
        }
        long header = Integer.toUnsignedLong(
                first
                        | Byte.toUnsignedInt(frame.get(offset + 1)) << 8
                        | Byte.toUnsignedInt(frame.get(offset + 2)) << 16
                        | frame.get(offset + 3) << 24
        ) | (long) Byte.toUnsignedInt(frame.get(offset + 4)) << 32;
        return 5 + (int) (header >>> 22 & 0x3ffff);
    }

    /// Returns one direct-buffer sample.
    private static ByteBuffer directSample(int index) {
        byte[] bytes = sample(index);
        ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes).flip();
        return buffer;
    }

    /// Returns one related training or payload record.
    private static byte[] sample(int index) {
        return (
                "customer-record:" + (index % 31)
                        + ";region:" + (index % 7)
                        + ";event:archive-compression-dictionary-training"
                        + ";sequence:" + index
                        + ";payload:0123456789abcdef0123456789abcdef"
        ).repeat(3).getBytes(StandardCharsets.UTF_8);
    }
}
