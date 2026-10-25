// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.benchmark;

import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.openjdk.jmh.annotations.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
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
    /// Opens an index, loads every entry's attributes, or reads one entry from an existing index.
    @Param({"open", "read"}) public String operation = "read";
    /// The number of data entries in their common directory.
    @Param({"2048"}) public int entryCount = 2048;
    /// The uncompressed bytes in one entry.
    @Param({"4096"}) public int entrySize = 4096;
    /// The number of implicit parent directories traversed before each data entry.
    @Param({"0"}) public int pathDepth = 0;
    /// The read entry point: stream, channel, Files.readAllBytes, or InputStream.readAllBytes.
    @Param({"stream"}) public String access = "stream";
    /// The reused caller buffer size for stream and channel read loops, in bytes.
    @Param({"8192"}) public int readBufferSize = 8192;
    /// Uses repeated random prefixes or incompressible random contents for each entry.
    @Param({"repeated"}) public String contentPattern = "repeated";
    /// The common relative directory prefix, including its trailing separator when nonempty.
    private String directoryPrefix = "";
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
    private byte[] buffer = new byte[0];
    /// A view of the reusable read storage for seekable-channel reads.
    private ByteBuffer channelBuffer = ByteBuffer.allocate(0);
    /// Cached entry names, excluding name formatting from entry-read timing.
    private @Nullable String[] names = new String[0];
    /// Cached NIO paths, excluding path parsing from entry-read timing.
    private @Nullable Path[] paths = new Path[0];

    /// Creates a common JDK-produced archive and verifies one decoded entry.
    @Setup
    public void setup() throws IOException {
        if (entryCount <= 0 || entrySize < 0 || pathDepth < 0 || readBufferSize <= 0) {
            throw new IllegalArgumentException("Invalid archive dimensions");
        }
        if (!Set.of("open", "attributes", "read").contains(operation)) {
            throw new IllegalArgumentException(operation);
        }
        if (operation.equals("attributes") && !Set.of("arkivo", "arkivo-channel", "zipfs").contains(api)) {
            throw new IllegalArgumentException("Attribute access requires a NIO file system");
        }
        if (!Set.of("stream", "channel", "allBytes", "streamAllBytes").contains(access)) {
            throw new IllegalArgumentException(access);
        }
        if ((access.equals("channel") || access.equals("allBytes"))
                && !Set.of("arkivo", "arkivo-channel", "zipfs").contains(api)) {
            throw new IllegalArgumentException("This access mode requires a NIO file system");
        }
        buffer = new byte[readBufferSize];
        channelBuffer = ByteBuffer.wrap(buffer);
        if (!Set.of("repeated", "random").contains(contentPattern)) {
            throw new IllegalArgumentException(contentPattern);
        }
        directoryPrefix = "parent/".repeat(pathDepth);
        names = new String[entryCount];
        paths = new Path[entryCount];
        archive = Files.createTempFile("arkivo-performance-", ".zip");
        byte[] body = new byte[entrySize];
        new Random(42).nextBytes(body);
        int period = Math.max(1, Math.min(entrySize / 2, 16_384));
        if (contentPattern.equals("repeated")) {
            for (int offset = period; offset < entrySize; offset += period) {
                System.arraycopy(body, 0, body, offset, Math.min(period, entrySize - offset));
            }
        }
        CRC32 crc = new CRC32(); crc.update(body);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive))) {
            out.setLevel(6);
            for (int i = 0; i < entryCount; i++) {
                names[i] = directoryPrefix + "entry-" + i + ".bin";
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
        if (!operation.equals("read")) closeShared();
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
        if (access.equals("allBytes")) return Files.readAllBytes(Objects.requireNonNull(paths[i]));
        if (access.equals("channel")) {
            try (InputStream in = Channels.newInputStream(Files.newByteChannel(Objects.requireNonNull(paths[i])))) {
                return in.readAllBytes();
            }
        }
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
        if (!operation.equals("read")) {
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
        if (access.equals("allBytes")) {
            return checkSize(Files.readAllBytes(Objects.requireNonNull(paths[cursor])).length);
        }
        if (access.equals("channel")) {
            try (var channel = Files.newByteChannel(Objects.requireNonNull(paths[cursor]))) {
                long size = 0;
                while (true) {
                    channelBuffer.clear();
                    int n = channel.read(channelBuffer);
                    if (n < 0) break;
                    if (n == 0) throw new AssertionError("Channel read made no progress");
                    size += n;
                }
                return checkSize(size);
            }
        }
        try (InputStream in = input(cursor)) {
            if (access.equals("streamAllBytes")) return checkSize(in.readAllBytes().length);
            long size = 0;
            int n;
            while ((n = in.read(buffer)) >= 0) size += n;
            return checkSize(size);
        }
    }
    /// Checks the complete decoded size without adding content hashing to the measured read loop.
    private long checkSize(long size) {
        if (size != entrySize) throw new AssertionError(size);
        return size;
    }
    /// Counts every indexed entry and optionally queries its attributes, triggering Arkivo's lazy metadata loading.
    private long count(FileSystem fs) throws IOException {
        try (var files = Files.list(fs.getPath("/" + directoryPrefix))) {
            if (!operation.equals("attributes")) return files.count();
            long count = 0;
            var iterator = files.iterator();
            while (iterator.hasNext()) {
                var attributes = Files.readAttributes(iterator.next(), java.nio.file.attribute.BasicFileAttributes.class);
                checkSize(attributes.size());
                count++;
            }
            if (count != entryCount) throw new AssertionError(count);
            return count;
        }
    }
    /// Counts every indexed ZIP entry, including entry-object materialization.
    private static long count(Enumeration<?> entries) {
        long count = 0;
        while (entries.hasMoreElements()) { entries.nextElement(); count++; }
        return count;
    }
}
