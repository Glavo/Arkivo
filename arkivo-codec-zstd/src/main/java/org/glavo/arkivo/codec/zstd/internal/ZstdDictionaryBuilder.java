// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;
import org.glavo.arkivo.internal.ByteArrayAccess;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/// Builds deterministic formatted Zstandard dictionaries from packed training samples.
@NotNullByDefault
public final class ZstdDictionaryBuilder {
    /// Minimum formatted dictionary capacity accepted by the Zstandard dictionary API.
    public static final int MINIMUM_DICTIONARY_CAPACITY = 256;

    /// Minimum combined sample size required for content and repeated offsets.
    public static final int MINIMUM_SAMPLE_BYTES = 8;

    /// Formatted dictionary magic in little-endian representation.
    private static final long DICTIONARY_MAGIC = 0xec30_a437L;

    /// Lowest generated dictionary identifier.
    private static final long MINIMUM_DICTIONARY_ID = 32_768L;

    /// Number of possible generated dictionary identifiers.
    private static final long DICTIONARY_ID_RANGE = (1L << 31) - MINIMUM_DICTIONARY_ID;

    /// Candidate segment size used by the current content selector.
    private static final int SEGMENT_SIZE = 64;

    /// Anchor stride used by the bounded frequency sketch.
    private static final int ANCHOR_STRIDE = 4;

    /// Number of counters in the bounded anchor-frequency sketch.
    private static final int FREQUENCY_TABLE_SIZE = 1 << 16;

    /// Bit mask for the bounded anchor-frequency sketch.
    private static final int FREQUENCY_TABLE_MASK = FREQUENCY_TABLE_SIZE - 1;

    /// Number of offset-code symbols accepted by the dictionary format.
    private static final int OFFSET_SYMBOL_COUNT = 32;

    /// Number of match-length symbols accepted by the dictionary format.
    private static final int MATCH_LENGTH_SYMBOL_COUNT = 53;

    /// Number of literal-length symbols accepted by the dictionary format.
    private static final int LITERAL_LENGTH_SYMBOL_COUNT = 36;

    /// Dictionary offset-code FSE table logarithm.
    private static final int OFFSET_TABLE_LOG = 5;

    /// Dictionary match-length FSE table logarithm.
    private static final int MATCH_LENGTH_TABLE_LOG = 6;

    /// Dictionary literal-length FSE table logarithm.
    private static final int LITERAL_LENGTH_TABLE_LOG = 6;

    /// Number of offset symbols covered by the fallback table.
    private static final int FALLBACK_OFFSET_SYMBOL_COUNT = 31;

    /// Builds one current or legacy-oriented formatted dictionary.
    ///
    /// @param samples packed sample bytes
    /// @param sampleSizes size of each packed sample
    /// @param dictionaryCapacity maximum dictionary size
    /// @param compressionLevel dictionary optimization level
    /// @param legacy whether to use the legacy-oriented content selector
    /// @return a standard formatted dictionary no larger than {@code dictionaryCapacity}
    /// @throws NullPointerException if {@code samples} or {@code sampleSizes} is {@code null}
    /// @throws IllegalArgumentException if capacities or sample boundaries are inconsistent or insufficient
    public static byte[] build(
            byte[] samples,
            int[] sampleSizes,
            int dictionaryCapacity,
            int compressionLevel,
            boolean legacy
    ) {
        if (dictionaryCapacity < MINIMUM_DICTIONARY_CAPACITY) {
            throw new IllegalArgumentException("dictionaryCapacity is too small");
        }
        int totalSize = totalSize(sampleSizes);
        if (totalSize != samples.length || totalSize < MINIMUM_SAMPLE_BYTES) {
            throw new IllegalArgumentException("Insufficient or inconsistent Zstandard training samples");
        }

        byte @Nullable [] huffmanDescription =
                ZstdLiteralEncoder.buildTableDescription(samples);
        SequenceFrequencies preliminaryFrequencies = collectSequenceFrequencies(
                samples,
                sampleSizes,
                compressionLevel,
                new byte[0]
        );
        byte[] preliminaryEntropy =
                buildEntropySection(huffmanDescription, preliminaryFrequencies);
        byte[] preliminaryContent = selectContent(
                samples,
                sampleSizes,
                contentCapacity(dictionaryCapacity, preliminaryEntropy.length),
                compressionLevel,
                legacy
        );
        SequenceFrequencies trainedFrequencies = collectSequenceFrequencies(
                samples,
                sampleSizes,
                compressionLevel,
                preliminaryContent
        );
        byte[] entropy = buildEntropySection(huffmanDescription, trainedFrequencies);
        byte[] content = selectContent(
                samples,
                sampleSizes,
                contentCapacity(dictionaryCapacity, entropy.length),
                compressionLevel,
                legacy
        );
        if (content.length < MINIMUM_SAMPLE_BYTES) {
            throw new IllegalArgumentException("Insufficient Zstandard dictionary content");
        }

        long dictionaryId = dictionaryId(content);
        ByteArrayOutputStream dictionary =
                new ByteArrayOutputStream(8 + entropy.length + content.length);
        writeLittleEndianInt(dictionary, DICTIONARY_MAGIC);
        writeLittleEndianInt(dictionary, dictionaryId);
        dictionary.writeBytes(entropy);
        dictionary.writeBytes(content);
        return dictionary.toByteArray();
    }

