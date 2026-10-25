// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.benchmark;

import org.glavo.arkivo.archive.ar.ArArkivoEntryAttributes;
import org.glavo.arkivo.archive.ar.ArArkivoStreamingReader;
import org.glavo.arkivo.archive.ar.ArArkivoStreamingWriter;
import org.jetbrains.annotations.NotNullByDefault;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/// Measures AR streaming enumeration while discarding unread member bodies and padding.
@NotNullByDefault
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class ArStreamBenchmark {
    /// Number of regular members in the generated archive.
    @Param({"256"})
    public int entryCount;

    /// Member size, including empty, padded, and multi-buffer bodies.
    @Param({"0", "1", "4096", "8193"})
    public int bodySize;

    /// Complete generated archive, shared read-only by measured reader instances.
    private byte @Unmodifiable [] archive = new byte[0];

    /// Generates deterministic members and verifies their names, sizes, and complete contents.
    @Setup
    public void setUp() throws IOException {
        byte[] content = new byte[bodySize];
        for (int index = 0; index < content.length; index++) content[index] = (byte) index;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var writer = ArArkivoStreamingWriter.open(output)) {
            for (int index = 0; index < entryCount; index++) {
                try (var body = writer.beginFile("entry-" + index).openOutputStream()) {
                    body.write(content);
                }
            }
        }
        archive = output.toByteArray();
        try (var reader = ArArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            for (int index = 0; index < entryCount; index++) {
                if (!reader.next()) throw new IllegalStateException("Missing member");
                var attributes = reader.readAttributes(ArArkivoEntryAttributes.class);
                if (!attributes.path().equals("entry-" + index) || attributes.size() != bodySize) {
                    throw new IllegalStateException("Unexpected member attributes");
                }
                try (var body = reader.openInputStream()) {
                    if (!Arrays.equals(content, body.readAllBytes())) {
                        throw new IllegalStateException("Unexpected member content");
                    }
                }
            }
            if (reader.next()) throw new IllegalStateException("Unexpected trailing member");
        }
        if (enumerate() != entryCount) throw new IllegalStateException("Unexpected member count");
    }

    /// Advances through every member and the end of the archive without opening member bodies.
    @Benchmark
    public int enumerate() throws IOException {
        int count = 0;
        try (var reader = ArArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            while (reader.next()) count++;
        }
        return count;
    }
}
