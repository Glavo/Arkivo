// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.benchmark;

import org.glavo.arkivo.archive.zip.internal.ZipLearnedMetadataDecoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.openjdk.jmh.annotations.*;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/// Measures the first non-ASCII decode in a fresh process, including model and charset-provider initialization.
@NotNullByDefault
@State(Scope.Thread)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class ZipMetadataLoadBenchmark {
    /// Loads the shared coefficients and strictly decodes a short UTF-8 field once.
    /// @return decoded text, consumed by JMH
    @Benchmark
    public String load() {
        return new ZipLearnedMetadataDecoder().decode(ByteBuffer.wrap(new byte[]{(byte) 0xc3, (byte) 0xa9}));
    }
}