    /// Returns the sum of non-negative sample sizes with overflow checking.
    private static int totalSize(int[] sampleSizes) {
        int total = 0;
        for (int sampleSize : sampleSizes) {
            if (sampleSize <= 0) {
                throw new IllegalArgumentException("sampleSizes must be positive");
            }
            total = Math.addExact(total, sampleSize);
        }
        return total;
    }

    /// Builds the dictionary entropy section from Huffman metadata and sequence statistics.
    private static byte[] buildEntropySection(
            byte @Nullable [] huffmanDescription,
            SequenceFrequencies sequenceFrequencies
    ) {
        ByteArrayOutputStream entropy = new ByteArrayOutputStream(160);
        if (huffmanDescription != null) {
            entropy.writeBytes(huffmanDescription);
        } else {
            writeFallbackHuffmanTable(entropy);
        }
        writeFseTable(entropy, trainedDistribution(
                sequenceFrequencies.offsetCodes(),
                FALLBACK_OFFSET_SYMBOL_COUNT,
                OFFSET_TABLE_LOG
        ));
        writeFseTable(entropy, trainedDistribution(
                sequenceFrequencies.matchLengthCodes(),
                MATCH_LENGTH_SYMBOL_COUNT,
                MATCH_LENGTH_TABLE_LOG
        ));
        writeFseTable(entropy, trainedDistribution(
                sequenceFrequencies.literalLengthCodes(),
                LITERAL_LENGTH_SYMBOL_COUNT,
                LITERAL_LENGTH_TABLE_LOG
        ));
        writeLittleEndianInt(entropy, 1L);
        writeLittleEndianInt(entropy, 4L);
        writeLittleEndianInt(entropy, 8L);
        return entropy.toByteArray();
    }

    /// Returns the space available for dictionary content after framing and entropy metadata.
    private static int contentCapacity(int dictionaryCapacity, int entropySize) {
        int contentCapacity = dictionaryCapacity - 8 - entropySize;
        if (contentCapacity < MINIMUM_SAMPLE_BYTES) {
            throw new IllegalArgumentException(
                    "dictionaryCapacity cannot hold a formatted dictionary"
            );
        }
        return contentCapacity;
    }

    /// Selects dictionary content for one known post-entropy capacity.
    private static byte[] selectContent(
            byte[] samples,
            int[] sampleSizes,
            int capacity,
            int compressionLevel,
            boolean legacy
    ) {
        int selectedCapacity = Math.min(capacity, samples.length);
        return legacy
                ? selectLegacyContent(samples, selectedCapacity)
                : selectCurrentContent(
                        samples,
                        sampleSizes,
                        selectedCapacity,
                        compressionLevel
                );
    }

