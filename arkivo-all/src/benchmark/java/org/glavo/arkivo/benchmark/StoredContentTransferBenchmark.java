// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.benchmark;

import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoStoredContent;
import org.glavo.arkivo.archive.internal.StoredContentSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/// Measures copies between existing memory-backed content handles, including transfer-channel lifecycles.
@NotNullByDefault
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class StoredContentTransferBenchmark {
    /// Bytes copied by each operation.
    @Param({"4096", "1048576"})
    public int size;

    /// Storage owning the benchmark content.
    private final ArkivoEditStorage storage = ArkivoEditStorage.memory();

    /// Populated immutable source for measured copies.
    private @Nullable ArkivoStoredContent source;

    /// Destination whose allocated capacity is reused after each truncation.
    private @Nullable ArkivoStoredContent destination;

    /// Populates deterministic content and verifies an initial full copy before measurements.
    @Setup
    public void setUp() throws IOException {
        byte[] bytes = new byte[size];
        new Random(0).nextBytes(bytes);
        source = storage.createContent("source", size);
        destination = storage.createContent("destination", size);
        try (var output = source.openChannel(Set.of(StandardOpenOption.WRITE))) {
            StoredContentSupport.copyInput(new ByteArrayInputStream(bytes), output, size);
        }
        if (copy() != size) throw new IllegalStateException("Copy size mismatch");
        try (var input = StoredContentSupport.openInputStream(destination)) {
            if (!Arrays.equals(bytes, input.readAllBytes())) throw new IllegalStateException("Copy content mismatch");
        }
    }

    /// Copies through the shared channel-owning entry point and returns the resulting length.
    @Benchmark
    public long copy() throws IOException {
        ArkivoStoredContent target = Objects.requireNonNull(destination);
        StoredContentSupport.copyContent(Objects.requireNonNull(source), target);
        return target.size();
    }

    /// Releases benchmark content and storage.
    @TearDown
    public void tearDown() throws IOException {
        try (storage; @Nullable ArkivoStoredContent input = source; @Nullable ArkivoStoredContent output = destination) {
            // Resource cleanup is independent of transfer-channel cleanup inside each copy.
        }
    }
}
