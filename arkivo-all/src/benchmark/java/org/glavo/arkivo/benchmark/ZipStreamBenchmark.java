// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.benchmark;

import org.glavo.arkivo.archive.zip.ZipArkivoStreamingReader;
import org.glavo.arkivo.archive.zip.ZipArkivoStreamingWriter;
import org.apache.commons.compress.archivers.zip.*;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.openjdk.jmh.annotations.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.zip.*;

/// Compares in-memory streaming ZIP reads and writes for many small entries.
@NotNullByDefault
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class ZipStreamBenchmark {
    /// The streaming implementation.
    @Param({"arkivo", "jdk", "commons"}) public String api = "arkivo";
    /// One MiB of uncompressed data is stored in each archive.
    private static final int COUNT = 256;
    /// The entry payload, half repeated and half random.
    private final byte[] body = new byte[4096];
    /// Precomputed entry names.
    private final String @Unmodifiable [] names = IntStream.range(0, COUNT)
            .mapToObj(index -> "entry-" + index + ".bin").toArray(String[]::new);
    /// The common JDK-produced archive used by all readers.
    private byte[] archive = new byte[0];
    /// The reusable buffer used by each reader.
    private final byte[] buffer = new byte[8192];
    /// Prepares the common input and checks the selected writer with the JDK reader.
    @Setup
    public void setup() throws IOException {
        new Random(42).nextBytes(body);
        System.arraycopy(body, 0, body, 2048, 2048);
        String selected = api;
        api = "jdk"; archive = encoded(); api = selected;
        byte[] output = encoded();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(output))) {
            int count = 0;
            while (in.getNextEntry() != null) {
                if (!Arrays.equals(body, in.readAllBytes())) throw new AssertionError("Writer mismatch");
                count++;
            }
            if (count != COUNT) throw new AssertionError(count);
        }
        if (read() != (long) COUNT * body.length) throw new AssertionError("Reader mismatch");
        System.out.println("SURVEY_OUTPUT_BYTES " + api + " " + output.length);
    }
    /// Reads every entry from the same in-memory ZIP.
    @Benchmark
    public long read() throws IOException {
        var source = new ByteArrayInputStream(archive);
        long bytes = 0;
        switch (api) {
            case "arkivo":
                try (var reader = ZipArkivoStreamingReader.open(source)) {
                    while (reader.next()) try (InputStream in = reader.openInputStream()) { bytes += drain(in); }
                }
                break;
            case "jdk":
                try (var reader = new ZipInputStream(source)) {
                    while (reader.getNextEntry() != null) bytes += drain(reader);
                }
                break;
            case "commons":
                try (var reader = new ZipArchiveInputStream(source)) {
                    while (reader.getNextEntry() != null) bytes += drain(reader);
                }
                break;
            default: throw new IllegalArgumentException(api);
        }
        return bytes;
    }
    /// Consumes an entry with the shared read buffer.
    private long drain(InputStream in) throws IOException {
        long bytes = 0; int n;
        while ((n = in.read(buffer)) >= 0) bytes += n;
        return bytes;
    }
    /// Writes a complete archive including its central directory into memory.
    @Benchmark
    public byte[] write() throws IOException { return encoded(); }
    /// Creates equivalent default-Deflate ZIP entries through the selected API.
    private byte[] encoded() throws IOException {
        var target = new ByteArrayOutputStream(1024 * 1024);
        switch (api) {
            case "arkivo":
                try (var writer = ZipArkivoStreamingWriter.open(target)) {
                    for (String name : names) try (var out = writer.beginFile(name).openOutputStream()) { out.write(body); }
                }
                break;
            case "jdk":
                try (var writer = new ZipOutputStream(target)) {
                    writer.setLevel(6);
                    for (String name : names) { writer.putNextEntry(new ZipEntry(name)); writer.write(body); writer.closeEntry(); }
                }
                break;
            case "commons":
                try (var writer = new ZipArchiveOutputStream(target)) {
                    writer.setLevel(6);
                    for (String name : names) { writer.putArchiveEntry(new ZipArchiveEntry(name)); writer.write(body); writer.closeArchiveEntry(); }
                }
                break;
            default: throw new IllegalArgumentException(api);
        }
        return target.toByteArray();
    }
}
