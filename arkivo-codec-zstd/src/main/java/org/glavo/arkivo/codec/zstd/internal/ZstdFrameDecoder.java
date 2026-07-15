// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDictionary;
import org.glavo.arkivo.codec.spi.StandardCodecOptionSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.util.Objects;

/// Parses Zstandard framing and incrementally decodes one bounded block at a time.
@NotNullByDefault
final class ZstdFrameDecoder {
    /// Standard frame magic in little-endian form.
    private static final long FRAME_MAGIC = 0xfd2f_b528L;

    /// First skippable-frame magic.
    private static final long SKIPPABLE_MAGIC = 0x184d_2a50L;

    /// Shared skippable-frame magic mask.
    private static final long SKIPPABLE_MASK = 0xffff_fff0L;


    /// Buffered compressed input.
    private final ZstdChannelInput input;

    /// Configured dictionary.
    private final ZstdDictionary dictionary;

    /// Configured maximum frame window, or the unknown sentinel.
    private final long maximumWindowSize;

    /// Whether standard frame magic is omitted.
    private final boolean magicless;

    /// Whether present frame checksums are verified.
    private final boolean verifyChecksums;

    /// Current block decoder, or null at a frame boundary.
    private @Nullable ZstdBlockDecoder blockDecoder;

    /// Current frame checksum, or null when disabled.
    private @Nullable ZstdXXHash64 checksum;

    /// Declared current-frame content size, or the unknown sentinel.
    private long declaredContentSize = CompressionCodec.UNKNOWN_SIZE;

    /// Maximum decoded block size for the current frame.
    private int maximumBlockSize;

    /// Whether the current standard frame carries a checksum.
    private boolean checksumPresent;

    /// Creates a frame decoder.
    ZstdFrameDecoder(
            ZstdChannelInput input,
            ZstdDictionary dictionary,
            long maximumWindowSize,
            boolean magicless,
            boolean verifyChecksums
    ) {
        this.input = input;
        this.dictionary = dictionary;
        this.maximumWindowSize = maximumWindowSize;
        this.magicless = magicless;
        this.verifyChecksums = verifyChecksums;
    }

    /// Decodes the next block or complete skippable frame.
    Step readStep() throws IOException {
        if (blockDecoder == null) {
            @Nullable Step headerStep = beginFrame();
            if (headerStep != null) {
                return headerStep;
            }
        }

        ZstdBlockDecoder decoder = Objects.requireNonNull(blockDecoder);
        int blockHeader = (int) input.readLittleEndian(3);
        boolean lastBlock = (blockHeader & 1) != 0;
        int blockType = (blockHeader >>> 1) & 3;
        int blockSize = blockHeader >>> 3;
        if (blockType == 3) {
            throw new IOException("Reserved Zstandard block type");
        }
        if (blockSize > maximumBlockSize) {
            throw new IOException("Zstandard block exceeds the frame block-size limit");
        }

        byte[] output = switch (blockType) {
            case 0 -> decoder.decodeRaw(input.readFully(blockSize));
            case 1 -> decoder.decodeRle(input.readRequired(), blockSize);
            case 2 -> decoder.decodeCompressed(input.readFully(blockSize));
            default -> throw new AssertionError(blockType);
        };
        if (output.length > maximumBlockSize) {
            throw new IOException("Decoded Zstandard block exceeds the frame block-size limit");
        }
        if (checksum != null) {
            checksum.update(output, 0, output.length);
        }

        if (!lastBlock) {
            return new Step(output, false, false);
        }
        finishStandardFrame(decoder);
        blockDecoder = null;
        return new Step(output, true, false);
    }

