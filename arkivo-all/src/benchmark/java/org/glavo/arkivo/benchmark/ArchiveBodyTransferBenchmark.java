// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.benchmark;

import org.glavo.arkivo.archive.internal.ArchiveOutputStream;
import org.glavo.arkivo.archive.internal.ReadOnlyByteArrayChannel;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
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
import java.io.OutputStream;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

/// Measures exact body copies within a 256-entry archive output session, including session allocation.
@NotNullByDefault
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class ArchiveBodyTransferBenchmark {
    /// Bytes copied for each body.
    @Param({"4096", "65536"})
    public int size;

    /// Rewindable source reused across sessions.
    private @Nullable ReadOnlyByteArrayChannel source;

    /// Creates deterministic input and verifies the complete batch checksum and byte count.
    @Setup
    public void setUp() throws IOException {
        byte[] content = new byte[size];
        new Random(0).nextBytes(content);
        source = new ReadOnlyByteArrayChannel(content);
        CRC32 expected = new CRC32();
        for (int entry = 0; entry < 256; entry++) expected.update(content);
        if (copyBodies() != (expected.getValue() ^ (256L * size))) {
            throw new IllegalStateException("Body transfer mismatch");
        }
    }

    /// Releases the source after measurement.
    @TearDown
    public void tearDown() {
        if (source != null) source.close();
    }

    /// Copies one batch to a checksum sink; reported time and allocation are per body.
    @Benchmark
    @OperationsPerInvocation(256)
    public long copyBodies() throws IOException {
        ReadOnlyByteArrayChannel input = Objects.requireNonNull(source);
        ChecksumOutput target = new ChecksumOutput();
        try (ArchiveOutputStream output = new ArchiveOutputStream(target)) {
            for (int entry = 0; entry < 256; entry++) {
                input.position(0L);
                output.writeBody(input, size);
            }
            return target.checksum.getValue() ^ output.bytesWritten();
        }
    }

    /// Consumes every output byte without retaining an archive-sized array.
    @NotNullByDefault
    private static final class ChecksumOutput extends OutputStream {
        /// Checksum of all bytes written in the session.
        private final CRC32 checksum = new CRC32();

        /// Accumulates a scalar write.
        @Override
        public void write(int value) {
            checksum.update(value);
        }

        /// Accumulates a bulk write.
        @Override
        public void write(byte[] bytes, int offset, int length) {
            checksum.update(bytes, offset, length);
        }
    }
}
