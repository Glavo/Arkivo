// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the public 7z format descriptor.
@NotNullByDefault
public final class SevenZipArkivoFormatTest {
    /// Verifies descriptor metadata, capabilities, and exact non-mutating signature detection.
    @Test
    public void describesAndDetectsSevenZipArchives() {
        SevenZipArkivoFormat format = SevenZipArkivoFormat.instance();
        byte[] signature = new byte[]{'7', 'z', (byte) 0xbc, (byte) 0xaf, 0x27, 0x1c};

        assertSame(format, SevenZipArkivoFormat.instance());
        assertEquals(SevenZipArkivoFormat.NAME, format.name());
        assertEquals(List.of("sevenzip"), format.aliases());
        assertEquals(List.of("7z"), format.fileExtensions());
        assertEquals(signature.length, format.probeSize());
        assertTrue(format instanceof org.glavo.arkivo.archive.ArkivoFormat.PathVolume);
        assertTrue(format instanceof org.glavo.arkivo.archive.ArkivoFormat.VolumeFileSystem.Writable);
        assertTrue(format instanceof org.glavo.arkivo.archive.ArkivoFormat.VolumeStreamingWriter);

        ByteBuffer prefix = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        prefix.position(3).put(signature).limit(3 + signature.length);
        prefix.position(2).mark().position(3);
        int position = prefix.position();
        int limit = prefix.limit();
        ByteOrder order = prefix.order();

        assertTrue(format.matches(prefix));
        assertEquals(position, prefix.position());
        assertEquals(limit, prefix.limit());
        assertSame(order, prefix.order());
        prefix.reset();
        assertEquals(2, prefix.position());

        assertFalse(format.matches(ByteBuffer.wrap(new byte[signature.length - 1])));
        for (int index = 0; index < signature.length; index++) {
            byte[] wrongSignature = signature.clone();
            wrongSignature[index] ^= 1;
            assertFalse(format.matches(ByteBuffer.wrap(wrongSignature).asReadOnlyBuffer()), "index " + index);
        }
    }

    /// Verifies every stream and channel writer factory produces a readable empty 7z archive.
    ///
    /// @param directory the temporary directory used to reopen generated archives
    @Test
    public void writesEmptyArchivesThroughGenericFactories(@TempDir Path directory) throws IOException {
        SevenZipArkivoFormat format = SevenZipArkivoFormat.instance();

        ByteArrayOutputStream streamDefault = new ByteArrayOutputStream();
        try (var writer = format.openStreamingWriter(streamDefault)) {
            // Closing finishes the empty 7z header.
        }
        assertReadableEmptyArchive(format, streamDefault.toByteArray(), directory.resolve("stream-default.7z"));

        ByteArrayOutputStream streamConfigured = new ByteArrayOutputStream();
        try (var writer = format.openStreamingWriter(streamConfigured, ArchiveCreateOptions.DEFAULT)) {
            // Closing finishes the empty 7z header.
        }
        assertReadableEmptyArchive(format, streamConfigured.toByteArray(), directory.resolve("stream-options.7z"));

        ByteArrayOutputStream channelDefault = new ByteArrayOutputStream();
        try (var writer = format.openStreamingWriter(Channels.newChannel(channelDefault))) {
            // Closing finishes the empty 7z header.
        }
        assertReadableEmptyArchive(format, channelDefault.toByteArray(), directory.resolve("channel-default.7z"));

        ByteArrayOutputStream channelConfigured = new ByteArrayOutputStream();
        try (var writer = format.openStreamingWriter(
                Channels.newChannel(channelConfigured),
                ArchiveCreateOptions.DEFAULT
        )) {
            // Closing finishes the empty 7z header.
        }
        assertReadableEmptyArchive(format, channelConfigured.toByteArray(), directory.resolve("channel-options.7z"));
    }

