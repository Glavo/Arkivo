// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.jetbrains.annotations.NotNullByDefault;
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

    /// Builds one current or legacy-oriented formatted dictionary.
    ///
    /// @param samples packed sample bytes
    /// @param sampleSizes size of each packed sample
    /// @param dictionaryCapacity maximum dictionary size
    /// @param compressionLevel dictionary optimization level
    /// @param legacy whether to use the legacy-oriented content selector
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

        ByteArrayOutputStream entropy = new ByteArrayOutputStream(160);
        writeHuffmanTable(entropy);
        writeFseTable(entropy, uniformDistribution(31, 5));
        writeFseTable(entropy, uniformDistribution(53, 6));
        writeFseTable(entropy, uniformDistribution(36, 6));
        writeLittleEndianInt(entropy, 1L);
        writeLittleEndianInt(entropy, 4L);
        writeLittleEndianInt(entropy, 8L);

        int contentCapacity = dictionaryCapacity - 8 - entropy.size();
        if (contentCapacity < MINIMUM_SAMPLE_BYTES) {
            throw new IllegalArgumentException("dictionaryCapacity cannot hold a formatted dictionary");
        }
        byte[] content = legacy
                ? selectLegacyContent(samples, Math.min(contentCapacity, samples.length))
                : selectCurrentContent(
                        samples,
                        sampleSizes,
                        Math.min(contentCapacity, samples.length),
                        compressionLevel
                );
        if (content.length < MINIMUM_SAMPLE_BYTES) {
            throw new IllegalArgumentException("Insufficient Zstandard dictionary content");
        }

        long dictionaryId = dictionaryId(content);
        ByteArrayOutputStream dictionary = new ByteArrayOutputStream(8 + entropy.size() + content.length);
        writeLittleEndianInt(dictionary, DICTIONARY_MAGIC);
        writeLittleEndianInt(dictionary, dictionaryId);
        dictionary.writeBytes(entropy.toByteArray());
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
        long value = readLong(source, offset);
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

    /// Reads an unaligned little-endian long.
    private static long readLong(byte[] source, int offset) {
        return Integer.toUnsignedLong(readInt(source, offset))
                | Integer.toUnsignedLong(readInt(source, offset + 4)) << 32;
    }

    /// Reads an unaligned little-endian integer.
    private static int readInt(byte[] source, int offset) {
        return Byte.toUnsignedInt(source[offset])
                | Byte.toUnsignedInt(source[offset + 1]) << 8
                | Byte.toUnsignedInt(source[offset + 2]) << 16
                | source[offset + 3] << 24;
    }

    /// Writes a compact valid Huffman tree covering the complete ASCII range.
    private static void writeHuffmanTable(ByteArrayOutputStream output) {
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
        int[] normalized = distribution.normalized();
        ForwardBitWriter bits = new ForwardBitWriter();
        bits.write(distribution.tableLog() - 5, 4);

        int remaining = (1 << distribution.tableLog()) + 1;
        int threshold = 1 << distribution.tableLog();
        int numberOfBits = distribution.tableLog() + 1;
        boolean previousZero = false;
        int symbol = 0;
        while (remaining > 1 && symbol < normalized.length) {
            if (previousZero) {
                int start = symbol;
                while (symbol < normalized.length && normalized[symbol] == 0) {
                    symbol++;
                }
                int zeroRun = symbol - start;
                while (zeroRun >= 24) {
                    bits.write(0xffff, 16);
                    zeroRun -= 24;
                }
                while (zeroRun >= 3) {
                    bits.write(3, 2);
                    zeroRun -= 3;
                }
                bits.write(zeroRun, 2);
            }

            int count = normalized[symbol++];
            int maximum = (threshold << 1) - 1 - remaining;
            remaining -= Math.abs(count);
            int encoded = count + 1;
            if (encoded >= threshold) {
                encoded += maximum;
            }
            bits.write(encoded, numberOfBits - (encoded < maximum ? 1 : 0));
            previousZero = count == 0;
            while (remaining < threshold) {
                numberOfBits--;
                threshold >>>= 1;
            }
        }
        if (remaining != 1) {
            throw new IllegalArgumentException("Invalid normalized Zstandard FSE distribution");
        }
        output.writeBytes(bits.finish());
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

    /// Packs little-endian fields into a forward-readable bitstream.
    @NotNullByDefault
    private static final class ForwardBitWriter {
        /// Packed bytes.
        private byte[] bytes = new byte[16];

        /// Number of bits written.
        private int bitCount;

        /// Appends a low-order bit field.
        private void write(int value, int count) {
            if (count < 0 || count > 31 || (count != 31 && value >>> count != 0)) {
                throw new IllegalArgumentException("Invalid Zstandard forward bit field");
            }
            ensureCapacity(bitCount + count);
            for (int bit = 0; bit < count; bit++) {
                if ((value & 1 << bit) != 0) {
                    bytes[(bitCount + bit) >>> 3] |= (byte) (1 << ((bitCount + bit) & 7));
                }
            }
            bitCount += count;
        }

        /// Returns the complete byte-aligned representation.
        private byte[] finish() {
            return Arrays.copyOf(bytes, (bitCount + 7) >>> 3);
        }

        /// Grows the packed storage to hold the requested bit count.
        private void ensureCapacity(int requiredBits) {
            int requiredBytes = (requiredBits + 7) >>> 3;
            if (requiredBytes > bytes.length) {
                bytes = Arrays.copyOf(bytes, Math.max(requiredBytes, bytes.length * 2));
            }
        }
    }

    /// Creates no instances.
    private ZstdDictionaryBuilder() {
    }
}
