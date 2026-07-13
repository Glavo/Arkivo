// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoFileSystem;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoStreamingWriter;
import org.glavo.arkivo.archive.sevenzip.SevenZipCompression;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.archive.zip.ZipArkivoStreamingWriter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Stress-tests repeated concurrent reads from shared ZIP and solid 7z file systems.
@NotNullByDefault
final class ArchiveConcurrentReadStressTest {
    /// The number of entries in each generated archive.
    private static final int ENTRY_COUNT = 32;

    /// The decoded size of each generated entry.
    private static final int ENTRY_SIZE = 32 * 1024;

    /// The number of simultaneous reader tasks.
    private static final int THREAD_COUNT = 8;

    /// The number of complete entry reads performed by each task.
    private static final int READS_PER_THREAD = 24;

    /// Verifies shared random-access file systems preserve entry data under concurrent decoding.
    @Test
    @Timeout(value = 90)
    void concurrentZipAndSolidSevenZipReads(@TempDir Path directory) throws Exception {
        Path zipArchive = directory.resolve("concurrent.zip");
        Path sevenZipArchive = directory.resolve("concurrent.7z");
        createZip(zipArchive);
        createSevenZip(sevenZipArchive);

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(zipArchive)) {
            stress(fileSystem);
        }
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(sevenZipArchive)) {
            stress(fileSystem);
        }
    }

    /// Creates a compressed ZIP fixture with deterministic entry data.
    private static void createZip(Path archive) throws IOException {
        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archive)) {
            for (int index = 0; index < ENTRY_COUNT; index++) {
                writer.beginFile(entryName(index));
                try (OutputStream output = writer.openOutputStream()) {
                    output.write(content(index));
                }
            }
        }
    }

    /// Creates an LZMA2-compressed 7z fixture whose entries share solid folders.
    private static void createSevenZip(Path archive) throws IOException {
        @Unmodifiable Map<String, Object> environment = Map.of(
                SevenZipArkivoFileSystem.COMPRESSION.key(),
                SevenZipCompression.lzma2(1024 * 1024),
                SevenZipArkivoFileSystem.SOLID_FILE_COUNT.key(),
                16
        );
        try (SevenZipArkivoStreamingWriter writer =
                     SevenZipArkivoStreamingWriter.create(archive, environment)) {
            for (int index = 0; index < ENTRY_COUNT; index++) {
                writer.beginFile(entryName(index));
                try (OutputStream output = writer.openOutputStream()) {
                    output.write(content(index));
                }
            }
        }
    }

    /// Runs synchronized reader tasks against one shared archive file system.
    private static void stress(ArkivoFileSystem fileSystem) throws Exception {
        assertEquals(ArkivoFileSystemThreadSafety.CONCURRENT_READ, fileSystem.threadSafety());
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>(THREAD_COUNT);
        try {
            for (int worker = 0; worker < THREAD_COUNT; worker++) {
                int workerIndex = worker;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10L, TimeUnit.SECONDS)) {
                        throw new AssertionError("Concurrent archive readers did not start together");
                    }
                    for (int iteration = 0; iteration < READS_PER_THREAD; iteration++) {
                        int entryIndex = (workerIndex * READS_PER_THREAD + iteration) % ENTRY_COUNT;
                        byte[] actual = Files.readAllBytes(fileSystem.getPath("/" + entryName(entryIndex)));
                        if (!Arrays.equals(content(entryIndex), actual)) {
                            throw new AssertionError("Concurrent archive read returned incorrect data");
                        }
                    }
                    return true;
                }));
            }
            assertTrue(ready.await(10L, TimeUnit.SECONDS), "Reader tasks did not become ready");
            start.countDown();
            for (Future<Boolean> future : futures) {
                assertTrue(future.get(60L, TimeUnit.SECONDS));
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10L, TimeUnit.SECONDS), "Reader tasks did not terminate");
        }
    }

    /// Returns deterministic content unique to one entry ordinal.
    private static byte[] content(int entryIndex) {
        byte[] content = new byte[ENTRY_SIZE];
        for (int index = 0; index < content.length; index++) {
            content[index] = (byte) (index * 31 + entryIndex * 17);
        }
        return content;
    }

    /// Returns the stable archive path for one entry ordinal.
    private static String entryName(int index) {
        return String.format(Locale.ROOT, "entry-%02d.bin", index);
    }
}