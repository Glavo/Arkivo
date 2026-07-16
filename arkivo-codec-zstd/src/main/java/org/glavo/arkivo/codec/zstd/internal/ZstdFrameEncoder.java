// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.glavo.arkivo.codec.zstd.ZstdDictionary;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/// Encodes Zstandard frame headers, block framing, and optional checksum trailers.
@NotNullByDefault
final class ZstdFrameEncoder {
    /// Standard Zstandard frame magic in little-endian byte order.
    private static final byte @Unmodifiable [] FRAME_MAGIC = {0x28, (byte) 0xb5, 0x2f, (byte) 0xfd};

    /// Encodes a frame header for the configured source pledge and dictionary.
    static byte[] header(ZstdEncoderParameters parameters, boolean magicless) {
        long contentSize = parameters.contentSize()
                ? parameters.pledgedSourceSize()
                : -1L;
        long dictionaryId = parameters.frameDictionaryId();
        boolean singleSegment = contentSize >= 0L
                && contentSize <= (1L << parameters.windowLog());
        int contentSizeFlag = contentSizeFlag(contentSize, singleSegment);
        int dictionaryFlag = dictionaryFlag(dictionaryId);
        int descriptor = contentSizeFlag << 6
                | (singleSegment ? 1 << 5 : 0)
                | (parameters.checksum() ? 1 << 2 : 0)
                | dictionaryFlag;

        ByteArrayOutputStream output = new ByteArrayOutputStream(18);
        if (!magicless) {
            output.writeBytes(FRAME_MAGIC);
        }
        output.write(descriptor);
        if (!singleSegment) {
            output.write((parameters.windowLog() - 10) << 3);
        }
        writeDictionaryId(output, dictionaryId, dictionaryFlag);
        writeContentSize(output, contentSize, contentSizeFlag, singleSegment);
        return output.toByteArray();
    }

    /// Encodes one independently scheduled multi-block job.
    static JobEncoding job(
            @Unmodifiable List<BlockInput> blocks,
            byte @Unmodifiable [] prefix,
            long frameOffset,
            boolean lastJob,
            ZstdEncoderParameters parameters
    ) {
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("A Zstandard compression job requires a block");
        }

        ZstdBlockEncoder encoder = new ZstdBlockEncoder();
        encoder.resetJob(parameters, prefix, frameOffset);
        ArrayList<byte @Unmodifiable []> encoded = new ArrayList<>(blocks.size());
        for (int index = 0; index < blocks.size(); index++) {
            BlockInput block = blocks.get(index);
            boolean lastBlock = lastJob && index + 1 == blocks.size();
            encoded.add(encoder.encode(
                    block.bytes(),
                    block.bytes().length,
                    lastBlock,
                    parameters,
                    block.longDistanceMatches()
            ));
        }
        return new JobEncoding(List.copyOf(encoded));
    }

    /// Encodes the low 32 bits of an XXH64 frame checksum.
    static byte[] checksum(ZstdXXHash64 checksum) {
        long value = checksum.digest();
        return new byte[]{
                (byte) value,
                (byte) (value >>> 8),
                (byte) (value >>> 16),
                (byte) (value >>> 24)
        };
    }

    /// Selects the frame-content-size flag for one header.
    private static int contentSizeFlag(long contentSize, boolean singleSegment) {
        if (contentSize < 0L) {
            return 0;
        }
        if (singleSegment && contentSize <= 255L) {
            return 0;
        }
        if (contentSize <= 65_791L) {
            return 1;
        }
        if (contentSize <= 0xffff_ffffL) {
            return 2;
        }
        return 3;
    }

    /// Selects the dictionary-identifier flag for one header.
    private static int dictionaryFlag(long dictionaryId) {
        if (dictionaryId == ZstdDictionary.NO_DICTIONARY_ID) {
            return 0;
        }
        if (dictionaryId <= 0xffL) {
            return 1;
        }
        if (dictionaryId <= 0xffffL) {
            return 2;
        }
        if (dictionaryId <= 0xffff_ffffL) {
            return 3;
        }
        throw new IllegalArgumentException("Zstandard dictionary identifier exceeds 32 bits");
    }

    /// Writes a little-endian dictionary identifier according to its flag.
    private static void writeDictionaryId(
            ByteArrayOutputStream output,
            long dictionaryId,
            int dictionaryFlag
    ) {
        int bytes = switch (dictionaryFlag) {
            case 0 -> 0;
            case 1 -> 1;
            case 2 -> 2;
            case 3 -> 4;
            default -> throw new AssertionError(dictionaryFlag);
        };
        writeLittleEndian(output, dictionaryId, bytes);
    }

    /// Writes a frame content size according to its descriptor flag.
    private static void writeContentSize(
            ByteArrayOutputStream output,
            long contentSize,
            int contentSizeFlag,
            boolean singleSegment
    ) {
        if (contentSize < 0L || contentSizeFlag == 0 && !singleSegment) {
            return;
        }
        int bytes = switch (contentSizeFlag) {
            case 0 -> 1;
            case 1 -> 2;
            case 2 -> 4;
            case 3 -> 8;
            default -> throw new AssertionError(contentSizeFlag);
        };
        long encoded = contentSizeFlag == 1 ? contentSize - 256L : contentSize;
        writeLittleEndian(output, encoded, bytes);
    }

    /// Writes the requested number of low-order bytes in little-endian order.
    private static void writeLittleEndian(ByteArrayOutputStream output, long value, int bytes) {
        for (int index = 0; index < bytes; index++) {
            output.write((int) (value >>> (index * 8)));
        }
    }

    /// Holds one immutable uncompressed block and its preplanned long-distance matches.
    ///
    /// @param bytes uncompressed block bytes
    /// @param longDistanceMatches verified frame-history matches for the block
    record BlockInput(
            byte @Unmodifiable [] bytes,
            @Unmodifiable List<ZstdLongDistanceMatcher.Match> longDistanceMatches
    ) {
    }

    /// Holds the ordered encoded blocks produced by one parallel job.
    ///
    /// @param blocks encoded blocks including their block headers
    record JobEncoding(@Unmodifiable List<byte @Unmodifiable []> blocks) {
    }

    /// Creates no instances.
    private ZstdFrameEncoder() {
    }
}
