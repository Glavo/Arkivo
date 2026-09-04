// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArchiveCreateOptions;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArchiveUpdateOptions;
import org.glavo.arkivo.archive.ArkivoPathVolumeTarget;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.ArkivoVolumePathLayout;
import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the public ZIP format descriptor.
@NotNullByDefault
public final class ZipArkivoFormatTest {
    /// Verifies descriptor metadata, capabilities, and every recognized leading record signature.
    @Test
    public void describesAndDetectsZipArchives() {
        ZipArkivoFormat format = ZipArkivoFormat.instance();

        assertSame(format, ZipArkivoFormat.instance());
        assertEquals(ZipArkivoFormat.NAME, format.name());
        assertEquals(List.of("zip", "jar"), format.fileExtensions());
        assertEquals(4, format.probeSize());
        assertTrue(format instanceof org.glavo.arkivo.archive.ArkivoFormat.PathVolume);
        assertTrue(format instanceof org.glavo.arkivo.archive.ArkivoFormat.VolumeFileSystem.Writable);
        assertTrue(format instanceof org.glavo.arkivo.archive.ArkivoFormat.VolumeStreamingReadable);
        assertTrue(format instanceof org.glavo.arkivo.archive.ArkivoFormat.VolumeStreamingWritable);

        for (byte[] signature : List.of(
                new byte[]{'P', 'K', 3, 4},
                new byte[]{'P', 'K', 5, 6},
                new byte[]{'P', 'K', 7, 8}
        )) {
            assertTrue(format.matches(ByteBuffer.wrap(signature)));
        }

        ByteBuffer prefix = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        prefix.position(3).put(new byte[]{'P', 'K', 3, 4}).limit(7);
        prefix.position(2).mark().position(3);
        assertTrue(format.matches(prefix));
        assertEquals(3, prefix.position());
        assertEquals(7, prefix.limit());
        assertSame(ByteOrder.LITTLE_ENDIAN, prefix.order());
        prefix.reset();
        assertEquals(2, prefix.position());

        assertFalse(format.matches(ByteBuffer.wrap(new byte[]{'P', 'K', 3})));
        assertFalse(format.matches(ByteBuffer.wrap(new byte[]{'Q', 'K', 3, 4})));
        assertFalse(format.matches(ByteBuffer.wrap(new byte[]{'P', 'J', 3, 4})));
        assertFalse(format.matches(ByteBuffer.wrap(new byte[]{'P', 'K', 4, 3})));
        assertFalse(format.matches(ByteBuffer.wrap(new byte[]{'P', 'K', 3, 6})));
        assertFalse(format.matches(ByteBuffer.wrap(new byte[]{'P', 'K', 5, 8})));
        assertFalse(format.matches(ByteBuffer.wrap(new byte[]{'P', 'K', 7, 9})));
    }

    /// Verifies every generic streaming factory can produce or consume a valid empty ZIP archive.
    ///
    /// @param directory the temporary directory used for path and volume reader factories
    @Test
    public void roundTripsEmptyArchiveThroughStreamingFactories(@TempDir Path directory) throws IOException {
        ZipArkivoFormat format = ZipArkivoFormat.instance();

        ByteArrayOutputStream streamDefault = new ByteArrayOutputStream();
        try (var writer = format.openStreamingWriter(streamDefault)) {
            // Closing emits an empty end-of-central-directory record.
        }
        assertEmptyArchiveThroughReaders(format, streamDefault.toByteArray(), directory);

        ByteArrayOutputStream streamConfigured = new ByteArrayOutputStream();
        try (var writer = format.openStreamingWriter(streamConfigured, ArchiveCreateOptions.DEFAULT)) {
            // Closing emits an empty end-of-central-directory record.
        }
        assertEmptyArchiveThroughReaders(format, streamConfigured.toByteArray(), directory);

        ByteArrayOutputStream channelDefault = new ByteArrayOutputStream();
        try (var writer = format.openStreamingWriter(Channels.newChannel(channelDefault))) {
            // Closing emits an empty end-of-central-directory record.
        }
        assertEmptyArchiveThroughReaders(format, channelDefault.toByteArray(), directory);

        ByteArrayOutputStream channelConfigured = new ByteArrayOutputStream();
        try (var writer = format.openStreamingWriter(
                Channels.newChannel(channelConfigured),
                ArchiveCreateOptions.DEFAULT
        )) {
            // Closing emits an empty end-of-central-directory record.
        }
        assertEmptyArchiveThroughReaders(format, channelConfigured.toByteArray(), directory);
    }

