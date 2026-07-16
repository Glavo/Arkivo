// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.benchmark;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormats;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.lzma.LZMA2Codec;
import org.glavo.arkivo.codec.lzma.RawLZMACodec;
import org.glavo.arkivo.codec.ppmd.PPMdCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/// Measures transport-independent compression engines with reusable caller-owned buffers.
@NotNullByDefault
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class CodecBufferThroughputBenchmark {
    /// The uncompressed byte count processed by each benchmark invocation.
    private static final int SOURCE_SIZE = 4 * 1024 * 1024;

    /// The fallback target multiplier used when a codec does not publish a compressed-size bound.
    private static final int FALLBACK_CAPACITY_MULTIPLIER = 2;

    /// The externally declared raw LZMA-family dictionary size.
    private static final int LZMA_DICTIONARY_SIZE = 1 << 20;

    /// The stable compression format name selected for the current benchmark trial.
    @Param({"bzip2", "deflate", "deflate64", "gzip", "lzma", "lzma-raw", "lzma2", "ppmd", "xz", "zlib", "zstd"})
    public String formatName = "gzip";

    /// The deterministic uncompressed bytes used to verify decoder output.
    private byte @Unmodifiable [] expected = new byte[0];

    /// The reusable read-only source view advanced by encoder operations.
    private @UnmodifiableView ByteBuffer sourceInput = ByteBuffer.allocate(0).asReadOnlyBuffer();

    /// The reusable read-only compressed view advanced by decoder operations.
    private @UnmodifiableView ByteBuffer compressedInput = ByteBuffer.allocate(0).asReadOnlyBuffer();

    /// The reusable caller-owned compression target.
    private ByteBuffer encodedOutput = ByteBuffer.allocate(0);

    /// The reusable caller-owned decompression target.
    private ByteBuffer decodedOutput = ByteBuffer.allocate(0);

    /// The immutable codec configuration used by compression operations in the current trial.
    private @Nullable CompressionCodec<?> compressionCodec;

    /// The immutable codec configuration used by decompression operations in the current trial.
    private @Nullable CompressionCodec<?> decompressionCodec;

    /// The transport-independent encoder under measurement.
    private @Nullable CompressionEncoder encoder;

    /// The transport-independent decoder under measurement.
    private @Nullable CompressionDecoder decoder;

    /// Creates reusable engines and buffers and verifies one complete round trip.
    @Setup
    public void setUp() throws IOException {
        CompressionCodec<?> selectedCodec = CompressionFormats.require(formatName).defaultCodec();
        CompressionCodec<?> compressionConfiguration = selectedCodec;
        CompressionCodec<?> decompressionConfiguration = selectedCodec;
        if (selectedCodec instanceof PPMdCodec ppmdCodec) {
            PPMdCodec configured = ppmdCodec
                    .withMaximumOrder(6L)
                    .withMemorySize(16L << 20);
            compressionConfiguration = configured;
            decompressionConfiguration = configured.withDecodedSize(SOURCE_SIZE);
        } else if (selectedCodec instanceof LZMA2Codec lzma2Codec) {
            LZMA2Codec configured = lzma2Codec.withDictionarySize(LZMA_DICTIONARY_SIZE);
            compressionConfiguration = configured;
            decompressionConfiguration = configured;
        } else if (selectedCodec instanceof RawLZMACodec rawLzmaCodec) {
            RawLZMACodec configured = rawLzmaCodec.withDictionarySize(LZMA_DICTIONARY_SIZE);
            compressionConfiguration = configured;
            decompressionConfiguration = configured;
        }
        compressionCodec = compressionConfiguration;
        decompressionCodec = decompressionConfiguration;
        encoder = compressionConfiguration.newEncoder();
        decoder = decompressionConfiguration.newDecoder();

        byte[] content = new byte[SOURCE_SIZE];
        for (int index = 0; index < content.length; index++) {
            int offset = index & 0xffff;
            content[index] = (byte) (offset < 48 * 1024 ? offset * 31 : index * 17);
        }
        expected = content;
        sourceInput = ByteBuffer.wrap(content).asReadOnlyBuffer();
        encodedOutput = ByteBuffer.allocate(compressionCapacity(compressionConfiguration));
        decodedOutput = ByteBuffer.allocate(SOURCE_SIZE + 1);

        int compressedSize = encodeOnce();
        byte[] compressed = new byte[compressedSize];
        encodedOutput.flip();
        encodedOutput.get(compressed);
        compressedInput = ByteBuffer.wrap(compressed).asReadOnlyBuffer();

        int restoredSize = decodeOnce();
        if (restoredSize != expected.length
                || !Arrays.equals(expected, 0, expected.length, decodedOutput.array(), 0, restoredSize)) {
            throw new AssertionError("Codec buffer benchmark setup did not round trip " + formatName);
        }
    }

    /// Releases the trial's reusable codec engines.
    @TearDown
    public void tearDown() {
        compressionCodec = null;
        decompressionCodec = null;
        CompressionEncoder currentEncoder = encoder;
        encoder = null;
        if (currentEncoder != null) {
            currentEncoder.close();
        }

        CompressionDecoder currentDecoder = decoder;
        decoder = null;
        if (currentDecoder != null) {
            currentDecoder.close();
        }
    }

    /// Compresses one fixed-size source through the public allocating ByteBuffer operation.
    @Benchmark
    @OperationsPerInvocation(SOURCE_SIZE)
    public ByteBuffer compressOneShot() throws IOException {
        sourceInput.clear();
        return requireCompressionCodec().compress(sourceInput);
    }

    /// Decompresses one encoding through the public allocating ByteBuffer operation.
    @Benchmark
    @OperationsPerInvocation(SOURCE_SIZE)
    public ByteBuffer decompressOneShot() throws IOException {
        compressedInput.clear();
        ByteBuffer restored = requireDecompressionCodec().decompress(compressedInput, SOURCE_SIZE);
        if (restored.remaining() != SOURCE_SIZE) {
            throw new AssertionError("Unexpected one-shot decoded size: " + restored.remaining());
        }
        return restored;
    }

    /// Compresses one fixed-size source directly between caller-owned buffers.
    @Benchmark
    @OperationsPerInvocation(SOURCE_SIZE)
    public int compressBuffers() throws IOException {
        return encodeOnce();
    }

    /// Decompresses one encoding directly between caller-owned buffers.
    @Benchmark
    @OperationsPerInvocation(SOURCE_SIZE)
    public int decompressBuffers() throws IOException {
        int restoredSize = decodeOnce();
        if (restoredSize != SOURCE_SIZE) {
            throw new AssertionError("Unexpected decoded size: " + restoredSize);
        }
        return restoredSize;
    }

    /// Encodes the reusable source and returns the compressed byte count.
    private int encodeOnce() throws IOException {
        CompressionEncoder current = requireEncoder();
        current.reset();
        sourceInput.clear();
        encodedOutput.clear();

        while (sourceInput.hasRemaining()) {
            int sourcePosition = sourceInput.position();
            int targetPosition = encodedOutput.position();
            CodecOutcome outcome = current.encode(sourceInput, encodedOutput);
            if (outcome == CodecOutcome.NEEDS_OUTPUT) {
                throw new BufferOverflowException();
            }
            if (outcome != CodecOutcome.NEEDS_INPUT) {
                throw new IOException("Unexpected encoder outcome while consuming input: " + outcome);
            }
            if (sourceInput.position() == sourcePosition && encodedOutput.position() == targetPosition) {
                throw new IOException("Compression encoder made no progress");
            }
        }

        while (true) {
            int targetPosition = encodedOutput.position();
            CodecOutcome outcome = current.finish(encodedOutput);
            if (outcome == CodecOutcome.FINISHED) {
                return encodedOutput.position();
            }
            if (outcome != CodecOutcome.NEEDS_OUTPUT) {
                throw new IOException("Unexpected encoder outcome while finishing: " + outcome);
            }
            if (encodedOutput.position() == targetPosition) {
                throw new IOException("Compression encoder made no finishing progress");
            }
            if (!encodedOutput.hasRemaining()) {
                throw new BufferOverflowException();
            }
        }
    }

    /// Decodes the reusable encoding and returns the restored byte count.
    private int decodeOnce() throws IOException {
        CompressionDecoder current = requireDecoder();
        current.reset();
        compressedInput.clear();
        decodedOutput.clear();

        while (true) {
            int sourcePosition = compressedInput.position();
            int targetPosition = decodedOutput.position();
            CodecOutcome outcome = current.decode(compressedInput, decodedOutput, true);
            if (outcome == CodecOutcome.FINISHED) {
                if (compressedInput.hasRemaining() && !formatName.equals("ppmd")) {
                    throw new IOException("Compression decoder left trailing input");
                }
                return decodedOutput.position();
            }
            if (outcome == CodecOutcome.NEEDS_OUTPUT) {
                throw new BufferOverflowException();
            }
            if (outcome == CodecOutcome.NEEDS_DICTIONARY) {
                throw new IOException("Compression decoder unexpectedly requested a dictionary");
            }
            if (outcome == CodecOutcome.NEEDS_INPUT) {
                throw new IOException("Compression decoder requested input after end of input");
            }
            if (outcome != CodecOutcome.BOUNDARY_REACHED) {
                throw new IOException("Unexpected decoder outcome: " + outcome);
            }
            if (compressedInput.position() == sourcePosition && decodedOutput.position() == targetPosition) {
                throw new IOException("Compression decoder made no progress");
            }
        }
    }

    /// Returns the current initialized compression configuration.
    private CompressionCodec<?> requireCompressionCodec() {
        CompressionCodec<?> current = compressionCodec;
        if (current == null) {
            throw new IllegalStateException("Benchmark compression codec is not initialized");
        }
        return current;
    }

    /// Returns the current initialized decompression configuration.
    private CompressionCodec<?> requireDecompressionCodec() {
        CompressionCodec<?> current = decompressionCodec;
        if (current == null) {
            throw new IllegalStateException("Benchmark decompression codec is not initialized");
        }
        return current;
    }

    /// Returns the current initialized encoder.
    private CompressionEncoder requireEncoder() {
        CompressionEncoder current = encoder;
        if (current == null) {
            throw new IllegalStateException("Benchmark encoder is not initialized");
        }
        return current;
    }

    /// Returns the current initialized decoder.
    private CompressionDecoder requireDecoder() {
        CompressionDecoder current = decoder;
        if (current == null) {
            throw new IllegalStateException("Benchmark decoder is not initialized");
        }
        return current;
    }

    /// Returns a safe reusable compression target capacity for the selected codec.
    private static int compressionCapacity(CompressionCodec<?> codec) {
        long bound = codec.maxCompressedSize(SOURCE_SIZE);
        long capacity = bound >= 0L
                ? bound
                : (long) SOURCE_SIZE * FALLBACK_CAPACITY_MULTIPLIER;
        if (capacity <= 0L || capacity > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Unsupported compressed-size bound: " + capacity);
        }
        return (int) capacity;
    }
}
