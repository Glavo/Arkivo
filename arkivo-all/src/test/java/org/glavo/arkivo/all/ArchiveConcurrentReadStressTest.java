// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArchiveOptions;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoFileSystem;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoStreamingWriter;
import org.glavo.arkivo.archive.sevenzip.SevenZipCompression;
import org.glavo.arkivo.archive.zip.ZipArkivoStreamingWriter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Stress-tests concurrent reads and lifecycle coordination across installed archive file systems.
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

    /// Verifies every random-access archive file system preserves entry data under concurrent reads.
    @Test
    @Timeout(value = 120)
    void concurrentReadsAcrossEveryFormat(@TempDir Path directory) throws Exception {
        for (Path archive : createArchives(directory)) {
            try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(archive)) {
                stress(fileSystem);
            }
        }
    }

    /// Verifies strict close invalidates every open resource type for every archive format.
    @Test
    @Timeout(value = 60)
    void strictCloseInvalidatesResourcesAcrossEveryFormat(@TempDir Path directory) throws Exception {
        ArchiveOptions options = ArchiveOptions.of(
                ArkivoFileSystem.THREAD_SAFETY,
                ArkivoFileSystemThreadSafety.STRICT
        );
        for (Path archive : createArchives(directory)) {
            try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(archive, options);
                 SeekableByteChannel channel =
                         Files.newByteChannel(fileSystem.getPath("/" + entryName(0)));
                 InputStream input =
                         Files.newInputStream(fileSystem.getPath("/" + entryName(1)));
                 DirectoryStream<Path> children =
                         Files.newDirectoryStream(fileSystem.getPath("/"))) {
                Iterator<Path> iterator = children.iterator();

                fileSystem.close();

                assertFalse(fileSystem.isOpen(), archive.toString());
                assertFalse(channel.isOpen(), archive.toString());
                assertThrows(
                        ClosedChannelException.class,
                        () -> channel.read(ByteBuffer.allocate(1)),
                        archive.toString()
                );
                assertThrows(IOException.class, input::read, archive.toString());
                assertThrows(ClosedFileSystemException.class, iterator::hasNext, archive.toString());
            }
        }
    }

    /// Verifies close waits for a real directory read already holding the shared operation lock.
    @Test
    @Timeout(value = 60)
    void closeWaitsForInFlightReadsAcrossEveryFormat(@TempDir Path directory) throws Exception {
        for (Path archive : createArchives(directory)) {
            CountDownLatch filterEntered = new CountDownLatch(1);
            CountDownLatch releaseFilter = new CountDownLatch(1);
            CountDownLatch closeEntered = new CountDownLatch(1);
            try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(archive)) {
                ExecutorService executor = Executors.newFixedThreadPool(2);
                try {
                    Future<DirectoryStream<Path>> streamFuture = executor.submit(() -> {
                        DirectoryStream<Path> children = Files.newDirectoryStream(
                                fileSystem.getPath("/"),
                                entry -> {
                                    filterEntered.countDown();
                                    try {
                                        if (!releaseFilter.await(10L, TimeUnit.SECONDS)) {
                                            throw new IOException(
                                                    "Timed out waiting to release directory filter"
                                            );
                                        }
                                    } catch (InterruptedException exception) {
                                        Thread.currentThread().interrupt();
                                        throw new IOException(
                                                "Directory filter was interrupted",
                                                exception
                                        );
                                    }
                                    return true;
                                }
                        );
                        try {
                            if (filterEntered.getCount() != 0L) {
                                children.iterator();
                            }
                            return children;
                        } catch (RuntimeException | Error exception) {
                            try {
                                children.close();
                            } catch (IOException closeException) {
                                exception.addSuppressed(closeException);
                            }
                            throw exception;
                        }
                    });
                    assertTrue(filterEntered.await(10L, TimeUnit.SECONDS), archive.toString());
                    Future<Boolean> closeFuture = executor.submit(() -> {
                        closeEntered.countDown();
                        fileSystem.close();
                        return true;
                    });
                    assertTrue(closeEntered.await(10L, TimeUnit.SECONDS), archive.toString());

                    assertThrows(
                            TimeoutException.class,
                            () -> closeFuture.get(200L, TimeUnit.MILLISECONDS),
                            archive.toString()
                    );
                    releaseFilter.countDown();

                    try (DirectoryStream<Path> children =
                                 streamFuture.get(10L, TimeUnit.SECONDS)) {
                        assertTrue(closeFuture.get(10L, TimeUnit.SECONDS), archive.toString());
                        assertFalse(fileSystem.isOpen(), archive.toString());
                        assertThrows(
                                ClosedFileSystemException.class,
                                children::iterator,
                                archive.toString()
                        );
                    }
                } finally {
                    releaseFilter.countDown();
                    executor.shutdownNow();
                    assertTrue(
                            executor.awaitTermination(10L, TimeUnit.SECONDS),
                            "Archive lifecycle tasks did not terminate"
                    );
                }
            }
        }
    }

    /// Creates fixtures for every random-access archive format.
    private static @Unmodifiable List<Path> createArchives(Path directory) throws IOException {
        Path arArchive = directory.resolve("concurrent.a");
        Path tarArchive = directory.resolve("concurrent.tar");
        Path zipArchive = directory.resolve("concurrent.zip");
        Path sevenZipArchive = directory.resolve("concurrent.7z");
        Path rarArchive = directory.resolve("concurrent.rar");
        createStreamingArchive("ar", arArchive);
        createStreamingArchive("tar", tarArchive);
        createZip(zipArchive);
        createSevenZip(sevenZipArchive);
        createRar(rarArchive);
        return List.of(arArchive, tarArchive, zipArchive, sevenZipArchive, rarArchive);
    }

    /// Creates one archive through the generic streaming-writer API.
    private static void createStreamingArchive(String format, Path archive) throws IOException {
        try (ArkivoStreamingWriter writer =
                     ArkivoFormats.openStreamingWriter(format, Files.newOutputStream(archive))) {
            writeEntries(writer);
        }
    }

    /// Creates a compressed ZIP fixture with deterministic entry data.
    private static void createZip(Path archive) throws IOException {
        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archive)) {
            writeEntries(writer);
        }
    }

    /// Creates an LZMA2-compressed 7z fixture whose entries share solid folders.
    private static void createSevenZip(Path archive) throws IOException {
        ArchiveOptions options = ArchiveOptions.builder()
                .set(SevenZipArkivoFileSystem.COMPRESSION, SevenZipCompression.lzma2(1024 * 1024))
                .set(SevenZipArkivoFileSystem.SOLID_FILE_COUNT, 16)
                .build();
        try (SevenZipArkivoStreamingWriter writer =
                     SevenZipArkivoStreamingWriter.create(archive, options)) {
            writeEntries(writer);
        }
    }

    /// Creates a stored RAR4 fixture without a binary test asset or external process.
    private static void createRar(Path archive) throws IOException {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        for (int index = 0; index < ENTRY_COUNT; index++) {
            entries.put(entryName(index), content(index));
        }
        Files.write(archive, ArchiveTestFixtures.createRar4Archive(entries));
    }

    /// Writes the deterministic stress entries through one streaming writer.
    private static void writeEntries(ArkivoStreamingWriter writer) throws IOException {
        for (int index = 0; index < ENTRY_COUNT; index++) {
            writer.beginFile(entryName(index));
            try (OutputStream output = writer.openOutputStream()) {
                output.write(content(index));
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
