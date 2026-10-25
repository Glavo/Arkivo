// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.benchmark;

import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
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
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipOutputStream;

/// Measures complete and early-terminating traversal of archive path components without entry I/O.
@NotNullByDefault
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class ArchivePathIterationBenchmark {
    /// Number of components in the path.
    @Param({"8", "64"})
    public int depth;

    /// Empty archive backing the path's file system.
    private @Nullable Path archive;

    /// File system owning the benchmark path.
    private @Nullable ZipArkivoFileSystem fileSystem;

    /// Immutable path reused by all invocations.
    private @Nullable Path path;

    /// Creates an empty archive and verifies iteration over the generated path.
    @Setup
    public void setUp() throws IOException {
        archive = Files.createTempFile("arkivo-path-jmh-", ".zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.finish();
        }
        fileSystem = ZipArkivoFileSystem.open(archive);
        path = fileSystem.getPath("/" + "component/".repeat(depth));
        int count = 0;
        for (Path component : path) {
            if (!component.toString().equals("component") || component.isAbsolute()) {
                throw new IllegalStateException("Unexpected path component");
            }
            count++;
        }
        if (count != depth) {
            throw new IllegalStateException("Unexpected component count");
        }
    }

    /// Releases the file system and temporary archive.
    @TearDown
    public void tearDown() throws IOException {
        try {
            if (fileSystem != null) {
                fileSystem.close();
            }
        } finally {
            if (archive != null) {
                Files.delete(archive);
            }
        }
    }

    /// Consumes only the first path component.
    @Benchmark
    public Path firstComponent() {
        return Objects.requireNonNull(path).iterator().next();
    }

    /// Consumes each component so component-path allocations remain observable.
    @Benchmark
    public void allComponents(Blackhole blackhole) {
        for (Path component : Objects.requireNonNull(path)) {
            blackhole.consume(component);
        }
    }
}