    /// Collects sequence-code frequencies while preserving block-local and sample-local boundaries.
    private static SequenceFrequencies collectSequenceFrequencies(
            byte[] samples,
            int[] sampleSizes,
            int compressionLevel,
            byte @Unmodifiable [] dictionaryContent
    ) {
        int[] offsetCodes = new int[OFFSET_SYMBOL_COUNT];
        int[] matchLengthCodes = new int[MATCH_LENGTH_SYMBOL_COUNT];
        int[] literalLengthCodes = new int[LITERAL_LENGTH_SYMBOL_COUNT];
        ZstdEncoderParameters parameters =
                ZstdEncoderParameters.forDictionaryTraining(compressionLevel);
        int distanceLimit = ZstdBlockEncoder.matchDistanceLimit(parameters);
        byte @Unmodifiable [] dictionaryHistory = dictionaryContent.length <= distanceLimit
                ? dictionaryContent
                : Arrays.copyOfRange(
                        dictionaryContent,
                        dictionaryContent.length - distanceLimit,
                        dictionaryContent.length
                );
        int sampleOffset = 0;
        for (int sampleSize : sampleSizes) {
            byte @Unmodifiable [] history = dictionaryHistory;
            ZstdBlockEncoder.RepeatedOffsets repeated =
                    new ZstdBlockEncoder.RepeatedOffsets(1, 4, 8);
            int samplePosition = 0;
            while (samplePosition < sampleSize) {
                int blockLength = Math.min(
                        parameters.blockSize(),
                        sampleSize - samplePosition
                );
                byte[] block = Arrays.copyOfRange(
                        samples,
                        sampleOffset + samplePosition,
                        sampleOffset + samplePosition + blockLength
                );
                @Unmodifiable List<ZstdMatchParser.Match> matches =
                        ZstdMatchParser.parse(
                                block,
                                blockLength,
                                history,
                                distanceLimit,
                                parameters,
                                List.of()
                        );
                int literalStart = 0;
                for (ZstdMatchParser.Match match : matches) {
                    int literalLength = match.position() - literalStart;
                    literalLengthCodes[
                            ZstdBlockEncoder.literalLengthCode(literalLength)
                    ]++;
                    matchLengthCodes[
                            ZstdBlockEncoder.matchLengthCode(match.length())
                    ]++;
                    ZstdBlockEncoder.OffsetEncoding offset =
                            ZstdBlockEncoder.selectOffset(
                                    match.distance(),
                                    literalLength,
                                    repeated,
                                    true
                            );
                    int offsetCode =
                            Long.SIZE - 1 - Long.numberOfLeadingZeros(offset.value());
                    if (offsetCode >= offsetCodes.length) {
                        throw new IllegalStateException(
                                "Dictionary sample produced an invalid Zstandard offset code"
                        );
                    }
                    offsetCodes[offsetCode]++;
                    repeated = offset.repeatedOffsets();
                    literalStart = match.position() + match.length();
                }
                history = appendHistory(history, block, distanceLimit);
                samplePosition += blockLength;
            }
            sampleOffset += sampleSize;
        }
        return new SequenceFrequencies(
                offsetCodes,
                matchLengthCodes,
                literalLengthCodes
        );
    }

    /// Appends one analyzed block to a bounded immutable sample-history suffix.
    private static byte @Unmodifiable [] appendHistory(
            byte @Unmodifiable [] history,
            byte[] block,
            int historyLimit
    ) {
        if (block.length >= historyLimit) {
            return Arrays.copyOfRange(
                    block,
                    block.length - historyLimit,
                    block.length
            );
        }
        int retained = Math.min(history.length, historyLimit - block.length);
        byte[] updated = new byte[retained + block.length];
        System.arraycopy(
                history,
                history.length - retained,
                updated,
                0,
                retained
        );
        System.arraycopy(block, 0, updated, retained, block.length);
        return updated;
    }

    /// Normalizes observed sequence frequencies or returns the full-alphabet fallback table.
    private static FseDistribution trainedDistribution(
            int[] frequencies,
            int fallbackSymbolCount,
            int tableLog
    ) {
        int total = 0;
        int maximumSymbol = -1;
        int distinctSymbols = 0;
        for (int symbol = 0; symbol < frequencies.length; symbol++) {
            int frequency = frequencies[symbol];
            total = Math.addExact(total, frequency);
            if (frequency != 0) {
                maximumSymbol = symbol;
                distinctSymbols++;
            }
        }
        if (total == 0) {
            return uniformDistribution(fallbackSymbolCount, tableLog);
        }
        if (distinctSymbols > 1 << tableLog) {
            throw new IllegalStateException(
                    "Dictionary sequence alphabet exceeds its Zstandard FSE table"
            );
        }
        int symbolCount = maximumSymbol + 1;
        return new FseDistribution(
                ZstdSequenceEntropy.normalize(
                        frequencies,
                        symbolCount,
                        total,
                        tableLog
                ),
                tableLog
        );
    }

    /// Selects the packed suffix used by the legacy-oriented mode.
    private static byte[] selectLegacyContent(byte[] samples, int capacity) {
        return Arrays.copyOfRange(samples, samples.length - capacity, samples.length);
    }

