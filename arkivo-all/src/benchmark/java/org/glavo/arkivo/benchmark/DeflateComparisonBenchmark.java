// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.benchmark;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.deflate.DeflateCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/// Compares Arkivo's pure Java raw Deflate engine with the JDK's zlib-backed implementation.
///
/// Both compressors use the same numeric compression level. Both decoder measurements consume the same stream produced
/// by the JDK compressor so compressed representation differences do not distort decoder throughput comparisons.
@NotNullByDefault
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class DeflateComparisonBenchmark {
    /// The uncompressed byte count processed by each benchmark invocation.
    private static final int SOURCE_SIZE = 4 * 1024 * 1024;

    /// Extra output capacity above the source size for incompressible raw Deflate streams.
    private static final int OUTPUT_SLACK = SOURCE_SIZE >>> 4;

    /// The deterministic source profile selected for the current benchmark trial.
    @Param({"text", "mixed", "random"})
    public String profile = "mixed";

    /// The shared numeric compression level selected for both implementations.
    @Param({"1", "6", "9"})
    public int level = 6;

    /// The uncompressed source bytes.
    private byte @Unmodifiable [] source = new byte[0];

    /// A reusable read-only view over the uncompressed source.
    private @UnmodifiableView ByteBuffer sourceInput = ByteBuffer.allocate(0).asReadOnlyBuffer();

    /// The JDK-produced raw Deflate stream used by both decoder benchmarks.
    private byte @Unmodifiable [] commonCompressed = new byte[0];

    /// A reusable read-only view over the common compressed stream.
    private @UnmodifiableView ByteBuffer compressedInput = ByteBuffer.allocate(0).asReadOnlyBuffer();

    /// Reusable Arkivo compression output.
    private ByteBuffer arkivoEncoded = ByteBuffer.allocate(0);

    /// Reusable Arkivo decompression output.
    private ByteBuffer arkivoDecoded = ByteBuffer.allocate(0);

    /// Reusable JDK compression output.
    private byte[] jdkEncoded = new byte[0];

    /// Reusable JDK decompression output.
    private byte[] jdkDecoded = new byte[0];

    /// Arkivo's reusable raw Deflate encoder.
    private @Nullable CompressionEncoder arkivoEncoder;

    /// Arkivo's reusable raw Deflate decoder.
    private @Nullable CompressionDecoder arkivoDecoder;

    /// The reusable JDK raw Deflate compressor.
    private @Nullable Deflater jdkDeflater;

    /// The reusable JDK raw Deflate decompressor.
    private @Nullable Inflater jdkInflater;

    /// Creates reusable engines and verifies cross-implementation compatibility.
    @Setup
    public void setUp() throws IOException, DataFormatException {
        source = createSource(profile);
        sourceInput = ByteBuffer.wrap(source).asReadOnlyBuffer();
        arkivoEncoded = ByteBuffer.allocate(SOURCE_SIZE + OUTPUT_SLACK);
        arkivoDecoded = ByteBuffer.allocate(SOURCE_SIZE + 1);
        jdkEncoded = new byte[SOURCE_SIZE + OUTPUT_SLACK];
        jdkDecoded = new byte[SOURCE_SIZE + 1];

        DeflateCodec codec = new DeflateCodec().withCompressionLevel(level);
        arkivoEncoder = codec.newEncoder();
        arkivoDecoder = codec.newDecoder();
        jdkDeflater = new Deflater(level, true);
        jdkInflater = new Inflater(true);

        int commonSize = compressJdkOnce();
        commonCompressed = Arrays.copyOf(jdkEncoded, commonSize);
        compressedInput = ByteBuffer.wrap(commonCompressed).asReadOnlyBuffer();

        int arkivoSize = compressArkivoOnce();
        if (arkivoSize <= 0) {
            throw new AssertionError("Arkivo Deflate produced no output");
        }
        byte[] arkivoCompressed = Arrays.copyOf(arkivoEncoded.array(), arkivoSize);
        int arkivoDecodedSize = decompressArkivoOnce();
        int jdkDecodedSize = decompressJdkOnce();
        int jdkDecodedArkivoSize = decompressJdkOnce(arkivoCompressed);
        if (arkivoDecodedSize != source.length
                || jdkDecodedSize != source.length
                || jdkDecodedArkivoSize != source.length
                || !Arrays.equals(source, 0, source.length, arkivoDecoded.array(), 0, arkivoDecodedSize)
                || !Arrays.equals(source, 0, source.length, jdkDecoded, 0, jdkDecodedArkivoSize)) {
            throw new AssertionError("Deflate comparison setup did not round trip profile " + profile);
        }
    }

    /// Releases native and Java codec state after one benchmark trial.
    @TearDown
    public void tearDown() {
        CompressionEncoder currentEncoder = arkivoEncoder;
        arkivoEncoder = null;
        if (currentEncoder != null) {
            currentEncoder.close();
        }

        CompressionDecoder currentDecoder = arkivoDecoder;
        arkivoDecoder = null;
        if (currentDecoder != null) {
            currentDecoder.close();
        }

        Deflater currentDeflater = jdkDeflater;
        jdkDeflater = null;
        if (currentDeflater != null) {
            currentDeflater.end();
        }

        Inflater currentInflater = jdkInflater;
        jdkInflater = null;
        if (currentInflater != null) {
            currentInflater.end();
        }
    }

    /// Compresses the selected source with Arkivo and records its output ratio.
    @Benchmark
    @OperationsPerInvocation(SOURCE_SIZE)
    public int compressArkivo(CompressionCounters counters) throws IOException {
        int compressedSize = compressArkivoOnce();
        counters.record(compressedSize);
        return compressedSize;
    }

    /// Compresses the selected source with the JDK and records its output ratio.
    @Benchmark
    @OperationsPerInvocation(SOURCE_SIZE)
    public int compressJdk(CompressionCounters counters) {
        int compressedSize = compressJdkOnce();
        counters.record(compressedSize);
        return compressedSize;
    }

    /// Decompresses the common JDK-produced stream with Arkivo.
    @Benchmark
    @OperationsPerInvocation(SOURCE_SIZE)
    public int decompressArkivo() throws IOException {
        int decodedSize = decompressArkivoOnce();
        if (decodedSize != SOURCE_SIZE) {
            throw new AssertionError("Unexpected Arkivo decoded size: " + decodedSize);
        }
        return decodedSize;
    }

    /// Decompresses the common JDK-produced stream with the JDK.
    @Benchmark
    @OperationsPerInvocation(SOURCE_SIZE)
    public int decompressJdk() throws DataFormatException {
        int decodedSize = decompressJdkOnce();
        if (decodedSize != SOURCE_SIZE) {
            throw new AssertionError("Unexpected JDK decoded size: " + decodedSize);
        }
        return decodedSize;
    }

    /// Compresses the reusable source with Arkivo and returns the encoded byte count.
    private int compressArkivoOnce() throws IOException {
        CompressionEncoder encoder = requireArkivoEncoder();
        encoder.reset();
        sourceInput.clear();
        arkivoEncoded.clear();

        while (sourceInput.hasRemaining()) {
            CodecOutcome outcome = encoder.encode(sourceInput, arkivoEncoded);
            if (outcome == CodecOutcome.NEEDS_OUTPUT) {
                throw new BufferOverflowException();
            }
            if (outcome != CodecOutcome.NEEDS_INPUT) {
                throw new IOException("Unexpected Arkivo Deflate encode outcome: " + outcome);
            }
        }

        while (true) {
            CodecOutcome outcome = encoder.finish(arkivoEncoded);
            if (outcome == CodecOutcome.FINISHED) {
                return arkivoEncoded.position();
            }
            if (outcome != CodecOutcome.NEEDS_OUTPUT) {
                throw new IOException("Unexpected Arkivo Deflate finish outcome: " + outcome);
            }
            if (!arkivoEncoded.hasRemaining()) {
                throw new BufferOverflowException();
            }
        }
    }

    /// Compresses the reusable source with the JDK and returns the encoded byte count.
    private int compressJdkOnce() {
        Deflater deflater = requireJdkDeflater();
        deflater.reset();
        deflater.setInput(source);
        deflater.finish();
        int outputPosition = 0;
        while (!deflater.finished()) {
            int produced = deflater.deflate(jdkEncoded, outputPosition, jdkEncoded.length - outputPosition);
            outputPosition += produced;
            if (produced == 0) {
                if (outputPosition == jdkEncoded.length) {
                    throw new BufferOverflowException();
                }
                throw new IllegalStateException("JDK Deflater made no progress");
            }
        }
        return outputPosition;
    }

    /// Decompresses the common stream with Arkivo and returns the decoded byte count.
    private int decompressArkivoOnce() throws IOException {
        CompressionDecoder decoder = requireArkivoDecoder();
        decoder.reset();
        compressedInput.clear();
        arkivoDecoded.clear();

        while (true) {
            CodecOutcome outcome = decoder.finish(compressedInput, arkivoDecoded);
            if (outcome == CodecOutcome.FINISHED) {
                if (compressedInput.hasRemaining()) {
                    throw new IOException("Arkivo Deflate decoder left trailing input");
                }
                return arkivoDecoded.position();
            }
            if (outcome == CodecOutcome.NEEDS_OUTPUT) {
                throw new BufferOverflowException();
            }
            if (outcome == CodecOutcome.NEEDS_INPUT) {
                throw new IOException("Arkivo Deflate decoder requested input after end of input");
            }
            throw new IOException("Unexpected Arkivo Deflate decode outcome: " + outcome);
        }
    }

    /// Decompresses the common stream with the JDK and returns the decoded byte count.
    private int decompressJdkOnce() throws DataFormatException {
        return decompressJdkOnce(commonCompressed);
    }

    /// Decompresses one supplied stream with the JDK and returns the decoded byte count.
    private int decompressJdkOnce(byte[] compressed) throws DataFormatException {
        Inflater inflater = requireJdkInflater();
        inflater.reset();
        inflater.setInput(compressed);
        int outputPosition = 0;
        while (!inflater.finished()) {
            int produced = inflater.inflate(jdkDecoded, outputPosition, jdkDecoded.length - outputPosition);
            outputPosition += produced;
            if (produced == 0) {
                if (inflater.needsDictionary()) {
                    throw new DataFormatException("JDK Inflater unexpectedly requested a dictionary");
                }
                if (inflater.needsInput()) {
                    throw new DataFormatException("JDK Inflater requested additional input");
                }
                if (outputPosition == jdkDecoded.length) {
                    throw new BufferOverflowException();
                }
                throw new DataFormatException("JDK Inflater made no progress");
            }
        }
        if (inflater.getRemaining() != 0) {
            throw new DataFormatException("JDK Inflater left trailing input");
        }
        return outputPosition;
    }

    /// Returns the initialized Arkivo encoder.
    private CompressionEncoder requireArkivoEncoder() {
        CompressionEncoder encoder = arkivoEncoder;
        if (encoder == null) {
            throw new IllegalStateException("Arkivo Deflate encoder is not initialized");
        }
        return encoder;
    }

    /// Returns the initialized Arkivo decoder.
    private CompressionDecoder requireArkivoDecoder() {
        CompressionDecoder decoder = arkivoDecoder;
        if (decoder == null) {
            throw new IllegalStateException("Arkivo Deflate decoder is not initialized");
        }
        return decoder;
    }

    /// Returns the initialized JDK compressor.
    private Deflater requireJdkDeflater() {
        Deflater deflater = jdkDeflater;
        if (deflater == null) {
            throw new IllegalStateException("JDK Deflater is not initialized");
        }
        return deflater;
    }

    /// Returns the initialized JDK decompressor.
    private Inflater requireJdkInflater() {
        Inflater inflater = jdkInflater;
        if (inflater == null) {
            throw new IllegalStateException("JDK Inflater is not initialized");
        }
        return inflater;
    }

    /// Creates one deterministic source profile.
    private static byte @Unmodifiable [] createSource(String profile) {
        return switch (profile) {
            case "text" -> createTextSource();
            case "mixed" -> createMixedSource();
            case "random" -> createRandomSource();
            default -> throw new IllegalArgumentException("Unknown Deflate benchmark profile: " + profile);
        };
    }

    /// Creates repeated UTF-8 prose with slowly changing numeric fields.
    private static byte @Unmodifiable [] createTextSource() {
        byte[] result = new byte[SOURCE_SIZE];
        byte[] sentence = (
                "Arkivo compares pure Java compression with repeatable inputs and explicit buffer ownership.\n"
        ).getBytes(StandardCharsets.UTF_8);
        int position = 0;
        int line = 0;
        while (position < result.length) {
            int copied = Math.min(sentence.length, result.length - position);
            System.arraycopy(sentence, 0, result, position, copied);
            position += copied;
            if (position + Integer.BYTES <= result.length) {
                result[position++] = (byte) line;
                result[position++] = (byte) (line >>> 8);
                result[position++] = (byte) (line >>> 16);
                result[position++] = (byte) (line >>> 24);
            }
            line++;
        }
        return result;
    }

    /// Creates structured binary data with repeated and locally varying regions.
    private static byte @Unmodifiable [] createMixedSource() {
        byte[] result = new byte[SOURCE_SIZE];
        for (int index = 0; index < result.length; index++) {
            int offset = index & 0xffff;
            result[index] = (byte) (offset < 48 * 1024 ? offset * 31 : index * 17);
        }
        return result;
    }

    /// Creates deterministic high-entropy bytes.
    private static byte @Unmodifiable [] createRandomSource() {
        byte[] result = new byte[SOURCE_SIZE];
        new Random(0x41524b49564fL).nextBytes(result);
        return result;
    }

    /// Records source and encoded byte totals used to derive each compressor's output ratio.
    @NotNullByDefault
    @AuxCounters(AuxCounters.Type.EVENTS)
    @State(Scope.Thread)
    public static class CompressionCounters {
        /// Source bytes presented to the measured compressor.
        public long sourceBytes;

        /// Encoded bytes produced by the measured compressor.
        public long compressedBytes;

        /// Clears counters before each measurement iteration.
        @Setup(Level.Iteration)
        public void reset() {
            sourceBytes = 0L;
            compressedBytes = 0L;
        }

        /// Records one completed compression invocation.
        public void record(int compressedSize) {
            sourceBytes += SOURCE_SIZE;
            compressedBytes += compressedSize;
        }
    }
}