    /// Verifies generic path, direct-channel, repeatable-source, and volume-source file-system factories.
    ///
    /// @param directory the temporary directory used for the path-backed archive
    @Test
    public void opensFileSystemsThroughGenericFactories(@TempDir Path directory) throws IOException {
        ZipArkivoFormat format = ZipArkivoFormat.instance();
        Path archive = directory.resolve("empty.zip");

        try (var fileSystem = format.create(archive, ArchiveCreateOptions.DEFAULT)) {
            assertTrue(fileSystem.isOpen());
        }
        try (var fileSystem = format.open(archive)) {
            assertTrue(fileSystem.isOpen());
        }
        try (var fileSystem = format.open(archive, ArchiveReadOptions.DEFAULT)) {
            assertTrue(fileSystem.isOpen());
        }
        try (var fileSystem = format.update(archive, ArchiveUpdateOptions.DEFAULT)) {
            assertTrue(fileSystem.isOpen());
        }

        SeekableByteChannel defaultChannel = Files.newByteChannel(archive, StandardOpenOption.READ);
        try (var fileSystem = format.open(defaultChannel)) {
            assertTrue(fileSystem.isOpen());
        }
        assertFalse(defaultChannel.isOpen());

        SeekableByteChannel configuredChannel = Files.newByteChannel(archive, StandardOpenOption.READ);
        try (var fileSystem = format.open(configuredChannel, ArchiveReadOptions.DEFAULT)) {
            assertTrue(fileSystem.isOpen());
        }
        assertFalse(configuredChannel.isOpen());

        SeekableByteChannel defaultRepeatableBacking = Files.newByteChannel(archive, StandardOpenOption.READ);
        ArkivoSeekableChannelSource defaultRepeatableSource = ArkivoSeekableChannelSource.of(defaultRepeatableBacking);
        try (var fileSystem = format.open(defaultRepeatableSource)) {
            assertTrue(fileSystem.isOpen());
        }
        assertFalse(defaultRepeatableBacking.isOpen());

        SeekableByteChannel repeatableBacking = Files.newByteChannel(archive, StandardOpenOption.READ);
        ArkivoSeekableChannelSource repeatableSource = ArkivoSeekableChannelSource.of(repeatableBacking);
        try (var fileSystem = format.open(repeatableSource, ArchiveReadOptions.DEFAULT)) {
            assertTrue(fileSystem.isOpen());
        }
        assertFalse(repeatableBacking.isOpen());

        try (var fileSystem = format.open(ArkivoVolumeSource.of(List.of(archive)))) {
            assertTrue(fileSystem.isOpen());
        }
        try (var fileSystem = format.open(
                ArkivoVolumeSource.of(List.of(archive)),
                ArchiveReadOptions.DEFAULT
        )) {
            assertTrue(fileSystem.isOpen());
        }
    }

    /// Verifies generic split writer, creation, and complete-rewrite update factories publish readable output.
    ///
    /// @param directory the temporary directory used for transactional volume publication
    @Test
    public void publishesThroughGenericSplitFactories(@TempDir Path directory) throws IOException {
        ZipArkivoFormat format = ZipArkivoFormat.instance();
        Path sourceArchive = directory.resolve("source.zip");
        try (var fileSystem = format.create(sourceArchive, ArchiveCreateOptions.DEFAULT)) {
            // Closing publishes the empty source archive used by update operations.
        }

        TestVolumeLayout writerDefault = new TestVolumeLayout(directory, "writer-default");
        try (var writer = format.openStreamingWriter(
                new ArkivoPathVolumeTarget(writerDefault),
                ZipArkivoFileSystem.MINIMUM_SPLIT_SIZE
        )) {
            // Closing commits one empty archive volume.
        }
        assertReadablePublishedArchive(format, writerDefault);

        TestVolumeLayout writerConfigured = new TestVolumeLayout(directory, "writer-options");
        try (var writer = format.openStreamingWriter(
                new ArkivoPathVolumeTarget(writerConfigured),
                ZipArkivoFileSystem.MINIMUM_SPLIT_SIZE,
                ArchiveCreateOptions.DEFAULT
        )) {
            // Closing commits one empty archive volume.
        }
        assertReadablePublishedArchive(format, writerConfigured);

        TestVolumeLayout createDefault = new TestVolumeLayout(directory, "create-default");
        try (var fileSystem = format.create(
                new ArkivoPathVolumeTarget(createDefault),
                ZipArkivoFileSystem.MINIMUM_SPLIT_SIZE
        )) {
            assertTrue(fileSystem.isOpen());
        }
        assertReadablePublishedArchive(format, createDefault);

        TestVolumeLayout createConfigured = new TestVolumeLayout(directory, "create-options");
        try (var fileSystem = format.create(
                new ArkivoPathVolumeTarget(createConfigured),
                ZipArkivoFileSystem.MINIMUM_SPLIT_SIZE,
                ArchiveCreateOptions.DEFAULT
        )) {
            assertTrue(fileSystem.isOpen());
        }
        assertReadablePublishedArchive(format, createConfigured);

        TestVolumeLayout updateDefault = new TestVolumeLayout(directory, "update-default");
        try (var fileSystem = format.update(
                ArkivoVolumeSource.of(List.of(sourceArchive)),
                new ArkivoPathVolumeTarget(updateDefault),
                ZipArkivoFileSystem.MINIMUM_SPLIT_SIZE
        )) {
            assertTrue(fileSystem.isOpen());
            Files.write(fileSystem.getPath("/updated.txt"), new byte[]{1});
        }
        assertReadablePublishedArchive(format, updateDefault);

        TestVolumeLayout updateConfigured = new TestVolumeLayout(directory, "update-options");
        try (var fileSystem = format.update(
                ArkivoVolumeSource.of(List.of(sourceArchive)),
                new ArkivoPathVolumeTarget(updateConfigured),
                ZipArkivoFileSystem.MINIMUM_SPLIT_SIZE,
                ArchiveUpdateOptions.DEFAULT
        )) {
            assertTrue(fileSystem.isOpen());
            Files.write(fileSystem.getPath("/updated.txt"), new byte[]{2});
        }
        assertReadablePublishedArchive(format, updateConfigured);
    }

