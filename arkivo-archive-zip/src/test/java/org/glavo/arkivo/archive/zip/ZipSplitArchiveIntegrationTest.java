// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArkivoVolumeOutput;
import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.glavo.arkivo.archive.ArkivoVolumeTarget;
import org.glavo.arkivo.archive.internal.ReadOnlyByteArrayChannel;
import org.glavo.arkivo.archive.zip.internal.ZipArkivoFileSystemProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.zip.CRC32;

import static org.glavo.arkivo.archive.zip.ZipTestArchiveFixtures.containsBytes;
import static org.glavo.arkivo.archive.zip.ZipTestArchiveFixtures.createTemporaryArchivePath;
import static org.glavo.arkivo.archive.zip.ZipTestArchiveFixtures.deleteTemporaryArchive;
import static org.glavo.arkivo.archive.zip.ZipTestArchiveFixtures.splitVolumePath;
import static org.glavo.arkivo.archive.zip.ZipTestArchiveFixtures.splitVolumePaths;
import static org.glavo.arkivo.archive.zip.ZipTestArchiveFixtures.updateSourceZip;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests ZIP split-volume creation, update, publication, and rollback behavior.
@NotNullByDefault
public final class ZipSplitArchiveIntegrationTest {
    /// The standards-compliant split size used by these tests.
    private static final int TEST_SPLIT_SIZE = Math.toIntExact(ZipArkivoFileSystem.MINIMUM_SPLIT_SIZE);

    /// Verifies that ZIP file system writes create split output volumes.
    @Test
    public void fileSystemCreateWritesSplitVolumes() throws IOException {
        Path archivePath = createTemporaryArchivePath("fs-create-split-");

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystemProvider.instance().newFileSystem(
                    archivePath,
                    Map.of(
                            "arkivo.openOptions",
                            Set.of(
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.TRUNCATE_EXISTING,
                                    StandardOpenOption.WRITE
                            ),
                            "arkivo.zip.splitSize",
                            (long) TEST_SPLIT_SIZE
                    )
            )) {
                Files.writeString(fileSystem.getPath("/hello.txt"), "split file system", StandardCharsets.UTF_8);
                Files.write(fileSystem.getPath("/padding.bin"), splitTestContent(TEST_SPLIT_SIZE * 2));
            }

