// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.benchmark;

import org.glavo.arkivo.codec.transform.ByteTransform;
import org.glavo.arkivo.codec.transform.TransformingInputStream;
import org.glavo.arkivo.codec.xz.internal.filter.BCJTransforms;
import org.glavo.arkivo.codec.xz.internal.filter.DeltaTransform;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.openjdk.jmh.annotations.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/// Measures complete transform-input sessions with scalar, bulk, and repeated skip access.
@NotNullByDefault
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class TransformInputBenchmark {
    /// Stateful production filter applied to the deterministic source bytes.
    @Param({"delta", "x86"})
    public String filter = "delta";

    /// How each session consumes transformed data.
    @Param({"bulk", "scalar", "skip"})
    public String access = "bulk";

    /// Source bytes, including a short suffix beyond the last complete buffer.
    private byte @Unmodifiable [] content = new byte[0];

    /// Reused destination for measured bulk reads.
    private final byte[] output = new byte[8192];

    /// Expected scalar checksum, verified outside measurement.
    private long expectedSum;

    /// Generates data and validates full output and state retained after skipping a prefix.
    @Setup
    public void setUp() throws IOException {
        content = new byte[1024 * 1024 + 3];
        new Random(42).nextBytes(content);
        byte[] expected = content.clone();
        newTransform().transform(expected, 0, expected.length);
        for (byte value : expected) expectedSum += Byte.toUnsignedInt(value);
        try (var input = newInput()) {
            if (!Arrays.equals(expected, input.readAllBytes())) throw new IllegalStateException("Transform mismatch");
        }
        try (var input = newInput()) {
            long skipped = input.skip(8193);
            if (skipped != 8193 || !Arrays.equals(Arrays.copyOfRange(expected, 8193, expected.length), input.readAllBytes())) {
                throw new IllegalStateException("Transform state lost after skipping");
            }
        }
        long actual = consume();
        if (actual != (access.equals("scalar") ? expectedSum : content.length)) {
            throw new IllegalStateException("Unexpected consumed data");
        }
    }

    /// Creates independent state for one decoding sequence.
    private ByteTransform newTransform() {
        return switch (filter) {
            case "delta" -> new DeltaTransform(ByteTransform.Direction.DECODE, 4);
            case "x86" -> BCJTransforms.x86(ByteTransform.Direction.DECODE, 0);
            default -> throw new IllegalArgumentException(filter);
        };
    }

    /// Opens a new transform stream over the common source bytes.
    private TransformingInputStream newInput() {
        return new TransformingInputStream(new ByteArrayInputStream(content), newTransform());
    }

    /// Consumes the complete stream and closes it, returning the byte count or scalar checksum.
    @Benchmark
    public long consume() throws IOException {
        try (var input = newInput()) {
            long result = 0;
            switch (access) {
                case "bulk" -> {
                    int count;
                    while ((count = input.read(output)) >= 0) result += count;
                }
                case "scalar" -> {
                    int value;
                    while ((value = input.read()) >= 0) result += value;
                }
                case "skip" -> {
                    long count;
                    while ((count = input.skip(257)) > 0) result += count;
                }
                default -> throw new IllegalArgumentException(access);
            }
            return result;
        }
    }
}
