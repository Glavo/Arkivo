// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.benchmark;

import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoFileSystem;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoStreamingWriter;
import org.glavo.arkivo.archive.sevenzip.SevenZipCompression;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.archive.zip.ZipArkivoStreamingWriter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/// Measures ZIP and 7z indexing and concurrent random-entry reads through NIO file systems.
@NotNullByDefault
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class ArchiveFileSystemBenchmark {
    /// The number of regular files stored in each benchmark archive.
    private static final int ENTRY_COUNT = 2_048;

    /// The uncompressed size of every benchmark entry.
    private static final int ENTRY_SIZE = 4 * 1024;

    /// The temporary benchmark directory.
    private @Nullable Path directory;

    /// The generated ZIP archive path.
    private @Nullable Path zipArchive;

    /// The generated 7z archive path.
    private @Nullable Path sevenZipArchive;

    /// The shared open ZIP file system used by random-read benchmarks.
    private @Nullable ZipArkivoFileSystem zipFileSystem;

    /// The shared open 7z file system used by random-read benchmarks.
    private @Nullable SevenZipArkivoFileSystem sevenZipFileSystem;

    /// Precomputed ZIP entry paths.
    private Path @Unmodifiable [] zipEntries = new Path[0];

    /// Precomputed 7z entry paths.
    private Path @Unmodifiable [] sevenZipEntries = new Path[0];

    /// Creates representative archives and opens the shared random-read views.
    @Setup
    public void setUp() throws IOException {
        Path temporaryRoot = Path.of("build", "tmp");
        Files.createDirectories(temporaryRoot);
        directory = Files.createTempDirectory(temporaryRoot, "arkivo-jmh-");
        zipArchive = directory.resolve("entries.zip");
        sevenZipArchive = directory.resolve("entries.7z");
        createZip(zipArchive);
        createSevenZip(sevenZipArchive);
        zipFileSystem = ZipArkivoFileSystem.open(zipArchive);
        sevenZipFileSystem = SevenZipArkivoFileSystem.open(sevenZipArchive);
        zipEntries = entryPaths(zipFileSystem);
        sevenZipEntries = entryPaths(sevenZipFileSystem);
    }

    /// Closes shared file systems and removes generated benchmark data.
    @TearDown
    public void tearDown() throws IOException {
        @Nullable IOException failure = null;
        failure = close(zipFileSystem, failure);
        failure = close(sevenZipFileSystem, failure);
        Path currentDirectory = directory;
        if (currentDirectory != null) {
            try {
                deleteTree(currentDirectory);
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /// Opens and indexes the generated ZIP archive.
    @Benchmark
    public long openZipIndex() throws IOException {
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                Objects.requireNonNull(zipArchive, "ZIP archive")
        )) {
            return countEntries(fileSystem.getPath("/"));
        }
    }

    /// Opens and indexes the generated 7z archive.
    @Benchmark
    public long openSevenZipIndex() throws IOException {
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(
                Objects.requireNonNull(sevenZipArchive, "7z archive")
        )) {
            return countEntries(fileSystem.getPath("/"));
        }
    }

    /// Reads ZIP entries concurrently from one shared file system.
    @Benchmark
    @Threads(4)
    public int readZipEntry(ReadCursor cursor) throws IOException {
        return Arrays.hashCode(Files.readAllBytes(zipEntries[cursor.next(zipEntries.length)]));
    }

    /// Reads solid 7z entries concurrently from one shared file system.
    @Benchmark
    @Threads(4)
    public int readSevenZipEntry(ReadCursor cursor) throws IOException {
        return Arrays.hashCode(Files.readAllBytes(sevenZipEntries[cursor.next(sevenZipEntries.length)]));
    }

    /// Creates the benchmark ZIP archive through the public streaming writer.
    private static void createZip(Path archive) throws IOException {
        byte[] content = content();
        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archive)) {
            for (int index = 0; index < ENTRY_COUNT; index++) {
                writer.beginFile(entryName(index));
                try (OutputStream output = writer.openOutputStream()) {
                    output.write(content);
                }
            }
        }
    }

    /// Creates the benchmark solid 7z archive through the public streaming writer.
    private static void createSevenZip(Path archive) throws IOException {
        byte[] content = content();
        Map<String, Object> environment = Map.of(
                SevenZipArkivoFileSystem.COMPRESSION.key(),
                SevenZipCompression.lzma2(1024 * 1024),
                SevenZipArkivoFileSystem.SOLID_FILE_COUNT.key(),
                64
        );
        try (SevenZipArkivoStreamingWriter writer =
                     SevenZipArkivoStreamingWriter.create(archive, environment)) {
            for (int index = 0; index < ENTRY_COUNT; index++) {
                writer.beginFile(entryName(index));
                try (OutputStream output = writer.openOutputStream()) {
                    output.write(content);
                }
            }
        }
    }

    /// Returns deterministic compressible entry content.
    private static byte[] content() {
        byte[] content = new byte[ENTRY_SIZE];
        for (int index = 0; index < content.length; index++) {
            content[index] = (byte) (index * 31);
        }
        return content;
    }

    /// Returns the stable path name for one benchmark entry.
    private static String entryName(int index) {
        return String.format(Locale.ROOT, "entry-%04d.bin", index);
    }

    /// Returns all expected entry paths for one open archive file system.
    private static Path @Unmodifiable [] entryPaths(java.nio.file.FileSystem fileSystem) {
        Path[] entries = new Path[ENTRY_COUNT];
        for (int index = 0; index < entries.length; index++) {
            entries[index] = fileSystem.getPath("/" + entryName(index));
        }
        return entries;
    }

    /// Counts direct entries beneath the archive root.
    private static long countEntries(Path root) throws IOException {
        try (Stream<Path> entries = Files.list(root)) {
            return entries.count();
        }
    }

    /// Closes one optional file system while preserving an earlier failure.
    private static @Nullable IOException close(
            @Nullable java.nio.file.FileSystem fileSystem,
            @Nullable IOException failure
    ) {
        if (fileSystem == null) {
            return failure;
        }
        try {
            fileSystem.close();
        } catch (IOException exception) {
            if (failure == null) {
                return exception;
            }
            failure.addSuppressed(exception);
        }
        return failure;
    }

    /// Deletes a generated benchmark directory from leaves to root.
    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            @Unmodifiable List<Path> ordered = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path path : ordered) {
                Files.deleteIfExists(path);
            }
        }
    }

    /// Supplies an independent round-robin entry cursor to each benchmark thread.
    @NotNullByDefault
    @State(Scope.Thread)
    public static class ReadCursor {
        /// The next logical entry ordinal.
        private int index;

        /// Returns the next entry index and advances the cursor.
        public int next(int entryCount) {
            int current = index;
            index = current + 1 == entryCount ? 0 : current + 1;
            return current;
        }
    }
}