    /// Selects high-frequency, non-overlapping sample segments with deterministic tie breaking.
    private static byte[] selectCurrentContent(
            byte[] samples,
            int[] sampleSizes,
            int capacity,
            int compressionLevel
    ) {
        int segmentSize = compressionLevel < 0 ? 32 : compressionLevel >= 10 ? 128 : SEGMENT_SIZE;
        int segmentStride = segmentSize >>> 1;
        int[] frequencies = new int[FREQUENCY_TABLE_SIZE];
        int sampleOffset = 0;
        for (int sampleSize : sampleSizes) {
            int limit = sampleOffset + sampleSize - 8;
            for (int position = sampleOffset; position <= limit; position += ANCHOR_STRIDE) {
                int index = anchorIndex(samples, position);
                if (frequencies[index] != Integer.MAX_VALUE) {
                    frequencies[index]++;
                }
            }
            sampleOffset += sampleSize;
        }

        int candidateLimit = Math.max(16, Math.min(4_096, capacity / segmentSize * 4));
        PriorityQueue<Candidate> best = new PriorityQueue<>(candidateLimit, Candidate.WORST_FIRST);
        sampleOffset = 0;
        for (int sampleIndex = 0; sampleIndex < sampleSizes.length; sampleIndex++) {
            int sampleSize = sampleSizes[sampleIndex];
            int segmentLength = Math.min(segmentSize, sampleSize);
            int last = sampleOffset + sampleSize - segmentLength;
            for (int position = sampleOffset; position <= last; position += segmentStride) {
                Candidate candidate = candidate(
                        samples,
                        sampleIndex,
                        sampleOffset,
                        position,
                        segmentLength,
                        frequencies
                );
                if (best.size() < candidateLimit) {
                    best.add(candidate);
                } else if (Candidate.BEST_FIRST.compare(candidate, best.element()) < 0) {
                    best.remove();
                    best.add(candidate);
                }
            }
            if (last >= sampleOffset && (last - sampleOffset) % segmentStride != 0) {
                Candidate candidate = candidate(
                        samples,
                        sampleIndex,
                        sampleOffset,
                        last,
                        segmentLength,
                        frequencies
                );
                if (best.size() < candidateLimit) {
                    best.add(candidate);
                } else if (Candidate.BEST_FIRST.compare(candidate, best.element()) < 0) {
                    best.remove();
                    best.add(candidate);
                }
            }
            sampleOffset += sampleSize;
        }

        List<Candidate> ranked = new ArrayList<>(best);
        ranked.sort(Candidate.BEST_FIRST);
        List<Candidate> selected = new ArrayList<>();
        Set<Long> fingerprints = new HashSet<>();
        int selectedBytes = 0;
        for (Candidate candidate : ranked) {
            if (selectedBytes + candidate.length() > capacity
                    || !fingerprints.add(candidate.fingerprint())
                    || overlapsSelected(candidate, selected)) {
                continue;
            }
            selected.add(candidate);
            selectedBytes += candidate.length();
        }

        selected.sort(Candidate.WORST_FIRST);
        byte[] content = new byte[capacity];
        int fallbackSize = capacity - selectedBytes;
        System.arraycopy(samples, samples.length - fallbackSize, content, 0, fallbackSize);
        int output = fallbackSize;
        for (Candidate candidate : selected) {
            System.arraycopy(samples, candidate.offset(), content, output, candidate.length());
            output += candidate.length();
        }
        return content;
    }

    /// Builds one scored segment candidate.
    private static Candidate candidate(
            byte[] samples,
            int sampleIndex,
            int sampleOffset,
            int offset,
            int length,
            int[] frequencies
    ) {
        long score = 0L;
        int limit = offset + length - 8;
        for (int position = offset; position <= limit; position += ANCHOR_STRIDE) {
            score += frequencies[anchorIndex(samples, position)];
        }
        return new Candidate(
                sampleIndex,
                offset - sampleOffset,
                offset,
                length,
                score,
                fingerprint(samples, offset, length)
        );
    }

    /// Returns whether a candidate substantially overlaps a selected segment from the same sample.
    private static boolean overlapsSelected(Candidate candidate, List<Candidate> selected) {
        for (Candidate existing : selected) {
            if (candidate.sampleIndex() != existing.sampleIndex()) {
                continue;
            }
            int start = Math.max(candidate.samplePosition(), existing.samplePosition());
            int end = Math.min(
                    candidate.samplePosition() + candidate.length(),
                    existing.samplePosition() + existing.length()
            );
            if (end - start >= Math.min(candidate.length(), existing.length()) / 2) {
                return true;
            }
        }
        return false;
    }