            List<Path> volumes = splitVolumePaths(archivePath);
            assertEquals(true, volumes.size() > 1);
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(ArkivoVolumeSource.of(volumes))) {
                assertEquals(
                        "split file system",
                        Files.readString(fileSystem.getPath("/hello.txt"), StandardCharsets.UTF_8)
                );
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that ZIP streaming writer factories create split output volumes.
    @Test
    public void streamingWriterCreatesSplitVolumes() throws IOException {
        TestVolumeTarget target = new TestVolumeTarget();

        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(target, TEST_SPLIT_SIZE)) {
            var streamEntry = writer.beginFile("stream.txt");
            try (OutputStream output = streamEntry.openOutputStream()) {
                output.write("split streaming writer".getBytes(StandardCharsets.UTF_8));
            }
            var paddingEntry = writer.beginFile("padding.bin");
            ZipArkivoEntryAttributeView view = paddingEntry.attributeView(ZipArkivoEntryAttributeView.class);
            assertNotNull(view);
            view.setMethod(ZipMethod.STORED);
            try (OutputStream output = paddingEntry.openOutputStream()) {
                output.write(splitTestContent(TEST_SPLIT_SIZE * 2));
            }
        }

        TestVolumeOutput output = target.output();
        assertEquals(true, output.volumeCount() > 1);
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(output.volumeSource())) {
            assertEquals(
                    "split streaming writer",
                    Files.readString(fileSystem.getPath("/stream.txt"), StandardCharsets.UTF_8)
            );
        }
    }

    /// Verifies that streaming ZIP writers publish readable split archives to custom volume targets.
    @Test
    public void streamingWriterPublishesToCustomVolumeTarget() throws IOException {
        byte[] content = splitTestContent(TEST_SPLIT_SIZE * 2);
        TestVolumeTarget target = new TestVolumeTarget();

        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(target, TEST_SPLIT_SIZE)) {
            var contentEntry = writer.beginFile("content.bin");
            ZipArkivoEntryAttributeView view = contentEntry.attributeView(ZipArkivoEntryAttributeView.class);
            assertNotNull(view);
            view.setMethod(ZipMethod.STORED);
            try (OutputStream output = contentEntry.openOutputStream()) {
                output.write(content);
            }
        }

        TestVolumeOutput volumeOutput = target.output();
        assertEquals(true, volumeOutput.volumeCount() > 1);
        assertEquals(volumeOutput.volumeCount() - 1L, volumeOutput.finalVolumeIndex());
        assertEquals(1, volumeOutput.commitCount());
        assertEquals(0, volumeOutput.rollbackCount());
        assertEquals(1, volumeOutput.closeCount());
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(volumeOutput.volumeSource())) {
            assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/content.bin")));
        }
    }

    /// Verifies writable ZIP file systems publish split archives to custom volume targets.
    @Test
    public void fileSystemCreatesSplitArchiveOnCustomVolumeTarget() throws IOException {
        byte[] content = splitTestContent(TEST_SPLIT_SIZE * 2);
        TestVolumeTarget target = new TestVolumeTarget();

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.create(target, TEST_SPLIT_SIZE)) {
            Path entry = fileSystem.getPath("/content.bin");
            Files.write(entry, content);
        }

        TestVolumeOutput output = target.output();
        assertEquals(true, output.volumeCount() > 1);
        assertEquals(output.volumeCount() - 1L, output.finalVolumeIndex());
        assertEquals(true, output.allVolumeSizesAtMost(TEST_SPLIT_SIZE));
        assertEquals(1, output.commitCount());
        assertEquals(0, output.rollbackCount());
        assertEquals(1, output.closeCount());
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(output.volumeSource())) {
            assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/content.bin")));
        }
    }

    /// Verifies complete-rewrite mutation from split input to explicitly sized split output.
    @Test
    public void fileSystemUpdatesSplitArchiveOnCustomVolumeTarget() throws IOException {
        byte[] keepContent = splitTestContent(TEST_SPLIT_SIZE * 2);
        byte[] replacedContent = "replaced-local-record-secret".getBytes(StandardCharsets.UTF_8);
        byte[] removedContent = "removed-local-record-secret".getBytes(StandardCharsets.UTF_8);
        TestVolumeTarget originalTarget = new TestVolumeTarget();

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.create(originalTarget, TEST_SPLIT_SIZE)) {
            Files.write(fileSystem.getPath("/keep.bin"), keepContent);
            Files.write(fileSystem.getPath("/replace.txt"), replacedContent);
            Files.write(fileSystem.getPath("/remove.txt"), removedContent);
        }

        TrackingVolumeSource source =
                new TrackingVolumeSource(originalTarget.output().volumeSource());
        TestVolumeTarget updatedTarget = new TestVolumeTarget();
        byte[] updatedContent = "updated".getBytes(StandardCharsets.UTF_8);
        byte[] addedContent = new byte[137];
        for (int index = 0; index < addedContent.length; index++) {
            addedContent[index] = (byte) (index * 17);
        }

        try (ZipArkivoFileSystem fileSystem =
                     ZipArkivoFileSystem.update(source, updatedTarget, TEST_SPLIT_SIZE)) {
            assertEquals(false, fileSystem.isReadOnly());
            assertArrayEquals(keepContent, Files.readAllBytes(fileSystem.getPath("/keep.bin")));
            assertArrayEquals(replacedContent, Files.readAllBytes(fileSystem.getPath("/replace.txt")));
            Files.write(fileSystem.getPath("/replace.txt"), updatedContent);
            Files.delete(fileSystem.getPath("/remove.txt"));
            Files.write(fileSystem.getPath("/added.bin"), addedContent);
            assertArrayEquals(updatedContent, Files.readAllBytes(fileSystem.getPath("/replace.txt")));
            assertArrayEquals(addedContent, Files.readAllBytes(fileSystem.getPath("/added.bin")));
            assertThrows(
                    NoSuchFileException.class,
                    () -> Files.readAllBytes(fileSystem.getPath("/remove.txt"))
            );
        }

        assertEquals(1, source.closeCount());
        TestVolumeOutput output = updatedTarget.output();
        assertEquals(true, output.volumeCount() > 1);
        assertEquals(true, output.allVolumeSizesAtMost(TEST_SPLIT_SIZE));
        assertEquals(1, output.commitCount());
        assertEquals(0, output.rollbackCount());
        assertEquals(1, output.closeCount());
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(output.volumeSource())) {
            assertEquals(0L, fileSystem.preambleSize());
            assertArrayEquals(keepContent, Files.readAllBytes(fileSystem.getPath("/keep.bin")));
            assertArrayEquals(updatedContent, Files.readAllBytes(fileSystem.getPath("/replace.txt")));
            assertArrayEquals(addedContent, Files.readAllBytes(fileSystem.getPath("/added.bin")));
            assertEquals(false, Files.exists(fileSystem.getPath("/remove.txt")));
        }
        try (ZipArkivoFileSystem fileSystem =
                     ZipArkivoFileSystem.open(originalTarget.output().volumeSource())) {
            assertArrayEquals(replacedContent, Files.readAllBytes(fileSystem.getPath("/replace.txt")));
            assertArrayEquals(removedContent, Files.readAllBytes(fileSystem.getPath("/remove.txt")));
        }
        byte[] updatedArchive = output.archiveBytes();
        ByteBuffer updatedHeader = ByteBuffer.wrap(updatedArchive).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x08074b50, updatedHeader.getInt());
        assertEquals(0x04034b50, updatedHeader.getInt());
        assertEquals(false, containsBytes(updatedArchive, "remove.txt".getBytes(StandardCharsets.UTF_8)));
    }

    /// Verifies explicit volume updates preserve preamble bytes while changing the output split layout.
    @Test
    public void volumeUpdatePreservesPreambleInSplitOutput() throws IOException {
        byte[] preamble = new byte[]{9, 7, 5, 3, 1};
        byte[] sourceArchive = updateSourceZip(preamble);
        TrackingVolumeSource source = new TrackingVolumeSource(index ->
                index == 0L ? new ReadOnlyByteArrayChannel(sourceArchive) : null
        );
        TestVolumeTarget target = new TestVolumeTarget();

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.update(source, target, TEST_SPLIT_SIZE)) {
            assertEquals(preamble.length, fileSystem.preambleSize());
            assertPreambleContent(preamble, fileSystem);
            Files.writeString(fileSystem.getPath("/replace.txt"), "new", StandardCharsets.UTF_8);
            Files.delete(fileSystem.getPath("/remove.txt"));
            Files.writeString(fileSystem.getPath("/added.txt"), "added", StandardCharsets.UTF_8);
            Files.write(fileSystem.getPath("/padding.bin"), splitTestContent(TEST_SPLIT_SIZE * 2));
        }

        assertEquals(1, source.closeCount());
        TestVolumeOutput output = target.output();
        assertEquals(true, output.volumeCount() > 1);
        assertEquals(true, output.allVolumeSizesAtMost(TEST_SPLIT_SIZE));
        assertEquals(1, output.commitCount());
        assertEquals(0, output.rollbackCount());
        byte[] archive = output.archiveBytes();
        assertEquals(
                0x08074b50,
                ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN).getInt()
        );
        assertArrayEquals(preamble, Arrays.copyOfRange(archive, Integer.BYTES, Integer.BYTES + preamble.length));
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(output.volumeSource())) {
            assertEquals(preamble.length, fileSystem.preambleSize());
            assertPreambleContent(preamble, fileSystem);
            assertEquals("keep", Files.readString(fileSystem.getPath("/keep.txt"), StandardCharsets.UTF_8));
            assertEquals("new", Files.readString(fileSystem.getPath("/replace.txt"), StandardCharsets.UTF_8));
            assertEquals(false, Files.exists(fileSystem.getPath("/remove.txt")));
            assertEquals("added", Files.readString(fileSystem.getPath("/added.txt"), StandardCharsets.UTF_8));
        }
    }


    /// Verifies a failed split rewrite rolls back its output and releases the owned input source.
    @Test
    public void volumeUpdateRollsBackAfterOutputVolumeFailure() throws IOException {
        TestVolumeTarget originalTarget = new TestVolumeTarget();
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.create(originalTarget, TEST_SPLIT_SIZE)) {
            Files.writeString(fileSystem.getPath("/original.txt"), "original", StandardCharsets.UTF_8);
        }
        TrackingVolumeSource source = new TrackingVolumeSource(originalTarget.output().volumeSource());
        TestVolumeTarget failingTarget = new TestVolumeTarget(1);
        ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.update(source, failingTarget, TEST_SPLIT_SIZE);
        Files.write(fileSystem.getPath("/added.bin"), splitTestContent(TEST_SPLIT_SIZE * 2));

        IOException exception = assertThrows(IOException.class, fileSystem::close);
        assertEquals(true, exception.getMessage().contains("volume open failed"));
        assertEquals(1, source.closeCount());
        TestVolumeOutput output = failingTarget.output();
        assertEquals(0, output.commitCount());
        assertEquals(1, output.rollbackCount());
        assertEquals(1, output.closeCount());
        try (ZipArkivoFileSystem original =
                     ZipArkivoFileSystem.open(originalTarget.output().volumeSource())) {
            assertEquals(
                    "original",
                    Files.readString(original.getPath("/original.txt"), StandardCharsets.UTF_8)
            );
            assertEquals(false, Files.exists(original.getPath("/added.bin")));
        }
    }

    /// Verifies archive finalization failures roll back custom split output transactions.
    @Test
    public void splitVolumeTargetRollsBackAfterEntryFinalizationFailure() throws IOException {
        byte[] content = "invalid expected size".getBytes(StandardCharsets.UTF_8);
        TestVolumeTarget target = new TestVolumeTarget();
        ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(target, TEST_SPLIT_SIZE);
        var invalidEntry = writer.beginFile("invalid.txt");
        ZipArkivoEntryAttributeView view = invalidEntry.attributeView(ZipArkivoEntryAttributeView.class);
        assertNotNull(view);
        view.setUncompressedSizeAndCrc32(content.length + 1L, crc32(content));
        OutputStream outputStream = invalidEntry.openOutputStream();
        outputStream.write(content);

        IOException exception = assertThrows(IOException.class, writer::close);
        assertEquals(true, exception.getMessage().contains("configured size"));
        TestVolumeOutput output = target.output();
        assertEquals(0, output.commitCount());
        assertEquals(1, output.rollbackCount());
        assertEquals(1, output.closeCount());
    }

    /// Verifies that a custom volume target is rolled back when opening a later volume fails.
    @Test
    public void streamingWriterRollsBackCustomVolumeTargetAfterWriteFailure() throws IOException {
        byte[] content = splitTestContent(TEST_SPLIT_SIZE * 2);
        TestVolumeTarget target = new TestVolumeTarget(1);
        ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(target, TEST_SPLIT_SIZE);
        var contentEntry = writer.beginFile("content.bin");
        ZipArkivoEntryAttributeView view = contentEntry.attributeView(ZipArkivoEntryAttributeView.class);
        assertNotNull(view);
        view.setMethod(ZipMethod.STORED);
        OutputStream output = contentEntry.openOutputStream();

        assertThrows(IOException.class, () -> output.write(content));
        assertThrows(IOException.class, writer::close);

        TestVolumeOutput volumeOutput = target.output();
        assertEquals(0, volumeOutput.commitCount());
        assertEquals(1, volumeOutput.rollbackCount());
        assertEquals(1, volumeOutput.closeCount());
    }

    /// Verifies a failure shared by initial writing and any cleanup stage remains primary while cleanup continues.
    @Test
    public void splitOutputOpenPreservesSharedCleanupFailures() {
        for (CleanupFailureStage stage : CleanupFailureStage.values()) {
            IOException sharedFailure = new IOException("shared split output failure at " + stage);
            FailingVolumeTarget target = new FailingVolumeTarget(sharedFailure, sharedFailure, stage);

            IOException exception = assertThrows(
                    IOException.class,
                    () -> ZipArkivoStreamingWriter.open(target, TEST_SPLIT_SIZE),
                    stage.name()
            );

            assertSame(sharedFailure, exception, stage.name());
            assertEquals(0, exception.getSuppressed().length, stage.name());
            assertEquals(1, target.channelCloseCount(), stage.name());
            assertEquals(1, target.rollbackCount(), stage.name());
            assertEquals(1, target.closeCount(), stage.name());
        }
    }

    /// Verifies a distinct cleanup failure is suppressed while every split-output cleanup stage still runs.
    @Test
    public void splitOutputOpenSuppressesDistinctCleanupFailures() {
        for (CleanupFailureStage stage : CleanupFailureStage.values()) {
            IOException writeFailure = new IOException("split output write failure at " + stage);
            IOException cleanupFailure = new IOException("split output cleanup failure at " + stage);
            FailingVolumeTarget target = new FailingVolumeTarget(writeFailure, cleanupFailure, stage);

            IOException exception = assertThrows(
                    IOException.class,
                    () -> ZipArkivoStreamingWriter.open(target, TEST_SPLIT_SIZE),
                    stage.name()
            );

            assertSame(writeFailure, exception, stage.name());
            assertEquals(1, exception.getSuppressed().length, stage.name());
            assertSame(cleanupFailure, exception.getSuppressed()[0], stage.name());
            assertEquals(1, target.channelCloseCount(), stage.name());
            assertEquals(1, target.rollbackCount(), stage.name());
            assertEquals(1, target.closeCount(), stage.name());
        }
    }

    /// Verifies that split sizes outside the PKWARE bounds are rejected before opening a target.
    @Test
    public void rejectsSplitSizesOutsideSpecificationBounds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ZipArkivoStreamingWriter.open(
                        new TestVolumeTarget(),
                        ZipArkivoFileSystem.MINIMUM_SPLIT_SIZE - 1L
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ZipArkivoStreamingWriter.open(
                        new TestVolumeTarget(),
                        ZipArkivoFileSystem.MAXIMUM_SPLIT_SIZE + 1L
                )
        );
    }

    /// Verifies local and central directory header records start on disks where they fit completely.
    @Test
    public void splitWriterKeepsHeaderRecordsWithinVolumes() throws IOException {
        String firstName = "first.bin";
        String secondName = "second.bin";
        int firstHeaderSize = 30 + firstName.getBytes(StandardCharsets.UTF_8).length;
        int secondHeaderSize = 30 + secondName.getBytes(StandardCharsets.UTF_8).length;
        byte[] firstContent = splitTestContent(TEST_SPLIT_SIZE - Integer.BYTES - firstHeaderSize - 10);
        byte[] secondContent = splitTestContent(TEST_SPLIT_SIZE - secondHeaderSize - 20);
        TestVolumeTarget target = new TestVolumeTarget();

        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(target, TEST_SPLIT_SIZE)) {
            writeCompleteStoredEntry(writer, firstName, firstContent);
            writeCompleteStoredEntry(writer, secondName, secondContent);
        }

        TestVolumeOutput output = target.output();
        assertEquals(3, output.volumeCount());
        assertEquals(TEST_SPLIT_SIZE - 10, output.volumeBytes(0).length);
        assertEquals(TEST_SPLIT_SIZE - 20, output.volumeBytes(1).length);
        ByteBuffer firstVolume = ByteBuffer.wrap(output.volumeBytes(0)).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer secondVolume = ByteBuffer.wrap(output.volumeBytes(1)).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer finalVolume = ByteBuffer.wrap(output.volumeBytes(2)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x08074b50, firstVolume.getInt(0));
        assertEquals(0x04034b50, firstVolume.getInt(Integer.BYTES));
        assertEquals(0x04034b50, secondVolume.getInt(0));
        assertEquals(0x02014b50, finalVolume.getInt(0));
    }

    /// Verifies that replacement split output removes numbered volumes from the previous archive.
    @Test
    public void splitOutputReplacementRemovesStaleVolumes() throws IOException {
        Path archivePath = createTemporaryArchivePath("split-replace-");
        byte[] originalContent = splitTestContent(TEST_SPLIT_SIZE * 3);

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystemProvider.instance().newFileSystem(
                    archivePath,
                    Map.of(
                            "arkivo.openOptions",
                            Set.of(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE),
                            "arkivo.zip.splitSize",
                            (long) TEST_SPLIT_SIZE
                    )
            )) {
                Files.write(fileSystem.getPath("/original.bin"), originalContent);
            }
            assertEquals(true, splitVolumePaths(archivePath).size() > 2);

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystemProvider.instance().newFileSystem(
                    archivePath,
                    Map.of(
                            "arkivo.openOptions",
                            Set.of(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE),
                            "arkivo.zip.splitSize",
                            (long) TEST_SPLIT_SIZE
                    )
            )) {
                Files.writeString(fileSystem.getPath("/replacement.txt"), "replacement", StandardCharsets.UTF_8);
            }

            assertEquals(List.of(archivePath), splitVolumePaths(archivePath));
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                assertEquals(
                        "replacement",
                        Files.readString(fileSystem.getPath("/replacement.txt"), StandardCharsets.UTF_8)
                );
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that create-new split output rejects any existing volume before staging starts.
    @Test
    public void splitOutputCreateNewRejectsExistingVolumeAtOpen() throws IOException {
        Path archivePath = createTemporaryArchivePath("split-create-new-failure-");
        Path existingVolumePath = splitVolumePath(archivePath, 0);
        byte[] existingContent = "existing volume".getBytes(StandardCharsets.UTF_8);

        try {
            Files.write(existingVolumePath, existingContent);
            assertThrows(
                    FileAlreadyExistsException.class,
                    () -> ZipArkivoFileSystemProvider.instance().newFileSystem(
                            archivePath,
                            Map.of(
                                    "arkivo.openOptions",
                                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                                    "arkivo.zip.splitSize",
                                    (long) TEST_SPLIT_SIZE
                            )
                    )
            );
            assertArrayEquals(existingContent, Files.readAllBytes(existingVolumePath));
            assertEquals(false, Files.exists(archivePath));
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(archivePath.getParent())) {
                for (Path path : entries) {
                    assertEquals(false, path.getFileName().toString().startsWith(".arkivo-volumes-"));
                }
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Asserts that the preamble channel exposes exactly the expected preamble bytes.
    private static void assertPreambleContent(byte[] expected, ZipArkivoFileSystem fileSystem) throws IOException {
        try (SeekableByteChannel channel = fileSystem.openPreambleChannel()) {
            assertEquals(expected.length, channel.size());
            ByteBuffer buffer = ByteBuffer.allocate(expected.length);
            assertEquals(expected.length, channel.read(buffer));
            assertEquals(-1, channel.read(ByteBuffer.allocate(1)));
            assertArrayEquals(expected, buffer.array());
        }
    }

    /// Writes one stored streaming entry with exact size and CRC-32 metadata.
    private static void writeCompleteStoredEntry(
            ZipArkivoStreamingWriter writer,
            String entryName,
            byte[] content
    ) throws IOException {
        var entry = writer.beginFile(entryName);
        ZipArkivoEntryAttributeView view = entry.attributeView(ZipArkivoEntryAttributeView.class);
        assertNotNull(view);
        view.setMethod(ZipMethod.STORED);
        view.setUncompressedSizeAndCrc32(content.length, crc32(content));
        try (OutputStream output = entry.openOutputStream()) {
            output.write(content);
        }
    }

    /// Returns deterministic incompressible content for split ZIP tests.
    private static byte[] splitTestContent(int size) {
        byte[] content = new byte[size];
        new Random(0x41524b49564fL + size).nextBytes(content);
        return content;
    }

    /// Returns the unsigned CRC-32 value of the given bytes.
    private static long crc32(byte[] content) {
        CRC32 checksum = new CRC32();
        checksum.update(content);
        return checksum.getValue();
    }

    /// Repeatable volume source that tracks ownership closure.
    @NotNullByDefault
    private static final class TrackingVolumeSource implements ArkivoVolumeSource {
        /// The source that opens the actual volume channels.
        private final ArkivoVolumeSource delegate;

        /// The number of close attempts.
        private int closeCount;

        /// Whether this source has been closed.
        private boolean closed;

        /// Creates a tracking source.
        private TrackingVolumeSource(ArkivoVolumeSource delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        /// Opens one independently positioned volume channel.
        @Override
        public @Nullable SeekableByteChannel openVolume(long index) throws IOException {
            if (closed) {
                throw new IOException("volume source is closed");
            }
            return delegate.openVolume(index);
        }

        /// Closes the delegate and records source ownership release.
        @Override
        public void close() throws IOException {
            closeCount++;
            closed = true;
            delegate.close();
        }

        /// Returns the number of close attempts.
        private int closeCount() {
            return closeCount;
        }
    }

    /// Identifies the split-output cleanup operation that repeats the primary write failure.
    @NotNullByDefault
    private enum CleanupFailureStage {
        /// Fails closure of the first volume channel.
        CHANNEL_CLOSE,

        /// Fails rollback of the volume transaction.
        ROLLBACK,

        /// Fails closure of the volume transaction.
        OUTPUT_CLOSE
    }

    /// Supplies a split-output transaction whose initial write and one cleanup stage fail deterministically.
    @NotNullByDefault
    private static final class FailingVolumeTarget implements ArkivoVolumeTarget, ArkivoVolumeOutput {
        /// Failure reported by the selected cleanup operation.
        private final IOException cleanupFailure;

        /// Cleanup operation that repeats the write failure.
        private final CleanupFailureStage stage;

        /// First volume channel used by the split writer.
        private final FailingWritableChannel channel;

        /// Number of rollback calls.
        private int rollbackCount;

        /// Number of transaction close calls.
        private int closeCount;

        /// Creates a target with one selected failing cleanup stage.
        ///
        /// @param writeFailure the stable failure reported by the initial write
        /// @param cleanupFailure the stable failure reported by the selected cleanup stage
        /// @param stage the cleanup stage that reports `cleanupFailure`
        private FailingVolumeTarget(
                IOException writeFailure,
                IOException cleanupFailure,
                CleanupFailureStage stage
        ) {
            this.cleanupFailure = Objects.requireNonNull(cleanupFailure, "cleanupFailure");
            this.stage = Objects.requireNonNull(stage, "stage");
            this.channel = new FailingWritableChannel(
                    Objects.requireNonNull(writeFailure, "writeFailure"),
                    stage == CleanupFailureStage.CHANNEL_CLOSE ? cleanupFailure : null
            );
        }

        /// Opens this target's single test transaction.
        @Override
        public ArkivoVolumeOutput openOutput() {
            return this;
        }

        /// Opens the first failing volume channel.
        @Override
        public WritableByteChannel openVolume(long index) {
            if (index != 0L) {
                throw new IllegalArgumentException("Only volume zero is available");
            }
            return channel;
        }

        /// Rejects unexpected publication after the initial write failure.
        @Override
        public void commit(long finalVolumeIndex) {
            throw new AssertionError("Failed split output must not be committed");
        }

        /// Records rollback and optionally repeats the primary failure.
        @Override
        public void rollback() throws IOException {
            rollbackCount++;
            if (stage == CleanupFailureStage.ROLLBACK) {
                throw cleanupFailure;
            }
        }

        /// Records transaction closure and optionally repeats the primary failure.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (stage == CleanupFailureStage.OUTPUT_CLOSE) {
                throw cleanupFailure;
            }
        }

        /// Returns the number of volume channel close calls.
        private int channelCloseCount() {
            return channel.closeCount();
        }

        /// Returns the number of rollback calls.
        private int rollbackCount() {
            return rollbackCount;
        }

        /// Returns the number of transaction close calls.
        private int closeCount() {
            return closeCount;
        }
    }

    /// Fails all writes and optionally reports a configured close failure.
    @NotNullByDefault
    private static final class FailingWritableChannel implements WritableByteChannel {
        /// Stable write failure.
        private final IOException writeFailure;

        /// Stable close failure, or `null` when close succeeds.
        private final @Nullable IOException closeFailure;

        /// Number of close calls.
        private int closeCount;

        /// Whether this channel remains open.
        private boolean open = true;

        /// Creates a failing writable channel.
        ///
        /// @param writeFailure the stable write failure
        /// @param closeFailure the stable close failure, or `null` when close succeeds
        private FailingWritableChannel(IOException writeFailure, @Nullable IOException closeFailure) {
            this.writeFailure = Objects.requireNonNull(writeFailure, "writeFailure");
            this.closeFailure = closeFailure;
        }

        /// Reports the stable write failure.
        @Override
        public int write(ByteBuffer source) throws IOException {
            Objects.requireNonNull(source, "source");
            throw writeFailure;
        }

        /// Returns whether this channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Records closure and optionally repeats the write failure.
        @Override
        public void close() throws IOException {
            closeCount++;
            open = false;
            if (closeFailure != null) {
                throw closeFailure;
            }
        }

        /// Returns the number of close calls.
        private int closeCount() {
            return closeCount;
        }
    }

    /// Test target that creates one in-memory multi-volume output transaction.
    @NotNullByDefault
    private static final class TestVolumeTarget implements ArkivoVolumeTarget {
        /// The sentinel used when no volume open should fail.
        private static final int NO_FAILURE_VOLUME_INDEX = -1;

        /// The volume index whose open operation should fail.
        private final int failureVolumeIndex;

        /// The output transaction opened from this target, or `null` before use.
        private @Nullable TestVolumeOutput output;

        /// Creates a target whose volume opens succeed.
        private TestVolumeTarget() {
            this(NO_FAILURE_VOLUME_INDEX);
        }

        /// Creates a target that fails while opening the given volume index.
        private TestVolumeTarget(int failureVolumeIndex) {
            if (failureVolumeIndex < NO_FAILURE_VOLUME_INDEX) {
                throw new IllegalArgumentException("failureVolumeIndex is out of range");
            }
            this.failureVolumeIndex = failureVolumeIndex;
        }

        /// Opens the single output transaction supported by this test target.
        @Override
        public ArkivoVolumeOutput openOutput() throws IOException {
            if (output != null) {
                throw new IOException("volume target output is already open");
            }
            TestVolumeOutput openedOutput = new TestVolumeOutput(failureVolumeIndex);
            output = openedOutput;
            return openedOutput;
        }

        /// Returns the output transaction opened from this target.
        private TestVolumeOutput output() {
            return Objects.requireNonNull(output, "output");
        }
    }

    /// In-memory multi-volume output that records commit, rollback, and close operations.
    @NotNullByDefault
    private static final class TestVolumeOutput implements ArkivoVolumeOutput {
        /// The sentinel used before a final volume index has been committed.
        private static final long NO_FINAL_VOLUME_INDEX = -1L;

        /// The volume index whose open operation should fail.
        private final int failureVolumeIndex;

        /// The byte streams that receive individual volume content.
        private final ArrayList<ByteArrayOutputStream> volumes = new ArrayList<>();

        /// The final committed volume index, or `NO_FINAL_VOLUME_INDEX` before commit.
        private long finalVolumeIndex = NO_FINAL_VOLUME_INDEX;

        /// The number of commit calls.
        private int commitCount;

        /// The number of rollback calls.
        private int rollbackCount;

        /// The number of close calls.
        private int closeCount;

        /// Whether this output has been committed or rolled back.
        private boolean finished;

        /// Creates an in-memory volume output with the requested open failure index.
        private TestVolumeOutput(int failureVolumeIndex) {
            this.failureVolumeIndex = failureVolumeIndex;
        }

        /// Opens the next in-memory volume channel.
        @Override
        public WritableByteChannel openVolume(long index) throws IOException {
            ensureOpen();
            if (index < 0 || index > Integer.MAX_VALUE || index != volumes.size()) {
                throw new IllegalArgumentException("Volume indexes must be opened once in ascending order");
            }
            if (index == failureVolumeIndex) {
                throw new IOException("volume open failed");
            }
            ByteArrayOutputStream volume = new ByteArrayOutputStream();
            volumes.add(volume);
            return Channels.newChannel(volume);
        }

        /// Commits all in-memory volumes.
        @Override
        public void commit(long finalVolumeIndex) throws IOException {
            ensureOpen();
            if (finalVolumeIndex != volumes.size() - 1L) {
                throw new IllegalArgumentException("finalVolumeIndex must identify the last opened volume");
            }
            this.finalVolumeIndex = finalVolumeIndex;
            commitCount++;
            finished = true;
        }

        /// Rolls back this in-memory output.
        @Override
        public void rollback() {
            if (finished) {
                return;
            }
            rollbackCount++;
            finished = true;
        }

        /// Closes this output and rolls it back when unfinished.
        @Override
        public void close() {
            closeCount++;
            rollback();
        }

        /// Returns a readable source over the committed volume bytes.
        private ArkivoVolumeSource volumeSource() {
            if (commitCount == 0) {
                throw new IllegalStateException("volume output has not been committed");
            }
            return index -> {
                if (index < 0 || index >= volumes.size()) {
                    return null;
                }
                return new ReadOnlyByteArrayChannel(volumes.get((int) index).toByteArray());
            };
        }

        /// Returns a copy of one physical volume's bytes.
        private byte[] volumeBytes(int index) {
            return volumes.get(index).toByteArray();
        }

        /// Returns the concatenated logical archive bytes.
        private byte[] archiveBytes() {
            ByteArrayOutputStream archive = new ByteArrayOutputStream();
            for (ByteArrayOutputStream volume : volumes) {
                archive.writeBytes(volume.toByteArray());
            }
            return archive.toByteArray();
        }

        /// Returns whether every physical volume respects the requested maximum size.
        private boolean allVolumeSizesAtMost(long maximumSize) {
            if (maximumSize <= 0L) {
                throw new IllegalArgumentException("maximumSize must be positive");
            }
            for (ByteArrayOutputStream volume : volumes) {
                if (volume.size() > maximumSize) {
                    return false;
                }
            }
            return true;
        }

        /// Returns the number of opened volumes.
        private int volumeCount() {
            return volumes.size();
        }

        /// Returns the final committed volume index.
        private long finalVolumeIndex() {
            return finalVolumeIndex;
        }

        /// Returns the number of commit calls.
        private int commitCount() {
            return commitCount;
        }

        /// Returns the number of rollback calls.
        private int rollbackCount() {
            return rollbackCount;
        }

        /// Returns the number of close calls.
        private int closeCount() {
            return closeCount;
        }

        /// Requires this output transaction to remain unfinished.
        private void ensureOpen() throws IOException {
            if (finished) {
                throw new IOException("volume output is closed");
            }
        }
    }

}
