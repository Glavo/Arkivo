// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.benchmark;

import org.glavo.arkivo.archive.tar.TarArkivoFileSystem;
import org.glavo.arkivo.archive.tar.TarArkivoStreamingWriter;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/// Measures TAR index loading and enumeration with shared implicit parent directories.
@NotNullByDefault
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class TarIndexBenchmark {
    /// Number of empty regular entries in the generated archive.
    @Param({"2048"})
    public int entryCount;

    /// Number of shared implicit directories above every regular entry.
    @Param({"0", "8", "32"})
    public int pathDepth;

    /// Generated uncompressed archive, removed after each trial.
    private @Nullable Path archive;

    /// Common relative directory text, including its trailing separator when nonempty.
    private String prefix = "";

    /// Generates deterministic empty entries and verifies the complete visible namespace.
    @Setup
    public void setUp() throws IOException {
        if (entryCount <= 0 || pathDepth < 0) throw new IllegalArgumentException("Invalid archive dimensions");
        prefix = "parent/".repeat(pathDepth);
        archive = Files.createTempFile("arkivo-tar-index-", ".tar");
        try (var writer = TarArkivoStreamingWriter.create(archive)) {
            for (int index = 0; index < entryCount; index++) {
                writer.beginFile(prefix + "entry-" + index).close();
            }
        }
        try (var fileSystem = TarArkivoFileSystem.open(archive)) {
            var directory = fileSystem.getPath("/");
            for (int depth = 0; depth < pathDepth; depth++) {
                try (var children = Files.list(directory)) {
                    var entries = children.toList();
                    if (entries.size() != 1 || !entries.get(0).equals(directory.resolve("parent"))) {
                        throw new IllegalStateException("Unexpected ancestor directory");
                    }
                }
                directory = directory.resolve("parent");
            }
            for (int index = 0; index < entryCount; index++) {
                var attributes = Files.readAttributes(directory.resolve("entry-" + index),
                        java.nio.file.attribute.BasicFileAttributes.class);
                if (!attributes.isRegularFile() || attributes.size() != 0) {
                    throw new IllegalStateException("Unexpected entry attributes");
                }
            }
        }
        if (openIndex() != entryCount) throw new IllegalStateException("Unexpected entry count");
    }

    /// Loads the full index, enumerates every regular entry, and closes the file system.
    @Benchmark
    public long openIndex() throws IOException {
        try (var fileSystem = TarArkivoFileSystem.open(Objects.requireNonNull(archive));
             var entries = Files.list(fileSystem.getPath("/" + prefix))) {
            return entries.count();
        }
    }

    /// Removes the generated archive after all measured file systems have closed.
    @TearDown
    public void tearDown() throws IOException {
        Files.delete(Objects.requireNonNull(archive));
    }
}