    /// Returns a bounded frequency-table index for an eight-byte anchor.
    private static int anchorIndex(byte[] source, int offset) {
        long value = ByteArrayAccess.readLongLittleEndian(source, offset);
        value ^= value >>> 33;
        value *= 0xff51_afd7_ed55_8ccdL;
        value ^= value >>> 33;
        return (int) value & FREQUENCY_TABLE_MASK;
    }

    /// Returns a stable fingerprint for one candidate segment.
    private static long fingerprint(byte[] source, int offset, int length) {
        long hash = 0x9e37_79b1_85eb_ca87L ^ length;
        for (int index = 0; index < length; index++) {
            hash ^= Byte.toUnsignedLong(source[offset + index]);
            hash *= 0x100_0000_01b3L;
        }
        return hash;
    }

    /// Writes a fallback Huffman tree covering the complete ASCII range.
    private static void writeFallbackHuffmanTable(ByteArrayOutputStream output) {
        output.write(254);
        for (int index = 0; index < 63; index++) {
            output.write(0x11);
        }
        output.write(0x10);
    }

    /// Creates a positive near-uniform normalized distribution with the requested table size.
    private static FseDistribution uniformDistribution(int symbolCount, int tableLog) {
        int[] normalized = new int[symbolCount];
        Arrays.fill(normalized, 1);
        normalized[0] += (1 << tableLog) - symbolCount;
        return new FseDistribution(normalized, tableLog);
    }

    /// Writes one normalized FSE distribution in forward bit order.
    private static void writeFseTable(ByteArrayOutputStream output, FseDistribution distribution) {
        output.writeBytes(ZstdEntropy.encodeFseTableDescription(
                distribution.normalized(),
                distribution.normalized().length,
                distribution.tableLog()
        ));
    }

    /// Derives a compliant non-zero dictionary identifier from dictionary content.
    private static long dictionaryId(byte[] content) {
        ZstdXXHash64 hash = new ZstdXXHash64();
        hash.update(content, 0, content.length);
        return Long.remainderUnsigned(hash.digest(), DICTIONARY_ID_RANGE) + MINIMUM_DICTIONARY_ID;
    }

    /// Writes the low 32 bits of a little-endian integer.
    private static void writeLittleEndianInt(ByteArrayOutputStream output, long value) {
        for (int index = 0; index < 4; index++) {
            output.write((int) (value >>> (index * 8)));
        }
    }

    /// Describes one normalized finite-state distribution.
    ///
    /// @param normalized normalized counts
    /// @param tableLog decoding-table logarithm
    private record FseDistribution(int @Unmodifiable [] normalized, int tableLog) {
        /// Creates immutable distribution metadata.
        private FseDistribution {
        }
    }

    /// Holds sample-derived frequencies for all three sequence-code alphabets.
    ///
    /// @param offsetCodes offset-code frequencies
    /// @param matchLengthCodes match-length-code frequencies
    /// @param literalLengthCodes literal-length-code frequencies
    private record SequenceFrequencies(
            int @Unmodifiable [] offsetCodes,
            int @Unmodifiable [] matchLengthCodes,
            int @Unmodifiable [] literalLengthCodes
    ) {
    }

    /// Describes one scored dictionary-content candidate.
    ///
    /// @param sampleIndex source sample index
    /// @param samplePosition position within the source sample
    /// @param offset absolute position in packed samples
    /// @param length segment length
    /// @param score repeated-anchor score
    /// @param fingerprint segment fingerprint
    private record Candidate(
            int sampleIndex,
            int samplePosition,
            int offset,
            int length,
            long score,
            long fingerprint
    ) {
        /// Orders the strongest candidates first with deterministic position tie breaking.
        private static final Comparator<Candidate> BEST_FIRST = Comparator
                .comparingLong(Candidate::score)
                .reversed()
                .thenComparingInt(Candidate::sampleIndex)
                .thenComparingInt(Candidate::samplePosition);

        /// Orders the weakest candidates first.
        private static final Comparator<Candidate> WORST_FIRST = BEST_FIRST.reversed();

        /// Creates candidate metadata.
        private Candidate {
        }
    }

    /// Creates no instances.
    private ZstdDictionaryBuilder() {
    }
}
