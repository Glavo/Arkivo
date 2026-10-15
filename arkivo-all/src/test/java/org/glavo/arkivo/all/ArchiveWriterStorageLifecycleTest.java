// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArchiveCreateOptions;
import org.glavo.arkivo.archive.ArchiveUpdateOptions;
import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoEditStorageFactory;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.archive.ArkivoStoredContent;
import org.glavo.arkivo.archive.ArkivoStreamingReader;
import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.glavo.arkivo.archive.ar.ArArchiveOptions;
import org.glavo.arkivo.archive.ar.ArArkivoFileSystem;
import org.glavo.arkivo.archive.ar.ArArkivoEntryAttributeView;
import org.glavo.arkivo.archive.ar.ArArkivoStreamingReader;
import org.glavo.arkivo.archive.ar.ArArkivoStreamingWriter;
import org.glavo.arkivo.archive.ar.internal.ArArkivoFileSystemProvider;
import org.glavo.arkivo.archive.cpio.CPIOArchiveOptions;
import org.glavo.arkivo.archive.cpio.CPIOArkivoStreamingReader;
import org.glavo.arkivo.archive.cpio.CPIOArkivoStreamingWriter;
import org.glavo.arkivo.archive.sevenzip.SevenZipArchiveOptions;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoFileSystem;
import org.glavo.arkivo.archive.tar.TarArchiveOptions;
import org.glavo.arkivo.archive.tar.TarArkivoFileSystem;
import org.glavo.arkivo.archive.tar.TarArkivoStreamingReader;
import org.glavo.arkivo.archive.tar.TarArkivoStreamingWriter;
import org.glavo.arkivo.archive.tar.internal.TarArkivoFileSystemProvider;
import org.glavo.arkivo.archive.zip.ZipArchiveOptions;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies staged archive writers retain resources across failures without repeating publication.
@NotNullByDefault
final class ArchiveWriterStorageLifecycleTest {
    /// Completes atomic ZIP replacement even when retiring the overwritten body requires deferred cleanup.
    @ParameterizedTest
    @EnumSource(FailureKind.class)
    void atomicZipMoveDefersRetiredContentCleanup(FailureKind kind, @TempDir Path directory) throws IOException {
        for (boolean stagedSource : new boolean[]{false, true}) {
            Path archive = directory.resolve(stagedSource + ".zip");
            try (var fs = ZipArkivoFileSystem.create(archive)) {
                Files.writeString(fs.getPath("source"), "source body");
            }
            TrackingStorage storage = new TrackingStorage();
            var options = ZipArchiveOptions.UPDATE_DEFAULTS.withCommon(
                    ArchiveUpdateOptions.DEFAULT.withEditStorageFactory(() -> storage));
            var fs = ZipArkivoFileSystem.update(archive, options);
            try {
                Path source = fs.getPath("source");
                Path target = fs.getPath("target");
                if (stagedSource) Files.writeString(source, "staged source body");
                Files.writeString(target, "target body");
                String expected = stagedSource ? "staged source body" : "source body";
                int releases = storage.count(Stage.CONTENT);
                storage.fail(Stage.CONTENT, kind.failure("Retired target cleanup failed"));

                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

                assertEquals(releases + 1, storage.count(Stage.CONTENT));
                assertFalse(Files.exists(source));
                assertEquals(expected, Files.readString(target));
                Throwable cleanupFailure = kind.failure("Deferred content cleanup failed again");
                storage.fail(Stage.CONTENT, cleanupFailure);
                assertSame(cleanupFailure, assertThrows(cleanupFailure.getClass(), fs::close));
                fs.close();
                fs.close();
                storage.assertReleased();
                try (var reopened = ZipArkivoFileSystem.open(archive)) {
                    assertFalse(Files.exists(reopened.getPath("source")));
                    assertEquals(expected, Files.readString(reopened.getPath("target")));
                }
            } finally {
                fs.close();
            }
        }
    }

