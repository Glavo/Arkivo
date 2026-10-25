// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.benchmark;

import org.glavo.arkivo.codec.RawCompressionDictionary;
import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.internal.PrefixReplayReadableByteChannel;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnmodifiableView;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/// Measures dictionary snapshots and fragmented replay of probe prefixes from heap and direct buffers.
@NotNullByDefault
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class BufferCopyBenchmark {
    /// Whether source and destination buffers use direct storage.
    @Param({"false", "true"})
    public boolean direct;

    /// The dictionary content length, excluding guard bytes.
    @Param({"32768"})
    public int size;

    /// Number of bytes requested in each replay read.
    @Param({"32"})
    public int chunkSize;

    /// Read-only input window copied by the dictionary factory.
    private @UnmodifiableView ByteBuffer input = ByteBuffer.allocate(0).asReadOnlyBuffer();

    /// Read-only 512-byte probe window, with a nonzero starting position.
    private @UnmodifiableView ByteBuffer prefix = ByteBuffer.allocate(0).asReadOnlyBuffer();

    /// Reused replay destination.
    private ByteBuffer target = ByteBuffer.allocate(0);

    /// Initializes deterministic data and checks complete snapshot and replay contents.
    @Setup
    public void setUp() throws IOException {
        if (size < 512 || chunkSize <= 0) throw new IllegalArgumentException("Invalid buffer size");
        byte[] expected = new byte[size];
        for (int index = 0; index < size; index++) expected[index] = (byte) (index * 31 + (index >>> 8));
        ByteBuffer storage = direct ? ByteBuffer.allocateDirect(size + 2) : ByteBuffer.allocate(size + 2);
        storage.position(1).put(expected).flip().position(1);
        input = storage.asReadOnlyBuffer();
        prefix = input.asReadOnlyBuffer().limit(513);
        target = direct ? ByteBuffer.allocateDirect(chunkSize) : ByteBuffer.allocate(chunkSize);
        if (!Arrays.equals(expected, RawCompressionDictionary.of(input).bytes()) || input.position() != 1) {
            throw new IllegalStateException("Dictionary content or source position mismatch");
        }
        verifyReplay(new org.glavo.arkivo.archive.internal.PrefixReplayReadableByteChannel(prefix, EmptySource.INSTANCE), expected);
        verifyReplay(PrefixReplayReadableByteChannel.create(prefix, EmptySource.INSTANCE, ResourceOwnership.OWNED), expected);
    }

    /// Checks every replay byte and source-cursor isolation before measurements.
    private void verifyReplay(ReadableByteChannel replay, byte[] expected) throws IOException {
        try (replay) {
            int offset = 0;
            int count;
            while ((count = replay.read(target.clear())) >= 0) {
                if (count == 0) throw new IllegalStateException("Replay made no progress");
                for (int index = 0; index < count; index++) {
                    if (target.get(index) != expected[offset++]) throw new IllegalStateException("Replay content mismatch");
                }
            }
            if (offset != 512 || prefix.position() != 1) throw new IllegalStateException("Replay boundary mismatch");
        }
    }

    /// Creates an immutable raw dictionary from a non-consuming source window.
    @Benchmark
    public RawCompressionDictionary rawDictionary() {
        return RawCompressionDictionary.of(input);
    }

    /// Creates and drains an archive probe replay channel.
    @Benchmark
    public int archivePrefix() throws IOException {
        return drain(new org.glavo.arkivo.archive.internal.PrefixReplayReadableByteChannel(prefix, EmptySource.INSTANCE));
    }

    /// Creates and drains a compression probe replay channel.
    @Benchmark
    public int codecPrefix() throws IOException {
        return drain(PrefixReplayReadableByteChannel.create(prefix, EmptySource.INSTANCE, ResourceOwnership.OWNED));
    }

    /// Consumes replayed bytes in fixed-size reads, including the final source EOF and channel close.
    private int drain(ReadableByteChannel replay) throws IOException {
        try (replay) {
            int result = 0;
            int count;
            while ((count = replay.read(target.clear())) >= 0) {
                result += count + target.get(0);
            }
            return result;
        }
    }

    /// Supplies EOF without allocating a new backing source for every measured replay session.
    @NotNullByDefault
    private enum EmptySource implements ReadableByteChannel {
        /// Shared stateless source whose closure has no effect.
        INSTANCE;

        /// Returns EOF, or zero for an empty destination.
        @Override
        public int read(ByteBuffer destination) { return destination.hasRemaining() ? -1 : 0; }

        /// Returns true because this stateless source holds no resources.
        @Override
        public boolean isOpen() { return true; }

        /// Releases no resources.
        @Override
        public void close() { }
    }
}