    /// Verifies null prefixes fail at the public probing boundary.
    @Test
    @SuppressWarnings("DataFlowIssue")
    public void rejectsNullPrefix() {
        assertThrows(NullPointerException.class, () -> ZipArkivoFormat.instance().matches((ByteBuffer) null));
    }

    /// Verifies all generic streaming reader overloads observe an empty archive.
    private static void assertEmptyArchiveThroughReaders(
            ZipArkivoFormat format,
            byte[] archive,
            Path directory
    ) throws IOException {
        assertTrue(format.matches(ByteBuffer.wrap(archive)));
        try (var reader = format.openStreamingReader(new ByteArrayInputStream(archive))) {
            assertFalse(reader.next());
        }
        try (var reader = format.openStreamingReader(
                new ByteArrayInputStream(archive),
                ArchiveReadOptions.DEFAULT
        )) {
            assertFalse(reader.next());
        }
        try (var reader = format.openStreamingReader(Channels.newChannel(new ByteArrayInputStream(archive)))) {
            assertFalse(reader.next());
        }
        try (var reader = format.openStreamingReader(
                Channels.newChannel(new ByteArrayInputStream(archive)),
                ArchiveReadOptions.DEFAULT
        )) {
            assertFalse(reader.next());
        }

        Path path = Files.write(directory.resolve("streaming-empty.zip"), archive);
        try (var reader = format.openStreamingReader(path)) {
            assertFalse(reader.next());
        }
        try (var reader = format.openStreamingReader(path, ArchiveReadOptions.DEFAULT)) {
            assertFalse(reader.next());
        }
        try (var reader = format.openStreamingReader(ArkivoVolumeSource.of(List.of(path)))) {
            assertFalse(reader.next());
        }
        try (var reader = format.openStreamingReader(
                ArkivoVolumeSource.of(List.of(path)),
                ArchiveReadOptions.DEFAULT
        )) {
            assertFalse(reader.next());
        }
    }

    /// Verifies one transaction published a single readable ZIP volume.
    private static void assertReadablePublishedArchive(
            ZipArkivoFormat format,
            TestVolumeLayout layout
    ) throws IOException {
        Path path = layout.volumePath(0L, 0L);
        assertTrue(Files.exists(path), layout.name());
        try (var fileSystem = format.open(path, ArchiveReadOptions.DEFAULT)) {
            assertTrue(fileSystem.isOpen());
        }
    }

    /// Maps one test transaction to unique final-index-qualified paths.
    ///
    /// @param directory the publication and staging directory
    /// @param name the unique transaction name
    @NotNullByDefault
    private record TestVolumeLayout(Path directory, String name) implements ArkivoVolumePathLayout {
        /// Returns the transaction staging directory.
        @Override
        public Path outputDirectory() {
            return directory;
        }

        /// Returns the path for one physical output volume.
        @Override
        public Path volumePath(long index, long finalVolumeIndex) {
            if (index < 0L || index > finalVolumeIndex) {
                throw new IllegalArgumentException("Invalid test volume index: " + index);
            }
            return directory.resolve(name + "-" + index + "-of-" + finalVolumeIndex + ".zip");
        }

        /// Returns no previous output because each test layout is used for exactly one transaction.
        @Override
        public @Unmodifiable List<Path> existingVolumePaths() {
            return List.of();
        }
    }
}