    /// Verifies generic path, direct-channel, repeatable-source, and volume-source file-system factories.
    ///
    /// @param directory the temporary directory used for the path-backed archive
    @Test
    public void opensFileSystemsThroughGenericFactories(@TempDir Path directory) throws IOException {
        SevenZipArkivoFormat format = SevenZipArkivoFormat.instance();
        Path archive = directory.resolve("empty.7z");

        try (var fileSystem = format.create(archive, ArchiveCreateOptions.DEFAULT)) {
            assertTrue(fileSystem.isOpen());
        }
        assertNull(format.discoverVolumePaths(archive));
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
        SevenZipArkivoFormat format = SevenZipArkivoFormat.instance();
        Path sourceArchive = directory.resolve("source.7z");
        try (var fileSystem = format.create(sourceArchive, ArchiveCreateOptions.DEFAULT)) {
            // Closing publishes the empty source archive used by update operations.
        }

        TestVolumeLayout writerDefault = new TestVolumeLayout(directory, "writer-default");
        try (var writer = format.openStreamingWriter(new ArkivoPathVolumeTarget(writerDefault), 1024L)) {
            // Closing commits one empty archive volume.
        }
        assertReadablePublishedArchive(format, writerDefault);

        TestVolumeLayout writerConfigured = new TestVolumeLayout(directory, "writer-options");
        try (var writer = format.openStreamingWriter(
                new ArkivoPathVolumeTarget(writerConfigured),
                1024L,
                ArchiveCreateOptions.DEFAULT
        )) {
            // Closing commits one empty archive volume.
        }
        assertReadablePublishedArchive(format, writerConfigured);

        TestVolumeLayout createDefault = new TestVolumeLayout(directory, "create-default");
        try (var fileSystem = format.create(new ArkivoPathVolumeTarget(createDefault), 1024L)) {
            assertTrue(fileSystem.isOpen());
        }
        assertReadablePublishedArchive(format, createDefault);

        TestVolumeLayout createConfigured = new TestVolumeLayout(directory, "create-options");
        try (var fileSystem = format.create(
                new ArkivoPathVolumeTarget(createConfigured),
                1024L,
                ArchiveCreateOptions.DEFAULT
        )) {
            assertTrue(fileSystem.isOpen());
        }
        assertReadablePublishedArchive(format, createConfigured);

        TestVolumeLayout updateDefault = new TestVolumeLayout(directory, "update-default");
        try (var fileSystem = format.update(
                ArkivoVolumeSource.of(List.of(sourceArchive)),
                new ArkivoPathVolumeTarget(updateDefault),
                1024L
        )) {
            assertTrue(fileSystem.isOpen());
            Files.write(fileSystem.getPath("/updated.txt"), new byte[]{1});
        }
        assertReadablePublishedArchive(format, updateDefault);

        TestVolumeLayout updateConfigured = new TestVolumeLayout(directory, "update-options");
        try (var fileSystem = format.update(
                ArkivoVolumeSource.of(List.of(sourceArchive)),
                new ArkivoPathVolumeTarget(updateConfigured),
                1024L,
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
        assertThrows(NullPointerException.class, () -> SevenZipArkivoFormat.instance().matches((ByteBuffer) null));
    }

    /// Writes and reopens one generated empty archive through the generic path factory.
    private static void assertReadableEmptyArchive(
            SevenZipArkivoFormat format,
            byte[] archive,
            Path path
    ) throws IOException {
        assertTrue(format.matches(ByteBuffer.wrap(archive)));
        Files.write(path, archive);
        try (var fileSystem = format.open(path, ArchiveReadOptions.DEFAULT)) {
            assertTrue(fileSystem.isOpen());
        }
    }

    /// Verifies one transaction published a single readable 7z volume.
    private static void assertReadablePublishedArchive(
            SevenZipArkivoFormat format,
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
            return directory.resolve(name + "-" + index + "-of-" + finalVolumeIndex + ".7z");
        }

        /// Returns no previous output because each test layout is used for exactly one transaction.
        @Override
        public @Unmodifiable List<Path> existingVolumePaths() {
            return List.of();
        }
    }
}
