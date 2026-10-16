// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.benchmark;

import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.openjdk.jmh.annotations.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;

/// Compares hot-cache ZIP indexing and entry reads using identical archives.
@NotNullByDefault
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class ZipComparisonBenchmark {
    /// The indexed access implementation.
    @Param({"arkivo", "zipfs", "jdk", "commons"}) public String api = "arkivo";
    /// The ZIP storage method.
    @Param({"stored", "deflated"}) public String method = "deflated";
    /// Opens and closes the index or reads one entry from an existing index.
    @Param({"open", "read"}) public String operation = "read";
    /// The number of flat archive entries.
    @Param({"2048"}) public int entryCount = 2048;
    /// The uncompressed bytes in one entry.
    @Param({"4096"}) public int entrySize = 4096;
    /// The generated archive.
    private @Nullable Path archive;
    /// An optional pre-opened NIO view.
    private @Nullable FileSystem fs;
    /// An optional pre-opened JDK index.
    private @Nullable ZipFile jdk;
    /// An optional pre-opened Commons index.
    private @Nullable org.apache.commons.compress.archivers.zip.ZipFile commons;
    /// The next deterministic pseudo-random entry index.
    private int cursor;
    /// Reusable read storage shared by all implementations.
    private final byte[] buffer = new byte[8192];
    /// Cached entry names, excluding name formatting from entry-read timing.
    private @Nullable String[] names = new String[0];
    /// Cached NIO paths, excluding path parsing from entry-read timing.
    private @Nullable Path[] paths = new Path[0];

    /// Creates a common JDK-produced archive and verifies one decoded entry.
    @Setup
    public void setup() throws IOException {
        if (entryCount <= 0 || entrySize < 0) throw new IllegalArgumentException("Invalid archive dimensions");
        names = new String[entryCount];
        paths = new Path[entryCount];
        archive = Files.createTempFile("arkivo-performance-", ".zip");
        byte[] body = new byte[entrySize];
        new Random(42).nextBytes(body);
        int period = Math.max(1, Math.min(entrySize / 2, 16_384));
        for (int offset = period; offset < entrySize; offset += period) {
            System.arraycopy(body, 0, body, offset, Math.min(period, entrySize - offset));
        }
        CRC32 crc = new CRC32(); crc.update(body);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive))) {
            out.setLevel(6);
            for (int i = 0; i < entryCount; i++) {
                names[i] = "entry-" + i + ".bin";
                ZipEntry e = new ZipEntry(Objects.requireNonNull(names[i]));
                e.setMethod(method.equals("stored") ? ZipEntry.STORED : ZipEntry.DEFLATED);
                e.setTimeLocal(java.time.LocalDateTime.of(2020, 1, 2, 3, 4, 6));
                e.setSize(entrySize); e.setCrc(crc.getValue());
                out.putNextEntry(e); out.write(body); out.closeEntry();
            }
        }
        System.out.printf("Archive: %d entries, %d bytes/entry, %d ZIP bytes%n",
                entryCount, entrySize, Files.size(archive));
        openShared();
        if (!Arrays.equals(body, readOne(0))) throw new AssertionError("Decoded entry differs");
        if (operation.equals("open")) closeShared();
    }
    /// Opens exactly one implementation; no second JDK ZipFile keeps its shared index cached.
    private void openShared() throws IOException {
        switch (api) {
            case "arkivo" -> fs = ZipArkivoFileSystem.open(Objects.requireNonNull(archive));
            case "arkivo-channel" -> fs = ZipArkivoFileSystem.open(Files.newByteChannel(Objects.requireNonNull(archive)));
            case "zipfs" -> fs = FileSystems.newFileSystem(Objects.requireNonNull(archive), Map.of());
            case "jdk" -> jdk = new ZipFile(Objects.requireNonNull(archive).toFile());
            case "commons" -> commons = org.apache.commons.compress.archivers.zip.ZipFile.builder()
                    .setPath(Objects.requireNonNull(archive)).get();
            default -> throw new IllegalArgumentException(api);
        }
        if (fs != null) for (int i = 0; i < entryCount; i++) paths[i] = fs.getPath(Objects.requireNonNull(names[i]));
    }
    /// Opens the requested entry using the active API.
    private InputStream input(int i) throws IOException {
        if (fs != null) return Files.newInputStream(Objects.requireNonNull(paths[i]));
        if (jdk != null) return jdk.getInputStream(jdk.getEntry(Objects.requireNonNull(names[i])));
        var zip = Objects.requireNonNull(commons);
        return zip.getInputStream(zip.getEntry(Objects.requireNonNull(names[i])));
    }
    /// Reads setup validation bytes outside the measurement.
    private byte[] readOne(int i) throws IOException {
        try (InputStream in = input(i)) { return in.readAllBytes(); }
    }
    /// Releases a pre-opened index.
    private void closeShared() throws IOException {
        if (fs != null) { fs.close(); fs = null; }
        if (jdk != null) { jdk.close(); jdk = null; }
        if (commons != null) { commons.close(); commons = null; }
    }
    /// Releases the index and generated archive after a trial.
    @TearDown
    public void cleanup() throws IOException {
        closeShared(); Files.delete(Objects.requireNonNull(archive));
    }
    /// Measures one index lifecycle or one complete entry read of the configured size.
    @Benchmark
    public long run() throws IOException {
        if (operation.equals("open")) {
            Path p = Objects.requireNonNull(archive);
            switch (api) {
                case "arkivo": try (var x = ZipArkivoFileSystem.open(p)) { return count(x); }
                case "arkivo-channel": try (var x = ZipArkivoFileSystem.open(Files.newByteChannel(p))) { return count(x); }
                case "zipfs": try (var x = FileSystems.newFileSystem(p, Map.of())) { return count(x); }
                case "jdk": try (var x = new ZipFile(p.toFile())) { return count(x.entries()); }
                case "commons": try (var x = org.apache.commons.compress.archivers.zip.ZipFile.builder().setPath(p).get()) { return count(x.getEntries()); }
                default: throw new IllegalArgumentException(api);
            }
        }
        cursor = (int) (((long) cursor + 509) % entryCount);
        try (InputStream in = input(cursor)) {
            long size = 0;
            int n;
            while ((n = in.read(buffer)) >= 0) size += n;
            if (size != entrySize) throw new AssertionError(size);
            return size;
        }
    }
    /// Forces lazy index initialization and counts every flat file-system entry.
    private static long count(FileSystem fs) throws IOException {
        try (var files = Files.list(fs.getPath("/"))) { return files.count(); }
    }
    /// Counts every indexed ZIP entry, including entry-object materialization.
    private static long count(Enumeration<?> entries) {
        long count = 0;
        while (entries.hasMoreElements()) { entries.nextElement(); count++; }
        return count;
    }
}