    /// Cancels a ZIP mirror write without assembling or publishing the incomplete update.
    @Test
    void strictZipCloseCancelsMirrorWrite(@TempDir Path directory) throws Exception {
        Path archive = createZip(directory);
        byte[] original = Files.readAllBytes(archive);
        TrackingStorage storage = new TrackingStorage();
        var options = ArchiveUpdateOptions.DEFAULT.withThreadSafety(ArkivoFileSystemThreadSafety.STRICT)
                .withEditStorageFactory(() -> storage).withCommitTarget(source -> {
                    throw new AssertionError("Cancelled ZIP update opened its commit target");
                });
        var fs = ZipArkivoFileSystem.update(archive, ZipArchiveOptions.UPDATE_DEFAULTS.withCommon(options));
        OutputStream output = Files.newOutputStream(fs.getPath("replacement"));
        WriteGate gate = new WriteGate();
        storage.writeGate = gate;
        IOException writeFailure = new IOException("Delayed ZIP mirror write failure");
        storage.fail(Stage.WRITE, writeFailure);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var writing = executor.submit(() -> assertThrows(IOException.class, () -> output.write(BODY)));
            assertTrue(gate.entered.await(5, TimeUnit.SECONDS));
            var closing = executor.submit(() -> assertThrows(IOException.class, fs::close));
            assertTrue(gate.closed.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> closing.get(100, TimeUnit.MILLISECONDS));
            gate.release.countDown();
            assertSame(writeFailure, writing.get(5, TimeUnit.SECONDS));
            assertSame(writeFailure, closing.get(5, TimeUnit.SECONDS).getCause());
            assertFalse(gate.readWhileWriting);
            output.close();
            fs.close();
            storage.assertReleased();
            assertArrayEquals(original, Files.readAllBytes(archive));
        } finally {
            gate.release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            try {
                output.close();
            } finally {
                fs.close();
            }
        }
    }

    /// Entry completion discards writes interrupted even when storage returns partial success after close.
    @ParameterizedTest
    @CsvSource({"ar,STRICT,false", "tar,STRICT,false", "ar,CONCURRENT_READ,false", "tar,CONCURRENT_READ,false",
            "ar,STRICT,true", "tar,STRICT,true", "ar,CONCURRENT_READ,true", "tar,CONCURRENT_READ,true"})
    void createCloseDiscardsActiveFailedWrite(String format, ArkivoFileSystemThreadSafety safety,
                                             boolean partialSuccess, @TempDir Path directory) throws Exception {
        for (boolean channel : new boolean[]{false, true}) {
            for (boolean closeFileSystem : new boolean[]{false, true}) {
                if (closeFileSystem && safety != ArkivoFileSystemThreadSafety.STRICT) continue;
                TrackingStorage storage = new TrackingStorage();
                WriteGate gate = new WriteGate();
                storage.writeGate = gate;
                IOException writeFailure = new IOException("Delayed partial write failure");
                if (!partialSuccess) storage.fail(Stage.WRITE, writeFailure);
                var options = ArchiveCreateOptions.DEFAULT.withThreadSafety(safety)
                        .withEditStorageFactory(() -> storage);
                Path archive = directory.resolve(channel + "-" + closeFileSystem);
                var fs = ArkivoFormats.createFileSystem(format, archive, options);
                var executor = Executors.newFixedThreadPool(2);
                OutputStream output = channel
                        ? java.nio.channels.Channels.newOutputStream(Files.newByteChannel(fs.getPath("entry"),
                                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)))
                        : Files.newOutputStream(fs.getPath("entry"));
                try {
                    var writing = executor.submit(() -> assertThrows(IOException.class, () -> output.write(BODY)));
                    assertTrue(gate.entered.await(5, TimeUnit.SECONDS));
                    var closing = executor.submit(() -> assertThrows(IOException.class, () -> {
                        if (closeFileSystem) fs.close();
                        else output.close();
                    }));
                    assertTrue(gate.closed.await(5, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> closing.get(100, TimeUnit.MILLISECONDS));
                    gate.release.countDown();
                    IOException actual = writing.get(5, TimeUnit.SECONDS);
                    if (partialSuccess) assertInstanceOf(java.nio.channels.ClosedChannelException.class, actual);
                    else assertSame(writeFailure, actual);
                    assertCleanupCause(actual, closing.get(5, TimeUnit.SECONDS));
                    assertFalse(gate.readWhileWriting);
                    output.close();
                    if (!closeFileSystem) {
                        storage.writeGate = null;
                        try (var replacement = Files.newOutputStream(fs.getPath("entry"))) {
                            replacement.write(BODY);
                            output.close();
                        }
                    }
                    fs.close();
                    storage.assertReleased();
                    try (var reopened = ArkivoFormats.openFileSystem(format, archive)) {
                        if (closeFileSystem) assertFalse(Files.exists(reopened.getPath("entry")));
                        else assertArrayEquals(BODY, Files.readAllBytes(reopened.getPath("entry")));
                    }
                } finally {
                    gate.release.countDown();
                    executor.shutdownNow();
                    assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
                    try {
                        output.close();
                    } finally {
                        fs.close();
                    }
                }
            }
        }
    }

    /// Closing an update stream during partial progress preserves the previously published body.
    @ParameterizedTest
    @EnumSource(value = UpdateFormat.class, names = {"AR", "TAR", "SEVEN_ZIP"})
    void updateCloseDiscardsIncompleteStreamWrite(UpdateFormat format, @TempDir Path directory) throws Exception {
        for (boolean closeFileSystem : new boolean[]{false, true}) {
            Path archive = directory.resolve(Boolean.toString(closeFileSystem));
            try (var fs = format.create(archive)) {
                Files.write(fs.getPath("entry"), BODY);
            }
            TrackingStorage storage = new TrackingStorage();
            var fs = format.update(archive, storage, ArkivoFileSystemThreadSafety.STRICT);
            OutputStream output = Files.newOutputStream(fs.getPath("entry"));
            WriteGate gate = new WriteGate();
            storage.writeGate = gate;
            var executor = Executors.newFixedThreadPool(2);
            try {
                var writing = executor.submit(() -> assertThrows(java.nio.channels.ClosedChannelException.class,
                        () -> output.write(new byte[]{9, 8, 7, 6})));
                assertTrue(gate.entered.await(5, TimeUnit.SECONDS));
                var closing = executor.submit(() -> assertThrows(IOException.class, () -> {
                    if (closeFileSystem) fs.close();
                    else output.close();
                }));
                assertTrue(gate.closed.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> closing.get(100, TimeUnit.MILLISECONDS));
                gate.release.countDown();
                assertSame(writing.get(5, TimeUnit.SECONDS), closing.get(5, TimeUnit.SECONDS).getCause());
                assertFalse(gate.readWhileWriting);
                storage.writeGate = null;
                output.close();
                if (!closeFileSystem) assertArrayEquals(BODY, Files.readAllBytes(fs.getPath("entry")));
                fs.close();
                storage.assertReleased();
                try (var reopened = format.open(archive)) {
                    assertArrayEquals(BODY, Files.readAllBytes(reopened.getPath("entry")));
                }
            } finally {
                gate.release.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
                try {
                    output.close();
                } finally {
                    fs.close();
                }
            }
        }
    }

    /// Successful strict closure completes the current body, while closing an older stream leaves it untouched.
    @ParameterizedTest
    @ValueSource(strings = {"ar", "tar"})
    void strictCreateCloseCompletesOnlyCurrentBody(String format, @TempDir Path directory) throws IOException {
        TrackingStorage storage = new TrackingStorage();
        var options = ArchiveCreateOptions.DEFAULT.withThreadSafety(ArkivoFileSystemThreadSafety.STRICT)
                .withEditStorageFactory(() -> storage);
        Path archive = directory.resolve("archive");
        try (var fs = ArkivoFormats.createFileSystem(format, archive, options)) {
            OutputStream first = Files.newOutputStream(fs.getPath("empty"));
            first.close();
            try (var second = Files.newOutputStream(fs.getPath("body"))) {
                second.write(BODY);
                first.close();
                second.write(BODY);
                fs.close();
            }
        }
        storage.assertReleased();
        try (var fs = ArkivoFormats.openFileSystem(format, archive)) {
            assertEquals(0, Files.size(fs.getPath("empty")));
            assertArrayEquals(new byte[]{1, 2, 3, 4, 1, 2, 3, 4}, Files.readAllBytes(fs.getPath("body")));
        }
    }

    /// Separates physical channel closure from the return of an active failed write.
    @NotNullByDefault
    private static final class WriteGate {
        /// Signals that the partial write has entered storage.
        private final CountDownLatch entered = new CountDownLatch(1);
        /// Signals physical storage closure without completing the write.
        private final CountDownLatch closed = new CountDownLatch(1);
        /// Allows the partial write to report its failure.
        private final CountDownLatch release = new CountDownLatch(1);
        /// Records an attempt to publish storage before the failed write returned.
        private volatile boolean readWhileWriting;
    }

    /// Cleanup failure after publication does not allow a second member with the already emitted name.
    @ParameterizedTest
    @ValueSource(strings = {"ar", "tar"})
    void createCloseRetryRetainsPublishedName(String format, @TempDir Path directory) throws IOException {
        TrackingStorage storage = new TrackingStorage();
        Path archive = directory.resolve("archive");
        var options = ArchiveCreateOptions.DEFAULT.withEditStorageFactory(() -> storage);
        try (var fs = ArkivoFormats.createFileSystem(format, archive, options)) {
            OutputStream output = Files.newOutputStream(fs.getPath("entry"));
            output.write(BODY);
            IOException failure = new IOException("Cannot close published body input");
            storage.fail(Stage.READ_CLOSE, failure);
            assertSame(failure, assertThrows(IOException.class, output::close));
            output.close();
            assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> {
                try (var ignored = Files.newOutputStream(fs.getPath("entry"))) {
                    // The archive must reject the duplicate before opening a body.
                }
            });
        }
        storage.assertReleased();
        try (var fs = ArkivoFormats.openFileSystem(format, archive)) {
            assertArrayEquals(BODY, Files.readAllBytes(fs.getPath("entry")));
        }
    }

    /// Create sessions use the configured staging storage and retain it until cleanup succeeds.
    @ParameterizedTest
    @ValueSource(strings = {"ar", "tar"})
    void usesCreateStorageAndRetriesCleanup(String format, @TempDir Path directory) throws IOException {
        TrackingStorage storage = new TrackingStorage();
        int[] calls = {0};
        var options = ArchiveCreateOptions.DEFAULT.withEditStorageFactory(() -> {
            calls[0]++;
            return storage;
        });
        Path archive = directory.resolve("archive");
        try (var fs = ArkivoFormats.createFileSystem(format, archive, options)) {
            assertEquals(1, calls[0]);
            try (var output = Files.newOutputStream(fs.getPath("entry"))) {
                output.write(BODY);
                assertEquals(1, storage.liveContents);
            }
            assertEquals(0, storage.liveContents);
            IOException failure = new IOException("Transient create storage cleanup failure");
            storage.fail(Stage.STORAGE, failure);
            assertSame(failure, assertThrows(IOException.class, fs::close));
            fs.close();
            assertEquals(2, storage.count(Stage.STORAGE));
        }
        storage.assertReleased();
        try (var fs = ArkivoFormats.openFileSystem(format, archive)) {
            assertArrayEquals(BODY, Files.readAllBytes(fs.getPath("entry")));
        }
    }

    /// A rejected staging configuration cannot create or alter the destination archive.
    @ParameterizedTest
    @ValueSource(strings = {"ar", "tar"})
    void createStorageFailureLeavesDestinationUntouched(String format, @TempDir Path directory) throws IOException {
        for (FailureKind kind : FailureKind.values()) {
            for (boolean existing : new boolean[]{false, true}) {
                Path archive = directory.resolve(kind + "-" + existing);
                if (existing) Files.write(archive, BODY);
                TrackingStorage storage = new TrackingStorage();
                Throwable failure = kind.failure("Cannot open create storage");
                storage.fail(Stage.STORAGE, failure);
                int[] calls = {0};
                ArkivoEditStorageFactory factory = () -> {
                    calls[0]++;
                    storage.attempt(Stage.STORAGE);
                    throw new AssertionError("Factory unexpectedly returned");
                };
                var options = ArchiveCreateOptions.DEFAULT.withEditStorageFactory(factory);
                assertSame(failure, assertThrows(Throwable.class,
                        () -> ArkivoFormats.createFileSystem(format, archive, options)));
                assertEquals(1, calls[0]);
                storage.fail(Stage.STORAGE, failure);
                var provider = format.equals("ar")
                        ? new ArArkivoFileSystemProvider() : new TarArkivoFileSystemProvider();
                var environment = Map.of(
                        "arkivo.openOptions", Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING),
                        "arkivo.editStorageFactory", factory);
                assertSame(failure, assertThrows(Throwable.class,
                        () -> provider.newFileSystem(
                                URI.create(provider.getScheme() + ":" + archive.toUri()), environment)));
                assertEquals(2, calls[0]);
                if (existing) assertArrayEquals(BODY, Files.readAllBytes(archive));
                else assertFalse(Files.exists(archive));
                storage.close();
            }
        }
    }

    /// Failure to open the destination releases staging storage without masking the open failure.
    @ParameterizedTest
    @ValueSource(strings = {"ar", "tar"})
    void createOutputFailureClosesStorage(String format, @TempDir Path directory) throws IOException {
        for (FailureKind kind : FailureKind.values()) {
            TrackingStorage storage = new TrackingStorage();
            Throwable cleanup = kind.failure("Cannot close create storage");
            storage.fail(Stage.STORAGE, cleanup);
            Path archive = directory.resolve(kind + "-missing-parent").resolve("archive");
            var options = ArchiveCreateOptions.DEFAULT.withEditStorageFactory(() -> storage);
            IOException failure = assertThrows(IOException.class,
                    () -> ArkivoFormats.createFileSystem(format, archive, options));
            assertArrayEquals(new Throwable[]{cleanup}, failure.getSuppressed());
            assertEquals(1, storage.count(Stage.STORAGE));
            storage.close();
            storage.assertReleased();
            assertFalse(Files.exists(archive));
        }
    }

    /// Replacing and removing nodes releases obsolete content without invalidating already open readers.
    @ParameterizedTest
    @EnumSource(value = UpdateFormat.class, names = {"AR", "TAR"})
    void releasesObsoleteUpdateContent(UpdateFormat format, @TempDir Path directory) throws IOException {
        Path archive = directory.resolve("archive");
        try (var fs = format.create(archive)) {
            Files.write(fs.getPath("entry"), BODY);
        }
        TrackingStorage storage = new TrackingStorage();
        try (var fs = format.update(archive, storage)) {
            Path entry = fs.getPath("entry");
            assertEquals(1, storage.liveContents);
            try (InputStream old = Files.newInputStream(entry)) {
                for (int index = 0; index < 6; index++) {
                    Files.write(entry, new byte[]{(byte) index});
                    assertEquals(2, storage.liveContents, "Only the old reader and latest version retain content");
                }
                assertArrayEquals(BODY, old.readAllBytes());
            }
            assertEquals(1, storage.liveContents);
            if (format == UpdateFormat.AR) {
                for (long size : new long[]{3, 8, 1, 7}) {
                    Files.setAttribute(entry, "ar:size", size);
                    assertEquals(size, Files.size(entry));
                    assertEquals(1, storage.liveContents);
                }
            }
            Files.write(fs.getPath("replacement"), BODY);
            assertEquals(2, storage.liveContents);
            Files.move(fs.getPath("replacement"), entry, StandardCopyOption.REPLACE_EXISTING);
            assertEquals(1, storage.liveContents);
            assertArrayEquals(BODY, Files.readAllBytes(entry));
            Files.delete(entry);
            assertEquals(0, storage.liveContents);
        }
        storage.assertReleased();
    }

    /// Removing one hard-link name keeps shared content alive until the final name and reader have gone.
    @Test
    void retainsContentReferencedByTarHardLinks(@TempDir Path directory) throws IOException {
        Path archive = directory.resolve("archive.tar");
        try (var fs = UpdateFormat.TAR.create(archive)) {
            Files.write(fs.getPath("original"), BODY);
            Files.createLink(fs.getPath("alias"), fs.getPath("original"));
        }
        TrackingStorage storage = new TrackingStorage();
        try (var fs = UpdateFormat.TAR.update(archive, storage)) {
            Files.delete(fs.getPath("original"));
            assertEquals(1, storage.liveContents);
            try (var old = Files.newInputStream(fs.getPath("alias"))) {
                Files.write(fs.getPath("alias"), new byte[]{9});
                assertEquals(2, storage.liveContents);
                Files.delete(fs.getPath("alias"));
                assertEquals(1, storage.liveContents);
                assertArrayEquals(BODY, old.readAllBytes());
            }
            assertEquals(0, storage.liveContents);
        }
        storage.assertReleased();
    }

    /// A failed early content cleanup remains owned by the pool and is retried at file-system close.
    @ParameterizedTest
    @EnumSource(value = UpdateFormat.class, names = {"AR", "TAR"})
    void retriesObsoleteContentCleanup(UpdateFormat format, @TempDir Path directory) throws IOException {
        Path archive = directory.resolve("archive");
        try (var fs = format.create(archive)) {
            Files.write(fs.getPath("entry"), BODY);
        }
        TrackingStorage storage = new TrackingStorage();
        try (var fs = format.update(archive, storage)) {
            storage.fail(Stage.CONTENT, new IOException("Transient content cleanup failure"));
            Files.write(fs.getPath("entry"), new byte[]{9});
            assertEquals(2, storage.liveContents);
            assertArrayEquals(new byte[]{9}, Files.readAllBytes(fs.getPath("entry")));
        }
        storage.assertReleased();
        try (var fs = format.open(archive)) {
            assertArrayEquals(new byte[]{9}, Files.readAllBytes(fs.getPath("entry")));
        }
    }

    /// Staged output streams retain flush lifecycle checks when channel completion owns their closure.
    @ParameterizedTest
    @ValueSource(strings = {"ar", "tar", "7z"})
    void stagedOutputFlushRejectsClosedResources(String format, @TempDir Path directory) throws IOException {
        for (var safety : new ArkivoFileSystemThreadSafety[]{
                ArkivoFileSystemThreadSafety.CONCURRENT_READ, ArkivoFileSystemThreadSafety.STRICT}) {
            for (boolean closeFileSystem : new boolean[]{false, true}) {
                Path archive = directory.resolve(safety + "-" + closeFileSystem + "." + format);
                try (var fs = ArkivoFormats.createFileSystem(format, archive)) {
                    Files.writeString(fs.getPath("file"), "old");
                }
                try (var fs = ArkivoFormats.updateFileSystem(format, archive,
                        ArchiveUpdateOptions.DEFAULT.withThreadSafety(safety));
                     OutputStream output = Files.newOutputStream(fs.getPath("file"))) {
                    output.write(BODY);
                    output.flush();
                    if (closeFileSystem) fs.close();
                    else output.close();
                    assertThrows(IOException.class, output::flush);
                }
                try (var fs = ArkivoFormats.openFileSystem(format, archive)) {
                    assertArrayEquals(BODY, Files.readAllBytes(fs.getPath("file")));
                }
            }
        }
    }

    /// Requires cleanup wrappers to retain the exact original failure without prescribing the number of adapters.
    private static void assertCleanupCause(Throwable expected, Throwable actual) {
        var visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        while (actual != expected) {
            assertTrue(visited.add(actual), "Cyclic cleanup cause chain");
            assertInstanceOf(IOException.class, actual);
            actual = Objects.requireNonNull(actual.getCause(), "Missing original cleanup failure");
        }
    }

    /// Small entry bytes shared by all format cases.
    private static final byte @Unmodifiable [] BODY = {1, 2, 3, 4};

    /// Supplies every streaming format and failure category.
    private static Stream<Arguments> streamingFailures() {
        return Stream.of(Format.values()).flatMap(format -> Stream.of(FailureKind.values())
                .map(kind -> Arguments.of(format, kind)));
    }

    /// Supplies target and storage failures after an entry has been fully emitted.
    private static Stream<Arguments> finalCleanupFailures() {
        return streamingFailures().flatMap(arguments -> Stream.of(Stage.TARGET_CLOSE, Stage.STORAGE)
                .map(stage -> Arguments.of(arguments.get()[0], arguments.get()[1], stage)));
    }

    /// Supplies failures while closing either side of a staged-body transfer.
    private static Stream<Arguments> bodyCloseFailures() {
        return streamingFailures().flatMap(arguments -> Stream.of(Stage.WRITE_CLOSE, Stage.READ_CLOSE)
                .map(stage -> Arguments.of(arguments.get()[0], arguments.get()[1], stage)));
    }

    /// Verifies final cleanup is retryable and a retry never emits the archive ending twice.
    @ParameterizedTest
    @MethodSource("finalCleanupFailures")
    void retriesFinalCleanupWithoutRepeatingOutput(Format format, FailureKind kind, Stage stage) throws IOException {
        TrackingStorage storage = new TrackingStorage();
        TrackingOutput target = new TrackingOutput(storage);
        ArkivoStreamingWriter writer = format.writer(target, storage);
        try (OutputStream body = writer.beginFile("entry").openOutputStream()) {
            body.write(BODY);
        }
        Throwable failure = kind.failure("final cleanup");
        storage.fail(stage, failure);
        assertSame(failure, assertThrows(failure.getClass(), writer::close));
        byte[] finished = target.bytes.toByteArray();
        writer.close();
        writer.close();
        assertArrayEquals(finished, target.bytes.toByteArray());
        assertEquals(stage == Stage.TARGET_CLOSE ? 2 : 1, storage.count(Stage.TARGET_CLOSE));
        assertEquals(stage == Stage.STORAGE ? 2 : 1, storage.count(Stage.STORAGE));
        storage.assertReleased();
        format.verifyArchive(finished, true);
    }

    /// Preserves an earlier close failure when automatic cleanup encounters the same failure again.
    @ParameterizedTest
    @MethodSource("finalCleanupFailures")
    void preservesRepeatedCloseFailure(Format format, FailureKind kind, Stage stage) throws IOException {
        TrackingStorage storage = new TrackingStorage();
        TrackingOutput target = new TrackingOutput(storage);
        ArkivoStreamingWriter writer = format.writer(target, storage);
        try (OutputStream body = writer.beginFile("entry").openOutputStream()) {
            body.write(BODY);
        }
        Throwable failure = kind.failure("repeated cleanup");
        storage.fail(stage, failure, failure);
        try {
            assertSame(failure, assertThrows(failure.getClass(), () -> {
                try (writer) {
                    writer.close();
                }
            }));
            assertEquals(1, failure.getSuppressed().length);
            assertInstanceOf(IOException.class, failure.getSuppressed()[0]);
            assertCleanupCause(failure, failure.getSuppressed()[0]);
        } finally {
            byte[] finished = target.bytes.toByteArray();
            writer.close();
            writer.close();
            assertArrayEquals(finished, target.bytes.toByteArray());
            assertEquals(3, storage.count(stage));
            storage.assertReleased();
            format.verifyArchive(finished, true);
        }
    }

    /// Verifies failed staging-channel closes retain their handles and never permit premature content deletion.
    @ParameterizedTest
    @MethodSource("bodyCloseFailures")
    void retainsFailedBodyChannels(Format format, FailureKind kind, Stage stage) throws IOException {
        TrackingStorage storage = new TrackingStorage();
        TrackingOutput target = new TrackingOutput(storage);
        ArkivoStreamingWriter writer = format.writer(target, storage);
        OutputStream body = writer.beginFile("entry").openOutputStream();
        body.write(BODY);
        Throwable failure = kind.failure("body close");
        storage.fail(stage, failure);
        assertSame(failure, assertThrows(failure.getClass(), body::close));
        try {
            writer.close();
        } catch (IOException | RuntimeException | Error exception) {
            assertSame(failure, exception);
        }
        byte[] finished = target.bytes.toByteArray();
        writer.close();
        assertArrayEquals(finished, target.bytes.toByteArray());
        assertEquals(2, storage.count(stage));
        storage.assertReleased();
        format.verifyArchive(finished, stage == Stage.READ_CLOSE);
    }

    /// Verifies target, content, and storage failures preserve identity and respect cleanup dependencies.
    @ParameterizedTest
    @MethodSource("streamingFailures")
    void aggregatesFailuresInDependencyOrder(Format format, FailureKind kind) throws IOException {
        TrackingStorage storage = new TrackingStorage();
        TrackingOutput target = new TrackingOutput(storage);
        ArkivoStreamingWriter writer = format.writer(target, storage);
        Throwable contentFailure = kind.failure("content cleanup");
        storage.fail(Stage.CONTENT, contentFailure, contentFailure);
        try (OutputStream body = writer.beginFile("entry").openOutputStream()) {
            body.write(BODY);
        }
        Throwable targetFailure = kind.failure("target cleanup");
        Throwable storageFailure = kind.failure("storage cleanup");
        storage.fail(Stage.TARGET_CLOSE, targetFailure);
        storage.fail(Stage.STORAGE, storageFailure);
        assertSame(targetFailure, assertThrows(targetFailure.getClass(), writer::close));
        assertEquals(1, targetFailure.getSuppressed().length);
        assertSame(contentFailure, assertInstanceOf(IOException.class, targetFailure.getSuppressed()[0]).getCause());
        assertEquals(0, storage.count(Stage.STORAGE));
        assertSame(storageFailure, assertThrows(IOException.class, writer::close).getCause());
        byte[] finished = target.bytes.toByteArray();
        writer.close();
        assertEquals(2, storage.count(Stage.TARGET_CLOSE));
        assertEquals(3, storage.count(Stage.CONTENT));
        assertEquals(2, storage.count(Stage.STORAGE));
        assertArrayEquals(finished, target.bytes.toByteArray());
        storage.assertReleased();
        format.verifyArchive(finished, true);
    }

    /// Verifies finalization failures still close both resources and are never retried as further output.
    @ParameterizedTest
    @MethodSource("streamingFailures")
    void doesNotRetryFailedFinalization(Format format, FailureKind kind) throws IOException {
        TrackingStorage storage = new TrackingStorage();
        TrackingOutput target = new TrackingOutput(storage);
        ArkivoStreamingWriter writer = format.writer(target, storage);
        Throwable failure = kind.failure("archive ending");
        storage.fail(Stage.TARGET_WRITE, failure);
        assertSame(failure, assertThrows(failure.getClass(), writer::close));
        writer.close();
        assertEquals(1, storage.count(Stage.TARGET_WRITE));
        assertEquals(1, storage.count(Stage.TARGET_CLOSE));
        assertEquals(1, storage.count(Stage.STORAGE));
        storage.assertReleased();
    }

    /// Verifies successful ZIP publication is not repeated when only storage closure fails.
    @ParameterizedTest
    @EnumSource(FailureKind.class)
    void retriesZipStorageAfterPublication(FailureKind kind, @TempDir Path directory) throws IOException {
        Path archive = createZip(directory);
        TrackingStorage storage = new TrackingStorage();
        ZipArkivoFileSystem fileSystem = updateZip(archive, storage);
        Files.write(fileSystem.getPath("/added"), BODY);
        Throwable failure = kind.failure("ZIP storage close");
        storage.fail(Stage.STORAGE, failure);
        assertSame(failure, assertThrows(failure.getClass(), fileSystem::close));
        byte[] published = Files.readAllBytes(archive);
        fileSystem.close();
        fileSystem.close();
        assertEquals(2, storage.count(Stage.STORAGE));
        storage.assertReleased();
        assertArrayEquals(published, Files.readAllBytes(archive));
        try (ZipArkivoFileSystem result = ZipArkivoFileSystem.open(archive)) {
            assertArrayEquals(BODY, Files.readAllBytes(result.getPath("/original")));
            assertArrayEquals(BODY, Files.readAllBytes(result.getPath("/added")));
        }
    }

    /// Verifies ZIP does not publish staged local records whose output channel failed to close.
    @ParameterizedTest
    @EnumSource(FailureKind.class)
    void discardsZipRewriteAfterStagingCloseFailure(FailureKind kind, @TempDir Path directory) throws IOException {
        Path archive = createZip(directory);
        byte[] original = Files.readAllBytes(archive);
        TrackingStorage storage = new TrackingStorage();
        ZipArkivoFileSystem fileSystem = updateZip(archive, storage);
        Files.write(fileSystem.getPath("/added"), BODY);
        Throwable failure = kind.failure("ZIP records close");
        storage.fail(Stage.WRITE_CLOSE, failure);
        assertSame(failure, assertThrows(failure.getClass(), fileSystem::close));
        fileSystem.close();
        storage.assertReleased();
        assertArrayEquals(original, Files.readAllBytes(archive));
    }

    /// Verifies rejected ZIP entry updates retain failed channel cleanup but do not replace the old body.
    @ParameterizedTest
    @EnumSource(FailureKind.class)
    void discardsZipEntryAfterWriterCloseFailure(FailureKind kind, @TempDir Path directory) throws IOException {
        Path archive = createZip(directory);
        TrackingStorage storage = new TrackingStorage();
        ZipArkivoFileSystem fileSystem = updateZip(archive, storage);
        try (SeekableByteChannel body = Files.newByteChannel(fileSystem.getPath("/original"),
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            body.write(ByteBuffer.wrap(new byte[]{9, 8}));
            Throwable failure = kind.failure("ZIP entry close");
            storage.fail(Stage.WRITE_CLOSE, failure);
            assertSame(failure, assertThrows(failure.getClass(), body::close));
        }
        fileSystem.close();
        fileSystem.close();
        storage.assertReleased();
        try (ZipArkivoFileSystem result = ZipArkivoFileSystem.open(archive)) {
            assertArrayEquals(BODY, Files.readAllBytes(result.getPath("/original")));
        }
    }

    /// Verifies a partial staging write cannot emit a streaming entry, even when its storage closes successfully.
    @ParameterizedTest
    @MethodSource("streamingWriteFailures")
    void discardsStreamingBodyAfterWriteFailure(Format format, FailureKind kind, boolean singleByte) throws IOException {
        TrackingStorage storage = new TrackingStorage();
        TrackingOutput target = new TrackingOutput(storage);
        try (ArkivoStreamingWriter writer = format.writer(target, storage)) {
            OutputStream body = writer.beginFile("discarded").openOutputStream();
            body.write(7);
            Throwable failure = kind.failure("partial body write");
            storage.fail(Stage.WRITE, failure);
            assertSame(failure, assertThrows(failure.getClass(), () -> {
                if (singleByte) {
                    body.write(8);
                } else {
                    body.write(BODY);
                }
            }));
            int attempts = storage.count(Stage.WRITE);
            assertSame(failure, assertThrows(IOException.class, () -> body.write(9)).getCause());
            assertEquals(attempts, storage.count(Stage.WRITE));
            assertCleanupCause(failure, assertThrows(IOException.class, body::close));
            body.close();
            try (OutputStream replacement = writer.beginFile("entry").openOutputStream()) {
                replacement.write(BODY);
            }
        }
        storage.assertReleased();
        format.verifyArchive(target.bytes.toByteArray(), true);
    }

    /// Failed staged entry creation leaves no implicit parent directories in the current update session.
    @ParameterizedTest
    @EnumSource(value = UpdateFormat.class, names = {"AR", "TAR"})
    void failedEntryCreationDoesNotPublishParents(UpdateFormat format, @TempDir Path directory) throws IOException {
        for (FailureKind kind : FailureKind.values()) {
            for (Stage stage : new Stage[]{Stage.CREATE, Stage.WRITE_OPEN, Stage.WRITE, Stage.TRUNCATE, Stage.WRITE_CLOSE}) {
                Path archive = directory.resolve(kind + "-" + stage);
                try (var fs = format.create(archive)) {
                    Files.write(fs.getPath("original"), BODY);
                }
                TrackingStorage storage = new TrackingStorage();
                try (var fs = format.update(archive, storage)) {
                    Path path = fs.getPath("ghost/nested/file");
                    Throwable failure = kind.failure("entry creation");
                    storage.fail(stage, failure);
                    if (stage == Stage.CREATE || stage == Stage.WRITE_OPEN) {
                        assertSame(failure, assertThrows(failure.getClass(),
                                () -> Files.newByteChannel(path, EntryChange.CREATE.options())));
                    } else {
                        var body = Files.newByteChannel(path, EntryChange.CREATE.options());
                        if (stage != Stage.WRITE) body.write(ByteBuffer.wrap(BODY));
                        assertSame(failure, assertThrows(failure.getClass(), () -> {
                            if (stage == Stage.WRITE) body.write(ByteBuffer.wrap(BODY));
                            else if (stage == Stage.TRUNCATE) body.truncate(1);
                            else body.close();
                        }));
                        if (stage != Stage.WRITE_CLOSE) {
                            assertSame(failure, assertThrows(IOException.class, body::close).getCause());
                        }
                        body.close();
                    }
                    assertFalse(Files.exists(fs.getPath("ghost")), kind + " / " + stage);
                    assertFalse(Files.exists(fs.getPath("ghost/nested")));
                    assertArrayEquals(BODY, Files.readAllBytes(fs.getPath("original")));
                    try (var body = Files.newOutputStream(path)) {
                        body.write(BODY);
                        assertFalse(Files.exists(fs.getPath("ghost")));
                    }
                    assertArrayEquals(BODY, Files.readAllBytes(path));
                }
                storage.assertReleased();
                try (var fs = format.open(archive)) {
                    assertArrayEquals(BODY, Files.readAllBytes(fs.getPath("ghost/nested/file")));
                }
            }
        }
    }

    /// A failed AR symbolic-link body leaves neither a link nor its implicit parents behind.
    @Test
    void failedArSymbolicLinkDoesNotPublishParents(@TempDir Path directory) throws IOException {
        for (FailureKind kind : FailureKind.values()) {
            for (Stage stage : new Stage[]{Stage.CREATE, Stage.WRITE_OPEN, Stage.WRITE, Stage.WRITE_CLOSE}) {
                Path archive = directory.resolve(kind + "-" + stage);
                try (var fs = UpdateFormat.AR.create(archive)) {
                    Files.write(fs.getPath("original"), BODY);
                }
                TrackingStorage storage = new TrackingStorage();
                try (var fs = UpdateFormat.AR.update(archive, storage)) {
                    Path link = fs.getPath("ghost/nested/link");
                    Throwable failure = kind.failure("symbolic link body");
                    storage.fail(stage, failure);
                    assertSame(failure, assertThrows(failure.getClass(),
                            () -> Files.createSymbolicLink(link, fs.getPath("../../original"))));
                    assertFalse(Files.exists(fs.getPath("ghost")), kind + " / " + stage);
                    assertFalse(Files.exists(fs.getPath("ghost/nested")));
                    Files.createSymbolicLink(link, fs.getPath("../../original"));
                    assertArrayEquals(BODY, Files.readAllBytes(link));
                }
                storage.assertReleased();
                try (var fs = UpdateFormat.AR.open(archive)) {
                    assertArrayEquals(BODY, Files.readAllBytes(fs.getPath("ghost/nested/link")));
                }
            }
        }
    }

    /// Verifies a failed update cannot replace or create an entry, while later independent updates remain usable.
    @ParameterizedTest
    @MethodSource("updateMutationFailures")
    void discardsFailedEntryMutation(
            UpdateFormat format, FailureKind kind, EntryChange change, Stage stage, @TempDir Path directory
    ) throws IOException {
        Path archive = directory.resolve("archive");
        try (ArkivoFileSystem initial = format.create(archive)) {
            Files.write(initial.getPath("/original"), BODY);
        }
        TrackingStorage storage = new TrackingStorage();
        try (ArkivoFileSystem fileSystem = format.update(archive, storage)) {
            Path path = fileSystem.getPath(change == EntryChange.CREATE ? "/discarded" : "/original");
            SeekableByteChannel body = Files.newByteChannel(path, change.options());
            if (stage == Stage.TRUNCATE) {
                body.write(ByteBuffer.wrap(BODY));
            }
            Throwable failure = kind.failure("partial entry mutation");
            storage.fail(stage, failure);
            assertSame(failure, assertThrows(failure.getClass(), () -> {
                if (stage == Stage.WRITE) {
                    body.write(ByteBuffer.wrap(new byte[]{9, 8, 7}));
                } else {
                    body.truncate(1L);
                }
            }));
            int attempts = storage.count(Stage.WRITE);
            assertSame(failure, assertThrows(IOException.class,
                    () -> body.write(ByteBuffer.wrap(BODY))).getCause());
            assertSame(failure, assertThrows(IOException.class, () -> body.truncate(0L)).getCause());
            assertEquals(attempts, storage.count(Stage.WRITE));
            assertSame(failure, assertThrows(IOException.class, body::close).getCause());
            body.close();
            assertArrayEquals(BODY, Files.readAllBytes(fileSystem.getPath("/original")));
            assertFalse(Files.exists(fileSystem.getPath("/discarded")));
            Files.write(fileSystem.getPath("/added"), BODY);
        }
        storage.assertReleased();
        try (ArkivoFileSystem result = format.open(archive)) {
            assertArrayEquals(BODY, Files.readAllBytes(result.getPath("/original")));
            assertArrayEquals(BODY, Files.readAllBytes(result.getPath("/added")));
            assertFalse(Files.exists(result.getPath("/discarded")));
        }
    }

    /// Preserves failures from retained entry readers when automatic close reports the same exception.
    @ParameterizedTest
    @MethodSource("retainedReaderFailures")
    void preservesRetainedReaderFailure(
            UpdateFormat format, FailureKind kind, boolean stream, ArkivoFileSystemThreadSafety threadSafety,
            @TempDir Path directory
    ) throws IOException {
        Path archive = directory.resolve("archive");
        try (var initial = format.create(archive)) {
            Files.write(initial.getPath("original"), BODY);
        }
        TrackingStorage storage = new TrackingStorage();
        try (var fs = format.update(archive, storage, threadSafety)) {
            Path entry = fs.getPath("original");
            Files.write(entry, BODY);
            Throwable failure = kind.failure("retained reader failed");
            if (stream) {
                var input = Files.newInputStream(entry);
                storage.fail(Stage.READ, failure);
                storage.fail(Stage.READ_CLOSE, failure);
                try {
                    assertSame(failure, assertThrows(failure.getClass(), () -> {
                        try (input) {
                            input.read();
                        }
                    }));
                } finally {
                    input.close();
                }
            } else {
                var channel = Files.newByteChannel(entry);
                storage.fail(Stage.READ, failure);
                storage.fail(Stage.READ_CLOSE, failure);
                try {
                    assertSame(failure, assertThrows(failure.getClass(), () -> {
                        try (channel) {
                            channel.read(ByteBuffer.allocate(1));
                        }
                    }));
                } finally {
                    channel.close();
                }
            }
            assertEquals(1, failure.getSuppressed().length);
            assertCleanupCause(failure, failure.getSuppressed()[0]);
            assertArrayEquals(BODY, Files.readAllBytes(entry));
        }
        storage.assertReleased();
        try (var fs = format.open(archive)) {
            assertArrayEquals(BODY, Files.readAllBytes(fs.getPath("original")));
        }
    }

    /// Supplies stream and channel reads for each update format, failure category, and coordination mode.
    private static Stream<Arguments> retainedReaderFailures() {
        return Stream.of(UpdateFormat.values()).flatMap(format -> Stream.of(FailureKind.values())
                .flatMap(kind -> Stream.of(false, true).flatMap(stream ->
                        Stream.of(ArkivoFileSystemThreadSafety.values())
                                .map(mode -> Arguments.of(format, kind, stream, mode)))));
    }

    /// Preserves non-mutating operation failures when cleanup or publication reports the same exception.
    @ParameterizedTest
    @MethodSource("stagedObservationFailures")
    void preservesStagedObservationFailure(
            UpdateFormat format, FailureKind kind, Stage operation, boolean completion, @TempDir Path directory
    ) throws IOException {
        Path archive = directory.resolve("archive");
        try (var initial = format.create(archive)) {
            Files.write(initial.getPath("original"), BODY);
        }
        TrackingStorage storage = new TrackingStorage();
        try (var fs = format.update(archive, storage)) {
            var channel = Files.newByteChannel(fs.getPath("original"),
                    Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE));
            channel.write(ByteBuffer.wrap(new byte[]{9}));
            channel.position(0);
            Throwable failure = kind.failure("staged observation failed");
            storage.fail(operation, failure);
            storage.fail(completion ? Stage.CONTENT_SIZE : Stage.WRITE_CLOSE, failure);
            Throwable caught = assertThrows(failure.getClass(), () -> {
                try (channel) {
                    observeChannel(channel, operation);
                }
            });
            assertSame(failure, caught);
            assertEquals(1, caught.getSuppressed().length);
            assertCleanupCause(failure, caught.getSuppressed()[0]);
            channel.close();
            assertArrayEquals(BODY, Files.readAllBytes(fs.getPath("original")));
            Files.write(fs.getPath("added"), BODY);
        }
        storage.assertReleased();
        try (var fs = format.open(archive)) {
            assertArrayEquals(BODY, Files.readAllBytes(fs.getPath("original")));
            assertArrayEquals(BODY, Files.readAllBytes(fs.getPath("added")));
        }
    }

    /// A failed observation alone does not discard successfully staged bytes.
    @ParameterizedTest
    @MethodSource("stagedObservations")
    void commitsAfterStagedObservationFailure(
            UpdateFormat format, FailureKind kind, Stage operation, @TempDir Path directory
    ) throws IOException {
        Path archive = directory.resolve("archive");
        try (var initial = format.create(archive)) {
            Files.write(initial.getPath("original"), BODY);
        }
        byte[] expected = BODY.clone();
        expected[0] = 9;
        TrackingStorage storage = new TrackingStorage();
        try (var fs = format.update(archive, storage)) {
            var channel = Files.newByteChannel(fs.getPath("original"),
                    Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE));
            channel.write(ByteBuffer.wrap(new byte[]{9}));
            channel.position(0);
            Throwable failure = kind.failure("staged observation failed");
            storage.fail(operation, failure);
            assertSame(failure, assertThrows(failure.getClass(), () -> {
                try (channel) {
                    observeChannel(channel, operation);
                }
            }));
            assertEquals(0, failure.getSuppressed().length);
            channel.close();
            assertArrayEquals(expected, Files.readAllBytes(fs.getPath("original")));
        }
        storage.assertReleased();
        try (var fs = format.open(archive)) {
            assertArrayEquals(expected, Files.readAllBytes(fs.getPath("original")));
        }
    }

    /// Exercises one operation that does not change the staged body bytes.
    private static void observeChannel(SeekableByteChannel channel, Stage operation) throws IOException {
        switch (operation) {
            case READ -> channel.read(ByteBuffer.allocate(1));
            case POSITION -> channel.position();
            case SEEK -> channel.position(1);
            case SIZE -> channel.size();
            default -> throw new AssertionError(operation);
        }
    }

    /// Supplies non-mutating operations for every update format and exception category.
    private static Stream<Arguments> stagedObservations() {
        return Stream.of(UpdateFormat.values()).flatMap(format -> Stream.of(FailureKind.values())
                .flatMap(kind -> Stream.of(Stage.READ, Stage.POSITION, Stage.SEEK, Stage.SIZE)
                        .map(operation -> Arguments.of(format, kind, operation))));
    }

    /// Supplies observation and cleanup boundaries for every update format and exception category.
    /// ZIP publishes by copying bytes rather than querying the content size in its entry-close callback.
    private static Stream<Arguments> stagedObservationFailures() {
        return Stream.of(UpdateFormat.values()).flatMap(format -> Stream.of(FailureKind.values())
                .flatMap(kind -> Stream.of(Stage.READ, Stage.POSITION, Stage.SEEK, Stage.SIZE)
                        .flatMap(operation -> (format == UpdateFormat.ZIP ? Stream.of(false) : Stream.of(false, true))
                                .map(completion -> Arguments.of(format, kind, operation, completion)))));
    }

    /// Supplies both single-byte and bulk writes for every staging stream and failure category.
    private static Stream<Arguments> streamingWriteFailures() {
        return Stream.of(Format.values()).flatMap(format -> Stream.of(FailureKind.values())
                .flatMap(kind -> Stream.of(false, true).map(singleByte -> Arguments.of(format, kind, singleByte))));
    }

    /// Supplies existing, truncated, appended, and new entries for both failing mutation operations.
    private static Stream<Arguments> updateMutationFailures() {
        return Stream.of(UpdateFormat.values()).flatMap(format -> Stream.of(FailureKind.values())
                .flatMap(kind -> Stream.of(EntryChange.values())
                        .flatMap(change -> Stream.of(Stage.WRITE, Stage.TRUNCATE)
                                .map(stage -> Arguments.of(format, kind, change, stage)))));
    }

    /// Preserves rewrite failures while cleaning up entry or assembled-archive readers, without publishing output.
    @ParameterizedTest
    @MethodSource("updateRewriteFailures")
    void preservesRewriteFailureDuringCleanup(
            UpdateFormat format, FailureKind kind, boolean assembled, boolean shared, @TempDir Path directory
    ) throws IOException {
        Path archive = directory.resolve("archive");
        try (var initial = format.create(archive)) {
            Files.write(initial.getPath("entry"), BODY);
        }
        byte[] original = Files.readAllBytes(archive);
        TrackingStorage storage = new TrackingStorage();
        var fileSystem = format.update(archive, storage);
        try {
            Files.write(fileSystem.getPath("entry"), new byte[]{9, 8, 7});
            storage.readFailurePathPrefix = assembled
                    ? (format == UpdateFormat.ZIP ? "zip-assembled-archive" : "assembled.") : null;
            Throwable failure = kind.failure("rewrite read");
            Throwable cleanup = shared ? failure : kind.failure("rewrite read cleanup");
            storage.fail(Stage.READ, failure);
            storage.fail(Stage.READ_CLOSE, cleanup);
            assertSame(failure, assertThrows(failure.getClass(), fileSystem::close));
            if (!shared) {
                assertTrue(Stream.of(failure.getSuppressed()).anyMatch(value -> value == cleanup));
            }
            int readAttempts = storage.count(Stage.READ);
            fileSystem.close();
            fileSystem.close();
            assertEquals(readAttempts, storage.count(Stage.READ), "Cleanup must not retry the rewrite");
        } finally {
            storage.failures.clear();
            fileSystem.close();
        }
        storage.assertReleased();
        assertArrayEquals(original, Files.readAllBytes(archive));
        try (var reader = format.open(archive)) {
            assertArrayEquals(BODY, Files.readAllBytes(reader.getPath("entry")));
        }
    }

    /// Supplies checked and unchecked read failures, with shared or distinct cleanup exceptions, at both rewrite stages.
    private static Stream<Arguments> updateRewriteFailures() {
        return Stream.of(UpdateFormat.values()).flatMap(format -> Stream.of(FailureKind.values())
                .flatMap(kind -> (format != UpdateFormat.AR
                        ? Stream.of(false, true) : Stream.of(false))
                        .flatMap(assembled -> Stream.of(false, true)
                                .map(shared -> Arguments.of(format, kind, assembled, shared)))));
    }

    /// Reuses one close failure across strict resource cleanup and archive cleanup without self-suppression.
    @ParameterizedTest
    @MethodSource("strictCloseFailures")
    void preservesRepeatedStrictCloseFailure(UpdateFormat format, FailureKind kind, @TempDir Path directory)
            throws IOException {
        Path archive = directory.resolve("archive");
        try (var writer = format.create(archive)) {
            Files.write(writer.getPath("/original"), BODY);
        }
        TrackingStorage storage = new TrackingStorage();
        var fileSystem = format.update(archive, storage, ArkivoFileSystemThreadSafety.STRICT);
        var channel = Files.newByteChannel(fileSystem.getPath("/original"),
                Set.of(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
        channel.write(ByteBuffer.wrap(new byte[]{1, 2, 3}));
        Throwable failure = kind.failure("repeated strict cleanup failure");
        storage.fail(Stage.WRITE_CLOSE, failure, failure, failure, failure, failure);
        try {
            assertCleanupCause(failure, assertThrows(IOException.class, fileSystem::close));
        } finally {
            storage.failures.clear();
            fileSystem.close();
            channel.close();
        }
        storage.assertReleased();
        try (var reader = format.open(archive)) {
            assertArrayEquals(BODY, Files.readAllBytes(reader.getPath("/original")));
        }
    }

    /// Supplies each writable archive format and checked or unchecked cleanup failures.
    private static Stream<Arguments> strictCloseFailures() {
        return Stream.of(UpdateFormat.values()).flatMap(format -> Stream.of(FailureKind.values())
                .map(kind -> Arguments.of(format, kind)));
    }

    /// Archive file systems that commit random-access bodies from staging storage.
    @NotNullByDefault
    private enum UpdateFormat {
        /// Unix archive members.
        AR,
        /// Tape archive entries.
        TAR,
        /// ZIP local records and central directory.
        ZIP,
        /// 7z streams and index.
        SEVEN_ZIP;

        /// Creates a small source archive using the format's default options.
        private ArkivoFileSystem create(Path path) throws IOException {
            return switch (this) {
                case AR -> ArArkivoFileSystem.create(path);
                case TAR -> TarArkivoFileSystem.create(path);
                case ZIP -> ZipArkivoFileSystem.create(path);
                case SEVEN_ZIP -> SevenZipArkivoFileSystem.create(path);
            };
        }

        /// Opens an update session with fault-injecting body storage.
        private ArkivoFileSystem update(Path path, TrackingStorage storage) throws IOException {
            return update(path, storage, ArchiveUpdateOptions.DEFAULT.threadSafety());
        }

        /// Opens an update session with the requested lifecycle coordination and fault-injecting storage.
        private ArkivoFileSystem update(Path path, TrackingStorage storage, ArkivoFileSystemThreadSafety threadSafety)
                throws IOException {
            ArchiveUpdateOptions common = ArchiveUpdateOptions.DEFAULT.withEditStorageFactory(() -> storage)
                    .withThreadSafety(threadSafety);
            return switch (this) {
                case AR -> ArArkivoFileSystem.update(path, ArArchiveOptions.UPDATE_DEFAULTS.withCommon(common));
                case TAR -> TarArkivoFileSystem.update(path, TarArchiveOptions.UPDATE_DEFAULTS.withCommon(common));
                case ZIP -> ZipArkivoFileSystem.update(path, ZipArchiveOptions.UPDATE_DEFAULTS.withCommon(common));
                case SEVEN_ZIP -> SevenZipArkivoFileSystem.update(path,
                        SevenZipArchiveOptions.UPDATE_DEFAULTS.withCommon(common));
            };
        }

        /// Reopens the published archive independently of its update storage.
        private ArkivoFileSystem open(Path path) throws IOException {
            return switch (this) {
                case AR -> ArArkivoFileSystem.open(path);
                case TAR -> TarArkivoFileSystem.open(path);
                case ZIP -> ZipArkivoFileSystem.open(path);
                case SEVEN_ZIP -> SevenZipArkivoFileSystem.open(path);
            };
        }
    }

    /// Ways to stage a new or existing entry.
    @NotNullByDefault
    private enum EntryChange {
        /// Changes an existing body without forcing a commit at channel creation.
        EDIT,
        /// Clears an existing body before the first write.
        REPLACE,
        /// Forces each write to the end of the staged body.
        APPEND,
        /// Creates a previously absent entry.
        CREATE;

        /// Returns the open options that select this entry transition.
        private @Unmodifiable Set<StandardOpenOption> options() {
            return switch (this) {
                case EDIT -> Set.of(StandardOpenOption.WRITE);
                case REPLACE -> Set.of(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                case APPEND -> Set.of(StandardOpenOption.APPEND);
                case CREATE -> Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW);
            };
        }
    }

    /// Verifies failures at every physical write boundary prevent any subsequent archive output.
    @ParameterizedTest
    @MethodSource("streamingWriteFailures")
    void stopsAfterEveryTargetWriteFailure(Format format, FailureKind kind, boolean partial) throws IOException {
        TrackingStorage baselineStorage = new TrackingStorage();
        TrackingOutput baseline = new TrackingOutput(baselineStorage);
        try (ArkivoStreamingWriter writer = format.writer(baseline, baselineStorage)) {
            try (OutputStream body = writer.beginFile("entry").openOutputStream()) {
                body.write(BODY);
            }
        }
        int writes = baselineStorage.count(Stage.TARGET_WRITE);
        for (int index = 1; index <= writes; index++) {
            TrackingStorage storage = new TrackingStorage();
            TrackingOutput target = new TrackingOutput(storage);
            Throwable failure = kind.failure("target write " + index);
            target.failureWrite = index;
            target.writeFailure = failure;
            target.partialFailure = partial;
            ArkivoStreamingWriter writer = format.writer(target, storage);
            OutputStream body = writer.beginFile("entry").openOutputStream();
            body.write(BODY);
            assertSame(failure, assertThrows(failure.getClass(), () -> {
                body.close();
                writer.close();
            }));
            byte[] incomplete = target.bytes.toByteArray();
            int attempts = storage.count(Stage.TARGET_WRITE);
            body.close();
            try {
                writer.close();
            } catch (IOException exception) {
                assertSame(failure, exception.getCause());
            }
            writer.close();
            assertEquals(index, attempts);
            assertEquals(attempts, storage.count(Stage.TARGET_WRITE));
            assertArrayEquals(incomplete, target.bytes.toByteArray());
            assertEquals(1, storage.count(Stage.TARGET_CLOSE));
            storage.assertReleased();
        }
    }

    /// Verifies a staged-body read failure after its header prevents new entries and end markers.
    @ParameterizedTest
    @MethodSource("streamingFailures")
    void stopsAfterStagedBodyReadFailure(Format format, FailureKind kind) throws IOException {
        TrackingStorage storage = new TrackingStorage();
        TrackingOutput target = new TrackingOutput(storage);
        ArkivoStreamingWriter writer = format.writer(target, storage);
        OutputStream body = writer.beginFile("entry").openOutputStream();
        body.write(BODY);
        Throwable failure = kind.failure("body read");
        storage.fail(Stage.READ, failure);
        assertSame(failure, assertThrows(failure.getClass(), body::close));
        body.close();
        assertSame(failure, assertThrows(IOException.class, () -> writer.beginFile("later")).getCause());
        byte[] incomplete = target.bytes.toByteArray();
        assertTrue(incomplete.length > 0);
        assertSame(failure, assertThrows(IOException.class, writer::close).getCause());
        writer.close();
        assertArrayEquals(incomplete, target.bytes.toByteArray());
        storage.assertReleased();
    }

    /// Preserves the staging read failure when closing the same channel also fails.
    @ParameterizedTest
    @MethodSource("streamingFailures")
    void preservesReadFailureDuringBodyCleanup(Format format, FailureKind kind) throws IOException {
        for (boolean shared : new boolean[]{false, true}) {
            TrackingStorage storage = new TrackingStorage();
            TrackingOutput target = new TrackingOutput(storage);
            ArkivoStreamingWriter writer = format.writer(target, storage);
            OutputStream body = writer.beginFile("entry").openOutputStream();
            body.write(BODY);
            Throwable failure = kind.failure("body read");
            Throwable cleanup = shared ? failure : kind.failure("body cleanup");
            storage.fail(Stage.READ, failure);
            storage.fail(Stage.READ_CLOSE, cleanup);
            assertSame(failure, assertThrows(failure.getClass(), body::close));
            assertEquals(1, failure.getSuppressed().length);
            if (shared) {
                assertSame(failure, assertInstanceOf(IOException.class, failure.getSuppressed()[0]).getCause());
            } else {
                assertSame(cleanup, failure.getSuppressed()[0]);
            }
            body.close();
            byte[] incomplete = target.bytes.toByteArray();
            assertSame(failure, assertThrows(IOException.class, writer::close).getCause());
            writer.close();
            assertArrayEquals(incomplete, target.bytes.toByteArray());
            assertEquals(2, storage.count(Stage.READ_CLOSE));
            storage.assertReleased();
        }
    }

    /// Stops a stalled staging transfer without retrying the read or emitting archive completion records.
    @ParameterizedTest
    @EnumSource(Format.class)
    void stopsAfterStalledStagingRead(Format format) throws IOException {
        TrackingStorage storage = new TrackingStorage();
        TrackingOutput target = new TrackingOutput(storage);
        ArkivoStreamingWriter writer = format.writer(target, storage);
        OutputStream body = writer.beginFile("entry").openOutputStream();
        body.write(BODY);
        storage.stallRead = true;
        IOException failure = assertThrows(IOException.class, body::close);
        assertEquals(1, storage.count(Stage.READ));
        body.close();
        assertSame(failure, assertThrows(IOException.class, () -> writer.beginFile("later")).getCause());
        byte[] incomplete = target.bytes.toByteArray();
        assertTrue(incomplete.length > 0);
        assertSame(failure, assertThrows(IOException.class, writer::close).getCause());
        writer.close();
        assertArrayEquals(incomplete, target.bytes.toByteArray());
        storage.assertReleased();
    }

    /// Verifies premature staging EOF is terminal after the entry's declared size has been emitted.
    @ParameterizedTest
    @EnumSource(Format.class)
    void stopsAfterPrematureStagingEof(Format format) throws IOException {
        TrackingStorage storage = new TrackingStorage();
        TrackingOutput target = new TrackingOutput(storage);
        ArkivoStreamingWriter writer = format.writer(target, storage);
        OutputStream body = writer.beginFile("entry").openOutputStream();
        body.write(BODY);
        storage.prematureEof = true;
        EOFException failure = assertThrows(EOFException.class, body::close);
        body.close();
        byte[] incomplete = target.bytes.toByteArray();
        assertTrue(incomplete.length > 0);
        assertSame(failure, assertThrows(IOException.class, writer::close).getCause());
        writer.close();
        assertArrayEquals(incomplete, target.bytes.toByteArray());
        storage.assertReleased();
    }

    /// Verifies direct AR writes retain the target failure instead of replacing it with a body-size error.
    @ParameterizedTest
    @EnumSource(FailureKind.class)
    void stopsAfterDirectArBodyWriteFailure(FailureKind kind) throws IOException {
        TrackingStorage storage = new TrackingStorage();
        TrackingOutput target = new TrackingOutput(storage);
        ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.open(target, storage);
        ArkivoStreamingWriter.Entry entry = writer.beginFile("entry");
        ArArkivoEntryAttributeView view = Objects.requireNonNull(entry.attributeView(ArArkivoEntryAttributeView.class));
        view.setSize(BODY.length);
        OutputStream body = entry.openOutputStream();
        Throwable failure = kind.failure("direct AR write");
        target.failureWrite = storage.count(Stage.TARGET_WRITE) + 1;
        target.writeFailure = failure;
        target.partialFailure = true;
        assertSame(failure, assertThrows(failure.getClass(), () -> body.write(BODY)));
        assertCleanupCause(failure, assertThrows(IOException.class, body::close));
        body.close();
        byte[] incomplete = target.bytes.toByteArray();
        assertSame(failure, assertThrows(IOException.class, () -> writer.beginFile("later")).getCause());
        assertSame(failure, assertThrows(IOException.class, writer::close).getCause());
        writer.close();
        assertArrayEquals(incomplete, target.bytes.toByteArray());
        storage.assertReleased();
    }

    /// Verifies a failed direct-member padding write is not repeated by body or writer close.
    @ParameterizedTest
    @EnumSource(FailureKind.class)
    void doesNotRetryDirectArPadding(FailureKind kind) throws IOException {
        TrackingStorage storage = new TrackingStorage();
        TrackingOutput target = new TrackingOutput(storage);
        ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.open(target, storage);
        ArkivoStreamingWriter.Entry entry = writer.beginFile("entry");
        Objects.requireNonNull(entry.attributeView(ArArkivoEntryAttributeView.class)).setSize(1L);
        OutputStream body = entry.openOutputStream();
        body.write(7);
        Throwable failure = kind.failure("direct AR padding");
        storage.fail(Stage.TARGET_WRITE, failure);
        assertSame(failure, assertThrows(failure.getClass(), body::close));
        int attempts = storage.count(Stage.TARGET_WRITE);
        body.close();
        assertSame(failure, assertThrows(IOException.class, writer::close).getCause());
        writer.close();
        assertEquals(attempts, storage.count(Stage.TARGET_WRITE));
        storage.assertReleased();
    }

    /// Verifies an undersized direct AR body cannot be followed by another member.
    @Test
    void stopsAfterDirectArBodyUnderrun() throws IOException {
        TrackingStorage storage = new TrackingStorage();
        TrackingOutput target = new TrackingOutput(storage);
        ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.open(target, storage);
        ArkivoStreamingWriter.Entry entry = writer.beginFile("entry");
        ArArkivoEntryAttributeView view = Objects.requireNonNull(entry.attributeView(ArArkivoEntryAttributeView.class));
        view.setSize(3L);
        OutputStream body = entry.openOutputStream();
        body.write(1);
        IOException failure = assertThrows(IOException.class, body::close);
        body.close();
        byte[] incomplete = target.bytes.toByteArray();
        assertSame(failure, assertThrows(IOException.class, () -> writer.beginFile("later")).getCause());
        assertSame(failure, assertThrows(IOException.class, writer::close).getCause());
        writer.close();
        assertArrayEquals(incomplete, target.bytes.toByteArray());
        storage.assertReleased();
    }

    /// Creates an independently encoded ZIP source for update tests.
    private static Path createZip(Path directory) throws IOException {
        Path archive = directory.resolve("archive.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("original"));
            output.write(BODY);
            output.closeEntry();
        }
        return archive;
    }

    /// Opens an update session using the fault-injecting storage.
    private static ZipArkivoFileSystem updateZip(Path archive, TrackingStorage storage) throws IOException {
        return ZipArkivoFileSystem.update(archive, ZipArchiveOptions.UPDATE_DEFAULTS.withCommon(
                ArchiveUpdateOptions.DEFAULT.withEditStorageFactory(() -> storage)));
    }

    /// Formats whose unknown-size streaming bodies use configurable staging storage.
    @NotNullByDefault
    private enum Format {
        /// Unix archive members.
        AR,
        /// Tape archive entries.
        TAR,
        /// Copy-in/copy-out entries.
        CPIO;

        /// Opens an owning writer for this format.
        private ArkivoStreamingWriter writer(OutputStream output, ArkivoEditStorage storage) throws IOException {
            return switch (this) {
                case AR -> ArArkivoStreamingWriter.open(output, storage);
                case TAR -> TarArkivoStreamingWriter.open(output, storage);
                case CPIO -> CPIOArkivoStreamingWriter.open(output, CPIOArchiveOptions.CREATE_DEFAULTS.withCommon(
                        ArchiveCreateOptions.DEFAULT.withEditStorageFactory(() -> storage)));
            };
        }

        /// Opens the corresponding reader without retaining its input beyond reader closure.
        private ArkivoStreamingReader reader(InputStream input) throws IOException {
            return switch (this) {
                case AR -> ArArkivoStreamingReader.open(input);
                case TAR -> TarArkivoStreamingReader.open(input);
                case CPIO -> CPIOArkivoStreamingReader.open(input);
            };
        }

        /// Verifies exactly one committed body, or an empty archive when body staging failed.
        private void verifyArchive(byte[] bytes, boolean hasEntry) throws IOException {
            try (ArkivoStreamingReader reader = reader(new ByteArrayInputStream(bytes))) {
                assertEquals(hasEntry, reader.next());
                if (hasEntry) {
                    try (InputStream input = reader.openInputStream()) {
                        assertArrayEquals(BODY, input.readAllBytes());
                    }
                    assertFalse(reader.next());
                }
            }
        }
    }

    /// Exception categories accepted from user-provided storage and output implementations.
    @NotNullByDefault
    private enum FailureKind {
        /// A checked I/O failure.
        IO,
        /// An unchecked storage failure.
        RUNTIME,
        /// An unrecoverable failure whose identity must still be preserved during cleanup.
        ERROR;

        /// Creates a fresh failure for one operation boundary.
        private Throwable failure(String message) {
            return switch (this) {
                case IO -> new IOException(message);
                case RUNTIME -> new IllegalStateException(message);
                case ERROR -> new AssertionError(message);
            };
        }
    }

    /// Independently observable staging and output operations.
    @NotNullByDefault
    private enum Stage {
        /// Creates a staged content object.
        CREATE,
        /// Opens a writable channel over staged content.
        WRITE_OPEN,
        /// Reads staged bytes after their entry header has been emitted.
        READ,
        /// Queries a staging channel's current position.
        POSITION,
        /// Changes a staging channel's current position.
        SEEK,
        /// Queries a staging channel's size.
        SIZE,
        /// Queries retained content size during publication.
        CONTENT_SIZE,
        /// Fails after storing a prefix of the offered bytes.
        WRITE,
        /// Fails after changing the stored size.
        TRUNCATE,
        /// Closes a staging writer.
        WRITE_CLOSE,
        /// Closes a body reader.
        READ_CLOSE,
        /// Releases retained content.
        CONTENT,
        /// Closes the storage container.
        STORAGE,
        /// Writes archive bytes to the final target.
        TARGET_WRITE,
        /// Closes the final target.
        TARGET_CLOSE
    }

    /// Tracks resource dependencies and injects failures without allocating external temporary content.
    @NotNullByDefault
    private static final class TrackingStorage implements ArkivoEditStorage {
        /// In-memory backing storage used for byte operations.
        private final ArkivoEditStorage delegate = ArkivoEditStorage.memory();

        /// Pending failures at each operation boundary.
        private final EnumMap<Stage, ArrayDeque<Throwable>> failures = new EnumMap<>(Stage.class);

        /// Attempt counts, including failed operations.
        private final EnumMap<Stage, Integer> counts = new EnumMap<>(Stage.class);

        /// Whether staged reads should report end-of-input despite retained content.
        private boolean prematureEof;

        /// Whether the next staged read returns zero and rejects any retry.
        private boolean stallRead;

        /// Optional synchronization gates for a partially completed storage write.
        private @Nullable WriteGate writeGate;

        /// Limits queued read and read-close failures to matching content paths, or all paths when `null`.
        private @Nullable String readFailurePathPrefix;

        /// Number of content objects whose deletion has not succeeded.
        private int liveContents;

        /// Whether storage cleanup has succeeded.
        private boolean closed;

        /// Arms failures consumed in order at one boundary.
        private void fail(Stage stage, Throwable... values) {
            ArrayDeque<Throwable> queue = failures.computeIfAbsent(stage, ignored -> new ArrayDeque<>());
            for (Throwable value : values) {
                queue.add(value);
            }
        }

        /// Records an operation and reports its next queued failure.
        private void attempt(Stage stage) throws IOException {
            counts.merge(stage, 1, Integer::sum);
            @Nullable ArrayDeque<Throwable> queue = failures.get(stage);
            @Nullable Throwable failure = queue == null ? null : queue.poll();
            if (failure instanceof IOException exception) {
                throw exception;
            }
            if (failure instanceof RuntimeException exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }

        /// Returns the number of attempts at one boundary.
        private int count(Stage stage) {
            return counts.getOrDefault(stage, 0);
        }

        /// Counts every read-side operation but consumes failures only for the selected rewrite stage.
        private void attemptRead(Stage stage, Content content) throws IOException {
            if (readFailurePathPrefix == null || content.path.startsWith(readFailurePathPrefix)) {
                attempt(stage);
            } else {
                counts.merge(stage, 1, Integer::sum);
            }
        }

        /// Allocates content with independently tracked read and write channels.
        @Override
        public ArkivoStoredContent createContent(String path, long expectedSize) throws IOException {
            attempt(Stage.CREATE);
            ArkivoStoredContent content = delegate.createContent(path, expectedSize);
            liveContents++;
            return new Content(content, path);
        }

        /// Rejects premature storage closure and otherwise releases the backing storage.
        @Override
        public void close() throws IOException {
            assertEquals(0, liveContents, "Storage closed before content cleanup");
            attempt(Stage.STORAGE);
            delegate.close();
            closed = true;
        }

        /// Verifies all owned resources were released successfully.
        private void assertReleased() {
            assertEquals(0, liveContents);
            assertTrue(closed);
        }

        /// Keeps channel ownership separate from content deletion.
        @NotNullByDefault
        private final class Content implements ArkivoStoredContent {
            /// The logical content path used to select a rewrite stage.
            private final String path;

            /// Backing content whose lifetime is being tracked.
            private final ArkivoStoredContent delegate;

            /// Channels that have not closed successfully.
            private int liveChannels;

            /// Whether this content has been released.
            private boolean closed;

            /// Takes ownership of one backing body.
            private Content(ArkivoStoredContent delegate, String path) {
                this.delegate = delegate;
                this.path = path;
            }

            /// Opens and tracks one independently positioned channel.
            @Override
            public SeekableByteChannel openChannel(Set<? extends OpenOption> options) throws IOException {
                if (options.contains(StandardOpenOption.WRITE)) attempt(Stage.WRITE_OPEN);
                SeekableByteChannel channel = delegate.openChannel(options);
                liveChannels++;
                return new ContentChannel(channel, this, options.contains(StandardOpenOption.WRITE)
                        ? Stage.WRITE_CLOSE : Stage.READ_CLOSE);
            }

            /// Returns the current body size.
            @Override
            public long size() throws IOException {
                attempt(Stage.CONTENT_SIZE);
                return delegate.size();
            }

            /// Rejects deletion before channel closure and tracks successful release exactly once.
            @Override
            public void close() throws IOException {
                assertEquals(0, liveChannels, "Content deleted before channel cleanup");
                attempt(Stage.CONTENT);
                delegate.close();
                if (!closed) {
                    liveContents--;
                    closed = true;
                }
            }
        }

        /// Forwards byte operations and tracks whether a failed close still owns a channel.
        @NotNullByDefault
        private final class ContentChannel implements SeekableByteChannel {
            /// Backing channel retained until successful closure.
            private final SeekableByteChannel delegate;

            /// Content whose deletion depends on this channel.
            private final Content content;

            /// Close boundary selected from the requested access mode.
            private final Stage closeStage;

            /// Whether this channel remains owned.
            private boolean open = true;

            /// Takes ownership of an independently opened backing channel.
            private ContentChannel(SeekableByteChannel delegate, Content content, Stage closeStage) {
                this.delegate = delegate;
                this.content = content;
                this.closeStage = closeStage;
            }

            /// Reads bytes from the delegate.
            @Override
            public int read(ByteBuffer target) throws IOException {
                if (writeGate != null && writeGate.release.getCount() != 0) {
                    writeGate.readWhileWriting = true;
                }
                if (prematureEof) {
                    return -1;
                }
                attemptRead(Stage.READ, content);
                if (stallRead) {
                    stallRead = false;
                    fail(Stage.READ, new AssertionError("Stalled staging read was retried"));
                    return 0;
                }
                return delegate.read(target);
            }

            /// Writes bytes to the delegate.
            @Override
            public int write(ByteBuffer source) throws IOException {
                @Nullable ArrayDeque<Throwable> queue = failures.get(Stage.WRITE);
                int limit = source.limit();
                if ((writeGate != null || queue != null && !queue.isEmpty()) && source.hasRemaining()) {
                    source.limit(source.position() + 1);
                }
                int count;
                try {
                    count = delegate.write(source);
                } finally {
                    source.limit(limit);
                }
                if (writeGate != null) {
                    writeGate.entered.countDown();
                    try {
                        if (!writeGate.release.await(5, TimeUnit.SECONDS)) {
                            throw new IOException("Timed out waiting to report the write failure");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IOException(exception);
                    }
                }
                attempt(Stage.WRITE);
                return count;
            }

            /// Returns the delegate position.
            @Override
            public long position() throws IOException {
                attempt(Stage.POSITION);
                return delegate.position();
            }

            /// Changes the delegate position.
            @Override
            public SeekableByteChannel position(long position) throws IOException {
                attempt(Stage.SEEK);
                delegate.position(position);
                return this;
            }

            /// Returns the current body size.
            @Override
            public long size() throws IOException {
                attempt(Stage.SIZE);
                return delegate.size();
            }

            /// Truncates the delegate.
            @Override
            public SeekableByteChannel truncate(long size) throws IOException {
                delegate.truncate(size);
                attempt(Stage.TRUNCATE);
                return this;
            }

            /// Returns whether channel closure has not yet succeeded.
            @Override
            public boolean isOpen() {
                return open;
            }

            /// Leaves the delegate owned when an injected close failure occurs.
            @Override
            public void close() throws IOException {
                if (closeStage == Stage.READ_CLOSE) attemptRead(closeStage, content);
                else attempt(closeStage);
                delegate.close();
                if (open) {
                    content.liveChannels--;
                    open = false;
                }
                if (closeStage == Stage.WRITE_CLOSE && writeGate != null) {
                    writeGate.closed.countDown();
                }
            }
        }
    }

    /// Captures final archive bytes while exposing independent write and close failure boundaries.
    @NotNullByDefault
    private static final class TrackingOutput extends OutputStream {
        /// Indicates that no numbered target write is configured to fail.
        private static final int NO_WRITE_FAILURE = -1;

        /// Final bytes emitted by the writer.
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        /// Shared operation recorder and fault queues.
        private final TrackingStorage storage;

        /// Whether target closure has completed.
        private boolean closed;

        /// The one-based target write that should fail, or [#NO_WRITE_FAILURE] when not armed.
        private int failureWrite = NO_WRITE_FAILURE;

        /// The failure injected at the selected write.
        private @Nullable Throwable writeFailure;

        /// Whether one byte reaches the target before the selected write fails.
        private boolean partialFailure;

        /// Arms a target failure after any configured partial output.
        private void prepareWrite(int firstByte, boolean nonempty) {
            if (failureWrite == storage.count(Stage.TARGET_WRITE) + 1) {
                if (partialFailure && nonempty) {
                    bytes.write(firstByte);
                }
                storage.fail(Stage.TARGET_WRITE, Objects.requireNonNull(writeFailure));
            }
        }

        /// Creates a target controlled by the storage test's fault queues.
        private TrackingOutput(TrackingStorage storage) {
            this.storage = storage;
        }

        /// Writes one byte after checking target state and injected failures.
        @Override
        public void write(int value) throws IOException {
            assertFalse(closed, "Archive output resumed after close");
            prepareWrite(value, true);
            storage.attempt(Stage.TARGET_WRITE);
            bytes.write(value);
        }

        /// Writes a byte range after checking target state and injected failures.
        @Override
        public void write(byte[] source, int offset, int length) throws IOException {
            assertFalse(closed, "Archive output resumed after close");
            prepareWrite(length == 0 ? 0 : source[offset], length != 0);
            storage.attempt(Stage.TARGET_WRITE);
            bytes.write(source, offset, length);
        }

        /// Closes this target only after any queued failure has been reported.
        @Override
        public void close() throws IOException {
            storage.attempt(Stage.TARGET_CLOSE);
            closed = true;
        }
    }
}