    /// Parses the next frame header, returning an immediate step for end-of-input or a skippable frame.
    private @Nullable Step beginFrame() throws IOException {
        int first = input.read();
        if (first < 0) {
            return new Step(new byte[0], false, true);
        }
        int descriptor;
        if (magicless) {
            descriptor = first;
        } else {
            long magic = first
                    | (long) input.readRequired() << 8
                    | (long) input.readRequired() << 16
                    | (long) input.readRequired() << 24;
            if ((magic & SKIPPABLE_MASK) == SKIPPABLE_MAGIC) {
                long payloadSize = input.readLittleEndian(4);
                input.skipFully(payloadSize);
                return new Step(new byte[0], true, false);
            }
            if (magic != FRAME_MAGIC) {
                throw new IOException("Invalid Zstandard frame");
            }
            descriptor = input.readRequired();
        }
        if ((descriptor & 0x18) != 0) {
            throw new IOException("Reserved Zstandard frame descriptor bits are set");
        }
        boolean singleSegment = (descriptor & 0x20) != 0;
        checksumPresent = (descriptor & 0x04) != 0;
        int dictionaryIdFlag = descriptor & 3;
        int dictionaryIdSize = dictionaryIdFlag == 0 ? 0 : 1 << (dictionaryIdFlag - 1);
        int contentSizeFlag = descriptor >>> 6;
        int contentSizeFieldSize = switch (contentSizeFlag) {
            case 0 -> singleSegment ? 1 : 0;
            case 1 -> 2;
            case 2 -> 4;
            case 3 -> 8;
            default -> throw new AssertionError(contentSizeFlag);
        };

        long windowSize;
        if (singleSegment) {
            windowSize = 0L;
        } else {
            int windowDescriptor = input.readRequired();
            int windowLog = (windowDescriptor >>> 3) + 10;
            long windowBase = 1L << windowLog;
            windowSize = windowBase + (windowBase >>> 3) * (windowDescriptor & 7);
        }

        long dictionaryId = dictionaryIdSize == 0
                ? CompressionDictionary.UNKNOWN_ID
                : input.readLittleEndian(dictionaryIdSize);
        if (dictionaryId != CompressionDictionary.UNKNOWN_ID && dictionary.id() != dictionaryId) {
            throw new IOException("Zstandard frame requires dictionary " + dictionaryId);
        }

        if (contentSizeFieldSize == 0) {
            declaredContentSize = CompressionCodec.UNKNOWN_SIZE;
        } else {
            long contentSize = input.readLittleEndian(contentSizeFieldSize);
            if (contentSizeFieldSize == 8 && contentSize < 0L) {
                throw new IOException("Zstandard frame content size exceeds the Java long range");
            }
            declaredContentSize = contentSizeFieldSize == 2 ? contentSize + 256L : contentSize;
        }
        if (singleSegment) {
            windowSize = declaredContentSize;
        }
        StandardCodecOptionSupport.requireWindowSize(maximumWindowSize, windowSize);
        if (windowSize < 0L) {
            throw new IOException("Invalid Zstandard frame window");
        }

        maximumBlockSize = (int) Math.min(ZstdBlockDecoder.MAX_BLOCK_SIZE, windowSize);
        if (singleSegment && declaredContentSize == 0L) {
            maximumBlockSize = 0;
        }
        blockDecoder = new ZstdBlockDecoder(windowSize, dictionary);
        checksum = checksumPresent && verifyChecksums ? new ZstdXXHash64() : null;
        return null;
    }

    /// Validates content size and optional checksum at the end of a standard frame.
    private void finishStandardFrame(ZstdBlockDecoder decoder) throws IOException {
        long actualContentSize = decoder.frameSize();
        if (declaredContentSize != CompressionCodec.UNKNOWN_SIZE
                && actualContentSize != declaredContentSize) {
            throw new IOException("Zstandard frame content size mismatch");
        }
        if (checksumPresent) {
            long stored = input.readLittleEndian(4);
            if (verifyChecksums) {
                long actual = Objects.requireNonNull(checksum).digest() & 0xffff_ffffL;
                if (stored != actual) {
                    throw new IOException("Zstandard frame checksum mismatch");
                }
            }
        }
        checksum = null;
    }

    /// Represents one incremental frame-decoder result.
    record Step(byte @Unmodifiable [] output, boolean frameFinished, boolean endOfInput) {
        /// Creates an incremental result.
        Step {
        }
    }
}
