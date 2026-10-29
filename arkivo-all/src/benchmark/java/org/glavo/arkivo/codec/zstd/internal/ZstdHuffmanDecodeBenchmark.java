// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import com.github.luben.zstd.ZstdCompressCtx;
import org.glavo.arkivo.codec.zstd.ZstdStandardFrameInfo;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/// Measures four-stream literal decoding, excluding table construction, sequence execution and frame checksums.
@NotNullByDefault
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class ZstdHuffmanDecodeBenchmark {
    /// Complete input file; empty selects one MiB of deterministic uniformly distributed six-bit symbols.
    @Param({""})
    public String inputFile = "";

    /// Literal sections to include: all, small (below 32 KiB), or large (at least 32 KiB).
    @Param({"all"})
    public String sectionSize = "all";

    /// Native compression level used to generate the literal sections before measurement.
    @Param({"3"})
    public int compressionLevel = 3;

    /// Prepared sections with reusable output storage.
    private @Unmodifiable List<Section> sections = List.of();

    /// Extracts native-compressed sections and compares their output with independent bitwise prefix lookup.
    @Setup
    public void setup() throws Exception {
        if (!sectionSize.equals("all") && !sectionSize.equals("small") && !sectionSize.equals("large")) {
            throw new IllegalArgumentException(sectionSize);
        }
        byte[] body;
        if (inputFile.isEmpty()) {
            body = new byte[1024 * 1024];
            new Random(42).nextBytes(body);
            for (int index = 0; index < body.length; index++) body[index] &= 63;
        } else {
            body = Files.readAllBytes(Path.of(inputFile));
        }
        byte[] frame;
        try (var encoder = new ZstdCompressCtx()) {
            frame = encoder.setLevel(compressionLevel).setChecksum(true).compress(body);
        }
        var header = (ZstdStandardFrameInfo) ZstdFrameHeader.parse(ByteBuffer.wrap(frame));
        var prepared = new ArrayList<Section>();
        ZstdEntropy.@Nullable HuffmanTable table = null;
        short @Nullable @Unmodifiable [] referenceTable = null;
        int offset = header.headerSize();
        int literalCount = 0;
        for (;;) {
            // The generated frame's checksum leaves enough bytes for this masked three-byte read.
            int blockHeader = ByteArrayAccess.readIntLittleEndian(frame, offset) & 0xffffff;
            int blockType = (blockHeader >>> 1) & 3;
            int blockSize = blockHeader >>> 3;
            offset += 3;
            if (blockType == 2 && (frame[offset] & 3) >= 2) {
                byte[] block = Arrays.copyOfRange(frame, offset, offset + blockSize);
                int format = (block[0] >>> 2) & 3;
                long fields = Integer.toUnsignedLong(ByteArrayAccess.readIntLittleEndian(block, 0));
                int count;
                int compressedSize;
                int start;
                if (format <= 1) {
                    start = 3;
                    count = (int) (fields >>> 4) & 0x3ff;
                    compressedSize = (int) (fields >>> 14) & 0x3ff;
                } else if (format == 2) {
                    start = 4;
                    count = (int) (fields >>> 4) & 0x3fff;
                    compressedSize = (int) (fields >>> 18);
                } else {
                    start = 5;
                    fields |= (long) Byte.toUnsignedInt(block[4]) << 32;
                    count = (int) (fields >>> 4) & 0x3ffff;
                    compressedSize = (int) (fields >>> 22) & 0x3ffff;
                }
                int limit = start + compressedSize;
                if ((block[0] & 3) == 2) {
                    var parsed = ZstdEntropy.readHuffmanTable(block, start, limit);
                    table = parsed.table();
                    referenceTable = referenceTable(parsed.weights(), parsed.symbolCount(), parsed.tableLog());
                    start += parsed.bytesRead();
                }
                if (format != 0 && (sectionSize.equals("all")
                        || sectionSize.equals(count < 32768 ? "small" : "large"))) {
                    var section = new Section(Objects.requireNonNull(table), block, start, limit, new byte[count]);
                    byte[] expected = decodeReference(section, Objects.requireNonNull(referenceTable));
                    section.decode();
                    if (!Arrays.equals(expected, section.output())) throw new AssertionError("Huffman output differs");
                    prepared.add(section);
                    literalCount += count;
                }
            }
            offset += blockType == 1 ? 1 : blockSize;
            if ((blockHeader & 1) != 0) break;
        }
        if (prepared.isEmpty()) throw new IllegalArgumentException("Input has no selected four-stream literal sections");
        sections = List.copyOf(prepared);
        System.out.println("ZSTD_HUFFMAN_SECTIONS " + sections.size());
        System.out.println("ZSTD_HUFFMAN_OUTPUT_BYTES " + literalCount);
    }

    /// Decodes all selected literal sections with tables and buffers retained from setup.
    @Benchmark
    public int decode() throws IOException {
        int count = 0;
        for (int index = 0; index < sections.size(); index++) {
            Section section = sections.get(index);
            section.decode();
            count += section.output().length;
        }
        return count;
    }

    /// Expands canonical prefix ranges independently of the production table representation.
    private static short @Unmodifiable [] referenceTable(int @Unmodifiable [] weights, int symbolCount, int tableLog) {
        short[] entries = new short[1 << tableLog];
        int prefix = 0;
        for (int weight = 1; weight <= tableLog; weight++) {
            int width = 1 << (weight - 1);
            for (int symbol = 0; symbol < symbolCount; symbol++) {
                if (weights[symbol] != weight) continue;
                short entry = (short) ((symbol << 4) | (tableLog + 1 - weight));
                Arrays.fill(entries, prefix, prefix + width, entry);
                prefix += width;
            }
        }
        if (prefix != entries.length) throw new AssertionError("Incomplete Huffman weights");
        return entries;
    }

    /// Resolves prefixes one bit at a time without the production reverse reader, table, or batch loop.
    private static byte[] decodeReference(Section section, short @Unmodifiable [] entries) {
        int tableLog = Integer.numberOfTrailingZeros(entries.length);
        byte[] expected = new byte[section.output().length];
        int segmentSize = (expected.length + 3) / 4;
        byte @Unmodifiable [] source = section.source();
        int input = section.offset() + 6;
        for (int lane = 0; lane < 4; lane++) {
            int length = lane < 3
                    ? Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(source, section.offset() + lane * 2))
                    : section.limit() - input;
            int marker = Byte.toUnsignedInt(source[input + length - 1]);
            if (marker == 0) throw new AssertionError("Missing Huffman marker");
            int position = (input + length - 1) * 8 + 31 - Integer.numberOfLeadingZeros(marker);
            int start = input * 8;
            int end = Math.min(expected.length, (lane + 1) * segmentSize);
            for (int output = lane * segmentSize; output < end; output++) {
                int available = Math.min(tableLog, position - start);
                int prefix = 0;
                for (int bit = 1; bit <= available; bit++) {
                    int sourceBit = position - bit;
                    prefix = (prefix << 1) | ((source[sourceBit >>> 3] >>> (sourceBit & 7)) & 1);
                }
                int entry = entries[prefix << (tableLog - available)];
                int consumed = entry & 15;
                if (consumed == 0 || consumed > available) throw new AssertionError("Invalid Huffman prefix");
                expected[output] = (byte) (entry >>> 4);
                position -= consumed;
            }
            if (position != start) throw new AssertionError("Unused Huffman bits");
            input += length;
        }
        return expected;
    }

    /// One literal section with a fixed table and reusable output.
    /// @param table canonical prefix table
    /// @param source complete compressed block, including sequence bytes after the literal section
    /// @param offset jump-table offset within the block
    /// @param limit exclusive literal-section end
    /// @param output reusable decoded literal storage
    @NotNullByDefault
    private record Section(ZstdEntropy.HuffmanTable table, byte @Unmodifiable [] source,
                           int offset, int limit, byte[] output) {
        /// Decodes into the retained output without rebuilding the table.
        private void decode() throws IOException {
            table.decodeFourStreams(source, offset, limit, output, output.length);
        }
    }
}
