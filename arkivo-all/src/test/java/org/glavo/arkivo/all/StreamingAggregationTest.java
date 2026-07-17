// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoFormat;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFileSystemFormat;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.archive.ArkivoPathVolumeFormat;
import org.glavo.arkivo.archive.ArkivoStreamingReaderFormat;
import org.glavo.arkivo.archive.ArkivoStreamingReader;
import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.glavo.arkivo.archive.ArkivoStreamingWriterFormat;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.ArkivoVolumeFileSystemFormat;
import org.glavo.arkivo.archive.ArkivoVolumeOutput;
import org.glavo.arkivo.archive.ArkivoVolumeStreamingReaderFormat;
import org.glavo.arkivo.archive.ArkivoVolumeStreamingWriterFormat;
import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.glavo.arkivo.archive.ArkivoVolumeTarget;
import org.glavo.arkivo.archive.ar.ArArkivoStreamingWriter;
import org.glavo.arkivo.archive.ar.ArArkivoFileSystem;
import org.glavo.arkivo.archive.rar.RarArkivoStreamingReader;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoStreamingWriter;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoFileSystem;
import org.glavo.arkivo.archive.tar.TarArkivoFileSystem;
import org.glavo.arkivo.archive.tar.TarArchiveOptions;
import org.glavo.arkivo.archive.tar.TarArkivoStreamingReader;
import org.glavo.arkivo.archive.tar.TarArkivoStreamingWriter;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.archive.zip.ZipArkivoStreamingWriter;
import org.glavo.arkivo.codec.CompressionFormats;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies unified forward-only archive detection and reader dispatch through the aggregate module.
@NotNullByDefault
final class StreamingAggregationTest {
    /// The path stored in generated streaming archives.
    private static final String ENTRY_PATH = "value.txt";

    /// The content stored in generated streaming archives.
    private static final byte[] CONTENT = "unified streaming archive".getBytes(StandardCharsets.UTF_8);

    /// Verifies the installed formats that advertise forward-only readers.
    @Test
    void discoversStreamingFormats() {
        Set<String> formatNames = ArkivoFormats.installed()
                .stream()
                .filter(ArkivoStreamingReaderFormat.class::isInstance)
                .map(ArkivoFormat::name)
                .collect(Collectors.toUnmodifiableSet());
        assertEquals(Set.of("ar", "rar", "tar", "zip"), formatNames);
    }

    /// Verifies named reader lookup opens every readable streaming format without signature dispatch.
    @Test
    void readsEveryStreamingFormatByName() throws IOException {
        for (Map.Entry<String, byte[]> archive : Map.of(
                "ar", arArchive(),
                "tar", tarArchive(),
                "zip", zipArchive()
        ).entrySet()) {
            ReadableByteChannel source = Channels.newChannel(
                    new ByteArrayInputStream(archive.getValue())
            );
            try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(
                    archive.getKey(),
                    source
            )) {
                assertEntry(reader);
            }
            assertFalse(source.isOpen(), archive.getKey());
        }

        byte[] rarSignature = {'R', 'a', 'r', '!', 0x1a, 0x07, 0x01, 0x00};
        TrackingInputStream rarSource = new TrackingInputStream(rarSignature);
        try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader("rar", rarSource)) {
            assertInstanceOf(RarArkivoStreamingReader.class, reader);
            org.junit.jupiter.api.Assertions.assertNull(reader.nextEntry());
        }
        assertEquals(1, rarSource.closeCount());
    }

    /// Verifies named reader lookup rejects unknown and write-only formats and closes their sources.
    @Test
    void rejectsUnavailableNamedStreamingReadersAndClosesSources() {
        ReadableByteChannel unknownSource = Channels.newChannel(
                new ByteArrayInputStream(new byte[]{1, 2, 3})
        );
        IOException unknownFailure = assertThrows(
                IOException.class,
                () -> ArkivoFormats.openStreamingReader("missing", unknownSource)
        );
        assertEquals("Unknown archive format: missing", unknownFailure.getMessage());
        assertFalse(unknownSource.isOpen());

        ReadableByteChannel writeOnlySource = Channels.newChannel(
                new ByteArrayInputStream(new byte[0])
        );
        UnsupportedOperationException writeOnlyFailure = assertThrows(
                UnsupportedOperationException.class,
                () -> ArkivoFormats.openStreamingReader("7z", writeOnlySource)
        );
        assertTrue(writeOnlyFailure.getMessage().contains("7z"));
        assertFalse(writeOnlySource.isOpen());

        TrackingInputStream unknownStream = new TrackingInputStream(new byte[]{1, 2, 3});
        IOException unknownStreamFailure = assertThrows(
                IOException.class,
                () -> ArkivoFormats.openStreamingReader("missing", unknownStream)
        );
        assertEquals("Unknown archive format: missing", unknownStreamFailure.getMessage());
        assertEquals(1, unknownStream.closeCount());

        TrackingInputStream writeOnlyStream = new TrackingInputStream(new byte[0]);
        UnsupportedOperationException writeOnlyStreamFailure = assertThrows(
                UnsupportedOperationException.class,
                () -> ArkivoFormats.openStreamingReader("7z", writeOnlyStream)
        );
        assertTrue(writeOnlyStreamFailure.getMessage().contains("7z"));
        assertEquals(1, writeOnlyStream.closeCount());

        TrackingInputStream invalidArgumentStream = new TrackingInputStream(new byte[0]);
        assertThrows(
                NullPointerException.class,
                () -> ArkivoFormats.openStreamingReader((String) null, invalidArgumentStream)
        );
        assertEquals(0, invalidArgumentStream.closeCount());
    }

    /// Verifies the installed formats that advertise forward-only writers.
    @Test
    void discoversStreamingWriterFormats() {
        Set<String> formatNames = ArkivoFormats.installed()
                .stream()
                .filter(ArkivoStreamingWriterFormat.class::isInstance)
                .map(ArkivoFormat::name)
                .collect(Collectors.toUnmodifiableSet());
        assertEquals(Set.of("7z", "ar", "tar", "zip"), formatNames);
    }

    /// Verifies the installed formats that advertise transactional multi-volume streaming writers.
    @Test
    void discoversVolumeStreamingWriterFormats() {
        Set<String> formatNames = ArkivoFormats.installed()
                .stream()
                .filter(ArkivoVolumeStreamingWriterFormat.class::isInstance)
                .map(ArkivoFormat::name)
                .collect(Collectors.toUnmodifiableSet());
        assertEquals(Set.of("7z", "zip"), formatNames);
    }

    /// Verifies the installed formats that advertise conventional path volume discovery.
    @Test
    void discoversPathVolumeFormats() {
        Set<String> formatNames = ArkivoFormats.installed()
                .stream()
                .filter(ArkivoPathVolumeFormat.class::isInstance)
                .map(ArkivoFormat::name)
                .collect(Collectors.toUnmodifiableSet());
        assertEquals(Set.of("7z", "rar", "zip"), formatNames);
    }

    /// Verifies the installed formats that advertise multi-volume forward-only readers.
    @Test
    void discoversVolumeStreamingReaderFormats() {
        Set<String> formatNames = ArkivoFormats.installed()
                .stream()
                .filter(ArkivoVolumeStreamingReaderFormat.class::isInstance)
                .map(ArkivoFormat::name)
                .collect(Collectors.toUnmodifiableSet());
        assertEquals(Set.of("rar", "zip"), formatNames);
    }

    /// Verifies named and detected factories read an actual split ZIP stream and own each source.
    @Test
    void readsSplitZipStreamingVolumesByNameAndDetection() throws IOException {
        byte[] content = volumeContent("zip");
        RecordingVolumeTarget target = new RecordingVolumeTarget();
        try (ArkivoStreamingWriter writer = ArkivoFormats.openStreamingWriter(
                "zip",
                target,
                ZipArkivoFileSystem.MINIMUM_SPLIT_SIZE
        )) {
            writeEntry(writer, ENTRY_PATH, content);
        }
        byte[][] volumes = target.committedVolumes();
        assertTrue(volumes.length > 1);

        TrackingVolumeSource namedSource = new TrackingVolumeSource(volumes);
        try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader("zip", namedSource)) {
            assertEntry(reader, content);
        }
        assertEquals(1, namedSource.closeCount());

        TrackingVolumeSource detectedSource = new TrackingVolumeSource(volumes);
        try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(
                (ArkivoVolumeSource) detectedSource
        )) {
            assertEntry(reader, content);
        }
        assertEquals(1, detectedSource.closeCount());
    }

    /// Verifies detected and named path factories discover split ZIP storage.
    @Test
    void readsSplitZipStreamingPathByNameAndDetection() throws IOException {
        byte[] content = volumeContent("zip");
        RecordingVolumeTarget target = new RecordingVolumeTarget();
        try (ArkivoStreamingWriter writer = ArkivoFormats.openStreamingWriter(
                "zip",
                target,
                ZipArkivoFileSystem.MINIMUM_SPLIT_SIZE
        )) {
            writeEntry(writer, ENTRY_PATH, content);
        }

        Path directory = Files.createTempDirectory("arkivo-split-streaming-path-");
        Path archivePath = directory.resolve("sample.zip");
        List<Path> volumePaths = writeSplitZipVolumes(archivePath, target.committedVolumes());
        try {
            ArkivoPathVolumeFormat format = assertInstanceOf(
                    ArkivoPathVolumeFormat.class,
                    ArkivoFormats.find("zip")
            );
            assertEquals(volumePaths, format.discoverVolumePaths(archivePath));

            try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader("zip", archivePath)) {
                assertEntry(reader, content);
            }
            try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(archivePath)) {
                assertEntry(reader, content);
            }

            assertTrue(volumePaths.size() > 2);
            Path missingVolume = volumePaths.get(1);
            Files.delete(missingVolume);
            NoSuchFileException failure = assertThrows(
                    NoSuchFileException.class,
                    () -> ArkivoFormats.openStreamingReader(archivePath)
            );
            assertEquals(missingVolume.toString(), failure.getFile());
        } finally {
            for (Path volumePath : volumePaths) {
                Files.deleteIfExists(volumePath);
            }
            Files.deleteIfExists(directory);
        }
    }

    /// Verifies path detection retains installed outer source transformations.
    @Test
    void readsCompressedTarStreamingPathByDetection() throws IOException {
        Path path = Files.createTempFile("arkivo-compressed-streaming-path-", ".tar.gz");
        try {
            Files.write(path, compressedTarArchive());
            try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(path)) {
                assertEntry(reader);
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }

    /// Verifies named multi-volume lookup dispatches RAR readers and transfers source ownership.
    @Test
    void readsRarStreamingVolumesByName() throws IOException {
        TrackingVolumeSource source = new TrackingVolumeSource(
                new byte[][]{{'R', 'a', 'r', '!', 0x1a, 0x07, 0x01, 0x00}}
        );

        try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(
                "rar",
                (ArkivoVolumeSource) source
        )) {
            assertInstanceOf(RarArkivoStreamingReader.class, reader);
            org.junit.jupiter.api.Assertions.assertNull(reader.nextEntry());
        }

        assertEquals(1, source.closeCount());
    }

    /// Verifies unavailable multi-volume reader requests close their owned sources before failing.
    @Test
    void rejectsUnavailableVolumeStreamingReadersAndClosesSources() throws IOException {
        TrackingVolumeSource unsupported = new TrackingVolumeSource(new byte[][]{tarArchive()});
        UnsupportedOperationException unsupportedFailure = assertThrows(
                UnsupportedOperationException.class,
                () -> ArkivoFormats.openStreamingReader("tar", (ArkivoVolumeSource) unsupported)
        );
        assertTrue(unsupportedFailure.getMessage().contains("tar"));
        assertEquals(1, unsupported.closeCount());

        TrackingVolumeSource unknown = new TrackingVolumeSource(new byte[][]{{1, 2, 3}});
        IOException unknownFailure = assertThrows(
                IOException.class,
                () -> ArkivoFormats.openStreamingReader("missing", (ArkivoVolumeSource) unknown)
        );
        assertEquals("Unknown archive format: missing", unknownFailure.getMessage());
        assertEquals(1, unknown.closeCount());

        TrackingVolumeSource detectedUnsupported = new TrackingVolumeSource(
                splitArchive(sevenZipArchive(), 11)
        );
        UnsupportedOperationException detectedFailure = assertThrows(
                UnsupportedOperationException.class,
                () -> ArkivoFormats.openStreamingReader((ArkivoVolumeSource) detectedUnsupported)
        );
        assertTrue(detectedFailure.getMessage().contains("7z"));
        assertEquals(1, detectedUnsupported.closeCount());
    }

    /// Verifies successful multi-volume readers propagate owned source cleanup failures.
    @Test
    void volumeStreamingReaderPropagatesSourceCloseFailure() throws IOException {
        TrackingVolumeSource source = new TrackingVolumeSource(
                new byte[][]{zipArchive()},
                true
        );
        ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader("zip", (ArkivoVolumeSource) source);

        IOException failure = assertThrows(IOException.class, reader::close);

        assertEquals("source close failed", failure.getMessage());
        assertEquals(1, source.closeCount());
    }

    /// Verifies multi-volume reader setup failures release source ownership once.
    @Test
    void closesVolumeStreamingReaderSourceAfterSetupFailure() throws IOException {
        TrackingVolumeSource source = new TrackingVolumeSource(new byte[][]{zipArchive()});

        assertThrows(
                IOException.class,
                () -> ArkivoFormats.openStreamingReader("missing", (ArkivoVolumeSource) source)
        );

        assertEquals(1, source.closeCount());
    }

    /// Verifies unified named lookup creates valid transactional split streams for every capable format.
    @Test
    void writesVolumeStreamingFormatsByName() throws IOException {
        assertUnifiedVolumeStreamingWriter("zip", "zip");
        assertUnifiedVolumeStreamingWriter("sevenzip", "7z");
    }

    /// Verifies unavailable and invalid multi-volume writer requests leave targets unopened.
    @Test
    void rejectsUnavailableVolumeStreamingWritersWithoutOpeningTargets() {
        RecordingVolumeTarget unsupportedTarget = new RecordingVolumeTarget();
        UnsupportedOperationException unsupportedFailure = assertThrows(
                UnsupportedOperationException.class,
                () -> ArkivoFormats.openStreamingWriter("tar", unsupportedTarget, 64L)
        );
        assertTrue(unsupportedFailure.getMessage().contains("tar"));
        assertEquals(0, unsupportedTarget.openOutputCount());

        RecordingVolumeTarget unknownTarget = new RecordingVolumeTarget();
        IOException unknownFailure = assertThrows(
                IOException.class,
                () -> ArkivoFormats.openStreamingWriter("missing", unknownTarget, 64L)
        );
        assertEquals("Unknown archive format: missing", unknownFailure.getMessage());
        assertEquals(0, unknownTarget.openOutputCount());

        RecordingVolumeTarget invalidSizeTarget = new RecordingVolumeTarget();
        assertThrows(
                IllegalArgumentException.class,
                () -> ArkivoFormats.openStreamingWriter("zip", invalidSizeTarget, 0L)
        );
        assertEquals(0, invalidSizeTarget.openOutputCount());

    }

    /// Verifies unified multi-volume writers roll back unpublished output when target commit fails.
    @Test
    void rollsBackVolumeStreamingWritersAfterCommitFailure() throws IOException {
        for (String formatName : Set.of("zip", "sevenzip")) {
            long splitSize = volumeSplitSize(formatName, 64L);
            byte[] content = volumeContent(formatName);
            RecordingVolumeTarget target = new RecordingVolumeTarget(true);
            ArkivoStreamingWriter writer = ArkivoFormats.openStreamingWriter(formatName, target, splitSize);
            writeEntry(writer, ENTRY_PATH, content);

            IOException failure = assertThrows(IOException.class, writer::close, formatName);
            assertEquals("commit failed", failure.getMessage(), formatName);
            assertEquals(1, target.openOutputCount(), formatName);
            assertEquals(1, target.rollbackCount(), formatName);
        }
    }

    /// Verifies unified writer lookup creates every writable streaming archive through an owned channel.
    @Test
    void writesEveryStreamingFormatByName() throws IOException {
        for (String formatName : Set.of("7z", "ar", "tar", "zip")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            WritableByteChannel target = Channels.newChannel(output);
            try (ArkivoStreamingWriter writer = ArkivoFormats.openStreamingWriter(formatName, target)) {
                writeEntry(writer);
            }
            assertFalse(target.isOpen(), formatName);

            if ("7z".equals(formatName)) {
                ByteArraySeekableByteChannel source = new ByteArraySeekableByteChannel(output.toByteArray());
                try (ArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(source)) {
                    assertArrayEquals(
                            CONTENT,
                            Files.readAllBytes(fileSystem.getPath("/" + ENTRY_PATH)),
                            formatName
                    );
                }
            } else {
                assertArchive(output.toByteArray());
            }
        }
    }

    /// Verifies writer lookup accepts format aliases.
    @Test
    void writesStreamingFormatByAlias() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ArkivoStreamingWriter writer = ArkivoFormats.openStreamingWriter("sevenzip", output)) {
            writeEntry(writer);
        }

        ByteArraySeekableByteChannel source = new ByteArraySeekableByteChannel(output.toByteArray());
        try (ArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(source)) {
            assertArrayEquals(CONTENT, Files.readAllBytes(fileSystem.getPath("/" + ENTRY_PATH)));
        }
    }

    /// Verifies random-access archive file systems own arbitrary seekable channels at their current positions.
    @Test
    void opensRandomAccessFormatsFromSingleSeekableChannels() throws IOException {
        assertSingleChannelFileSystem("ar", arArchive(), ArArkivoFileSystem::open);
        assertSingleChannelFileSystem("tar", tarArchive(), TarArkivoFileSystem::open);
        assertSingleChannelFileSystem("tar-gzip", compressedTarArchive(), TarArkivoFileSystem::open);
        assertSingleChannelFileSystem("zip", zipArchive(), ZipArkivoFileSystem::open);
        assertSingleChannelFileSystem("7z", sevenZipArchive(), SevenZipArkivoFileSystem::open);
    }

    /// Verifies unified format detection opens every generated random-access archive from one channel.
    @Test
    void detectsAndOpensRandomAccessFormatsFromSingleSeekableChannels() throws IOException {
        assertDetectedSingleChannelFileSystem("ar", arArchive());
        assertDetectedSingleChannelFileSystem("tar", tarArchive());
        assertDetectedSingleChannelFileSystem("zip", zipArchive());
        assertDetectedSingleChannelFileSystem("7z", sevenZipArchive());

        ByteArraySeekableByteChannel unknown = new ByteArraySeekableByteChannel(new byte[]{1, 2, 3, 4});
        IOException failure = assertThrows(IOException.class, () -> ArkivoFormats.openFileSystem(unknown));
        assertEquals("Unrecognized archive format", failure.getMessage());
        assertFalse(unknown.isOpen());
    }

    /// Verifies direct seekable-channel setup failures release caller-provided resources.
    @Test
    void closesSingleSeekableChannelAfterSetupFailure() throws IOException {
        ByteArraySeekableByteChannel channel = new ByteArraySeekableByteChannel(zipArchive());
        assertThrows(
                IOException.class,
                () -> ArkivoFormats.openFileSystem("missing", channel)
        );
        assertFalse(channel.isOpen());
    }

    /// Verifies named file-system lookup opens every generated random-access format from an owned channel.
    @Test
    void opensEveryRandomAccessFormatByName() throws IOException {
        for (Map.Entry<String, byte[]> archive : Map.of(
                "ar", arArchive(),
                "tar", tarArchive(),
                "zip", zipArchive(),
                "7z", sevenZipArchive()
        ).entrySet()) {
            assertNamedSingleChannelFileSystem(archive.getKey(), archive.getValue());
        }

        assertNamedSingleChannelFileSystem("sevenzip", sevenZipArchive());

        ByteArraySeekableByteChannel rarSource = new ByteArraySeekableByteChannel(
                new byte[]{'R', 'a', 'r', '!', 0x1a, 0x07, 0x01, 0x00}
        );
        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem("rar", rarSource)) {
            assertTrue(fileSystem.isOpen());
        }
        assertFalse(rarSource.isOpen());
    }

    /// Verifies detected and named path factories delegate to each concrete format.
    @Test
    void opensRandomAccessFormatsFromPaths() throws IOException {
        for (Map.Entry<String, byte[]> archive : Map.of(
                "ar", arArchive(),
                "tar", tarArchive(),
                "zip", zipArchive(),
                "7z", sevenZipArchive()
        ).entrySet()) {
            Path path = Files.createTempFile("arkivo-unified-", "." + archive.getKey());
            try {
                Files.write(path, archive.getValue());
                assertPathFileSystem(path, null);
                assertPathFileSystem(path, archive.getKey());
            } finally {
                Files.deleteIfExists(path);
            }
        }

        Path compressedTar = Files.createTempFile("arkivo-unified-", ".tar.gz");
        try {
            Files.write(compressedTar, compressedTarArchive());
            assertPathFileSystem(compressedTar, "tar");
        } finally {
            Files.deleteIfExists(compressedTar);
        }
    }

    /// Verifies named file-system lookup closes owned channels after every setup failure.
    @Test
    void closesNamedFileSystemChannelsAfterSetupFailure() throws IOException {
        ByteArraySeekableByteChannel unknown = new ByteArraySeekableByteChannel(new byte[]{1, 2, 3, 4});
        IOException unknownFailure = assertThrows(
                IOException.class,
                () -> ArkivoFormats.openFileSystem("missing", unknown)
        );
        assertEquals("Unknown archive format: missing", unknownFailure.getMessage());
        assertFalse(unknown.isOpen());

        ByteArraySeekableByteChannel mismatch = new ByteArraySeekableByteChannel(zipArchive());
        assertThrows(IOException.class, () -> ArkivoFormats.openFileSystem("ar", mismatch));
        assertFalse(mismatch.isOpen());

    }

    /// Verifies source detection closes probe channels without taking ownership of source factories.
    @Test
    void detectsFormatsWithoutOwningArchiveSources() throws IOException {
        TrackingSeekableSource seekable = new TrackingSeekableSource(zipArchive());
        assertEquals("zip", requireDetectedFormat(ArkivoFormats.detect(seekable)).name());
        assertEquals(0, seekable.closeCount());
        seekable.close();

        TrackingVolumeSource volumes = new TrackingVolumeSource(splitArchive(sevenZipArchive(), 11));
        assertEquals(
                "7z",
                requireDetectedFormat(ArkivoFormats.detect((ArkivoVolumeSource) volumes)).name()
        );
        assertEquals(0, volumes.closeCount());
        volumes.close();
    }

    /// Verifies the installed formats that advertise multi-volume file-system access.
    @Test
    void discoversVolumeFileSystemFormats() {
        Set<String> formatNames = ArkivoFormats.installed()
                .stream()
                .filter(ArkivoVolumeFileSystemFormat.class::isInstance)
                .map(ArkivoFormat::name)
                .collect(Collectors.toUnmodifiableSet());
        assertEquals(Set.of("7z", "rar", "zip"), formatNames);
    }

    /// Verifies the installed formats that advertise path-backed creation and update support.
    @Test
    void discoversWritableFileSystemFormats() {
        Set<String> formatNames = ArkivoFormats.installed()
                .stream()
                .filter(ArkivoFileSystemFormat.Writable.class::isInstance)
                .map(ArkivoFormat::name)
                .collect(Collectors.toUnmodifiableSet());
        assertEquals(Set.of("7z", "ar", "tar", "zip"), formatNames);
    }

    /// Verifies detected and named file-system factories consume repeatable seekable sources.
    @Test
    void opensRandomAccessFormatsFromRepeatableSources() throws IOException {
        for (Map.Entry<String, byte[]> archive : Map.of(
                "ar", arArchive(),
                "tar", tarArchive(),
                "zip", zipArchive(),
                "7z", sevenZipArchive()
        ).entrySet()) {
            assertRepeatableSourceFileSystem(archive.getKey(), archive.getValue(), false);
            assertRepeatableSourceFileSystem(null, archive.getValue(), false);
        }

        byte[] emptyRar = {'R', 'a', 'r', '!', 0x1a, 0x07, 0x01, 0x00};
        assertRepeatableSourceFileSystem("rar", emptyRar, true);
        assertRepeatableSourceFileSystem(null, emptyRar, true);
        assertRepeatableSourceFileSystem("sevenzip", sevenZipArchive(), false);
        assertRepeatableSourceFileSystem("tar", compressedTarArchive(), false);
    }

    /// Verifies detected and named unified factories update every editable path-backed format.
    @Test
    void updatesRandomAccessFormatsFromPaths() throws IOException {
        for (Map.Entry<String, byte[]> archive : Map.of(
                "ar", arArchive(),
                "tar", tarArchive(),
                "zip", zipArchive(),
                "7z", sevenZipArchive()
        ).entrySet()) {
            assertPathUpdate(null, archive.getKey(), archive.getValue());
            assertPathUpdate(archive.getKey(), archive.getKey(), archive.getValue());
        }
    }

    /// Verifies detected and named volume factories preserve logical archives across physical boundaries.
    @Test
    void opensRandomAccessFormatsFromVolumeSources() throws IOException {
        for (Map.Entry<String, byte[]> archive : Map.of(
                "zip", zipArchive(),
                "7z", sevenZipArchive()
        ).entrySet()) {
            assertVolumeSourceFileSystem(archive.getKey(), splitArchive(archive.getValue(), 11), false);
            assertVolumeSourceFileSystem(null, splitArchive(archive.getValue(), 11), false);
        }

        byte[][] emptyRar = {
                new byte[]{'R', 'a', 'r', '!', 0x1a, 0x07, 0x01, 0x00}
        };
        assertVolumeSourceFileSystem("rar", emptyRar, true);
        assertVolumeSourceFileSystem(null, emptyRar, true);
        assertVolumeSourceFileSystem("sevenzip", splitArchive(sevenZipArchive(), 11), false);
    }

    /// Verifies named unified factories create writable ZIP and 7z volume file systems.
    @Test
    void createsRandomAccessFormatsInVolumeTargets() throws IOException {
        assertUnifiedVolumeCreation("zip", "zip");
        assertUnifiedVolumeCreation("sevenzip", "7z");
    }

    /// Verifies detected and named unified factories update ZIP and 7z volume sources.
    @Test
    void updatesRandomAccessFormatsBetweenVolumeEndpoints() throws IOException {
        for (Map.Entry<String, byte[]> archive : Map.of(
                "zip", zipArchive(),
                "7z", sevenZipArchive()
        ).entrySet()) {
            assertUnifiedVolumeUpdate(null, archive.getKey(), archive.getValue());
            assertUnifiedVolumeUpdate(
                    archive.getKey().equals("7z") ? "sevenzip" : archive.getKey(),
                    archive.getKey(),
                    archive.getValue()
            );
        }
    }

    /// Verifies unified volume writes reject unavailable formats without opening output transactions.
    @Test
    void rejectsUnavailableUnifiedVolumeWrites() throws IOException {
        RecordingVolumeTarget createTarget = new RecordingVolumeTarget();
        UnsupportedOperationException createFailure = assertThrows(
                UnsupportedOperationException.class,
                () -> ArkivoFormats.createFileSystem("rar", createTarget, 64L)
        );
        assertTrue(createFailure.getMessage().contains("rar"));
        assertEquals(0, createTarget.openOutputCount());

        TrackingVolumeSource readOnlySource = new TrackingVolumeSource(
                new byte[][]{{'R', 'a', 'r', '!', 0x1a, 0x07, 0x01, 0x00}},
                true
        );
        RecordingVolumeTarget updateTarget = new RecordingVolumeTarget();
        UnsupportedOperationException updateFailure = assertThrows(
                UnsupportedOperationException.class,
                () -> ArkivoFormats.updateFileSystem(readOnlySource, updateTarget, 64L)
        );
        assertTrue(updateFailure.getMessage().contains("rar"));
        assertEquals(1, readOnlySource.closeCount());
        assertEquals(1, updateFailure.getSuppressed().length);
        assertEquals("source close failed", updateFailure.getSuppressed()[0].getMessage());
        assertEquals(0, updateTarget.openOutputCount());

        TrackingVolumeSource unknownSource = new TrackingVolumeSource(splitArchive(zipArchive(), 11));
        RecordingVolumeTarget unknownTarget = new RecordingVolumeTarget();
        IOException unknownFailure = assertThrows(
                IOException.class,
                () -> ArkivoFormats.updateFileSystem("missing", unknownSource, unknownTarget, 64L)
        );
        assertEquals("Unknown archive format: missing", unknownFailure.getMessage());
        assertEquals(1, unknownSource.closeCount());
        assertEquals(0, unknownTarget.openOutputCount());

        TrackingVolumeSource invalidSizeSource = new TrackingVolumeSource(splitArchive(zipArchive(), 11));
        RecordingVolumeTarget invalidSizeTarget = new RecordingVolumeTarget();
        assertThrows(
                IllegalArgumentException.class,
                () -> ArkivoFormats.updateFileSystem(invalidSizeSource, invalidSizeTarget, 0L)
        );
        assertEquals(0, invalidSizeSource.closeCount());
        assertEquals(0, invalidSizeTarget.openOutputCount());

    }

    /// Verifies unified source factories release ownership once after setup failures.
    @Test
    void closesArchiveSourcesAfterUnifiedSetupFailure() throws IOException {
        TrackingSeekableSource unknownSeekable = new TrackingSeekableSource(new byte[]{1, 2, 3, 4});
        IOException unknownSeekableFailure = assertThrows(
                IOException.class,
                () -> ArkivoFormats.openFileSystem("missing", unknownSeekable)
        );
        assertEquals("Unknown archive format: missing", unknownSeekableFailure.getMessage());
        assertEquals(1, unknownSeekable.closeCount());

        TrackingVolumeSource unsupported = new TrackingVolumeSource(new byte[][]{arArchive()});
        UnsupportedOperationException unsupportedFailure = assertThrows(
                UnsupportedOperationException.class,
                () -> ArkivoFormats.openFileSystem((ArkivoVolumeSource) unsupported)
        );
        assertTrue(unsupportedFailure.getMessage().contains("ar"));
        assertEquals(1, unsupported.closeCount());

        TrackingVolumeSource invalidZip = new TrackingVolumeSource(new byte[][]{zipArchive()});
        assertThrows(
                IOException.class,
                () -> ArkivoFormats.openFileSystem("missing", (ArkivoVolumeSource) invalidZip)
        );
        assertEquals(1, invalidZip.closeCount());
    }

    /// Verifies lossless prefix replay for every format with a streaming writer.
    @Test
    void opensWritableStreamingFormatsFromInputStreams() throws IOException {
        assertArchive(tarArchive());
        assertArchive(arArchive());
        assertArchive(zipArchive());
    }

    /// Verifies TAR readers automatically detect every installed compression format with a reliable stream signature.
    @Test
    void opensCompressedTarStreamsWithInstalledFormats() throws IOException {
        for (String formatName : Set.of("bzip2", "gzip", "xz", "zlib", "zstd")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            TarArchiveOptions.Create options = TarArchiveOptions.CREATE_DEFAULTS.withCompression(
                    CompressionFormats.require(formatName).defaultCodec()
            );
            WritableByteChannel target = Channels.newChannel(output);
            try (ArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(target, options)) {
                writeEntry(writer);
            }
            assertFalse(target.isOpen(), formatName);

            ReadableByteChannel source = Channels.newChannel(
                    new ByteArrayInputStream(output.toByteArray())
            );
            try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(source)) {
                assertInstanceOf(TarArkivoStreamingReader.class, reader);
                assertEntry(reader);
            }
            assertFalse(source.isOpen(), formatName);

            ReadableByteChannel namedSource = Channels.newChannel(
                    new ByteArrayInputStream(output.toByteArray())
            );
            try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(
                    "tar",
                    namedSource
            )) {
                assertInstanceOf(TarArkivoStreamingReader.class, reader);
                assertEntry(reader);
            }
            assertFalse(namedSource.isOpen(), formatName);
        }
    }

    /// Verifies raw archive validation takes precedence over a coincidental compression signature.
    @Test
    void prefersValidatedRawArchiveOverCompressionSignature() throws IOException {
        byte[] archive = tarArchive("x\u0001value.txt");
        try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(
                new ByteArrayInputStream(archive)
        )) {
            assertInstanceOf(TarArkivoStreamingReader.class, reader);
            assertEntry(reader);
        }
    }

    /// Verifies malformed compressed sources close the caller-owned stream after decoder failure.
    @Test
    void closesMalformedCompressedSource() {
        TrackingInputStream source = new TrackingInputStream(
                new byte[]{0x1f, (byte) 0x8b, 0x08, 0x00}
        );
        assertThrows(IOException.class, () -> ArkivoFormats.openStreamingReader(source));
        assertTrue(source.closed());
    }

    /// Verifies every writable streaming format retries target closure without duplicating finalization.
    @Test
    void retriesStreamingWriterTargetClosure() throws IOException {
        assertWriterCloseRetry("ar", ArArkivoStreamingWriter::open);
        assertWriterCloseRetry("tar", TarArkivoStreamingWriter::open);
        assertWriterCloseRetry("zip", ZipArkivoStreamingWriter::open);
        assertWriterCloseRetry("7z", SevenZipArkivoStreamingWriter::open);
    }

    /// Verifies writer lookup rejects unknown and read-only formats and closes their targets.
    @Test
    void rejectsUnavailableStreamingWritersAndClosesTargets() {
        WritableByteChannel unknownTarget = Channels.newChannel(new ByteArrayOutputStream());
        IOException unknownFailure = assertThrows(
                IOException.class,
                () -> ArkivoFormats.openStreamingWriter("missing", unknownTarget)
        );
        assertEquals("Unknown archive format: missing", unknownFailure.getMessage());
        assertFalse(unknownTarget.isOpen());

        WritableByteChannel readOnlyTarget = Channels.newChannel(new ByteArrayOutputStream());
        UnsupportedOperationException readOnlyFailure = assertThrows(
                UnsupportedOperationException.class,
                () -> ArkivoFormats.openStreamingWriter("rar", readOnlyTarget)
        );
        assertTrue(readOnlyFailure.getMessage().contains("rar"));
        assertFalse(readOnlyTarget.isOpen());
    }

    /// Verifies unified streaming readers retry caller-source closure.
    @Test
    void retriesStreamingReaderSourceClosure() throws IOException {
        assertReaderCloseRetry("ar", arArchive());
        assertReaderCloseRetry("tar", tarArchive());
        assertReaderCloseRetry("zip", zipArchive());
        assertReaderCloseRetry("rar", new byte[]{'R', 'a', 'r', '!', 0x1a, 0x07, 0x01, 0x00});
    }

    /// Verifies successful reader ownership of a non-seekable channel.
    @Test
    void opensAndClosesReadableChannel() throws IOException {
        ReadableByteChannel channel = Channels.newChannel(new ByteArrayInputStream(tarArchive()));
        try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(channel)) {
            assertEntry(reader);
        }
        assertFalse(channel.isOpen());
    }

    /// Verifies that a fully validated TAR header wins over a short ZIP-like filename prefix.
    @Test
    void prefersValidatedTarOverShortZipPrefix() throws IOException {
        byte[] archive = tarArchive("PK\u0003\u0004value.txt");
        @Nullable ArkivoFormat detected = ArkivoFormats.detect(ByteBuffer.wrap(archive));
        assertNotNull(detected);
        assertEquals("tar", detected.name());
        try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(
                new ByteArrayInputStream(archive)
        )) {
            assertInstanceOf(TarArkivoStreamingReader.class, reader);
            assertEntry(reader);
        }
    }

    /// Verifies that RAR signatures dispatch to the RAR streaming reader.
    @Test
    void dispatchesRarSignature() throws IOException {
        byte[] signature = {'R', 'a', 'r', '!', 0x1a, 0x07, 0x01, 0x00};
        try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(
                new ByteArrayInputStream(signature)
        )) {
            assertInstanceOf(RarArkivoStreamingReader.class, reader);
        }
    }

    /// Verifies that detected seekable-only formats are rejected and their sources are closed.
    @Test
    void rejectsSeekableOnlyFormatAndClosesSource() {
        TrackingInputStream source = new TrackingInputStream(
                new byte[]{'7', 'z', (byte) 0xbc, (byte) 0xaf, 0x27, 0x1c}
        );
        UnsupportedOperationException exception = assertThrows(
                UnsupportedOperationException.class,
                () -> ArkivoFormats.openStreamingReader(source)
        );
        assertTrue(exception.getMessage().contains("7z"));
        assertTrue(source.closed());
    }

    /// Verifies unknown-format and zero-progress failures close their consumed sources.
    @Test
    void closesSourcesAfterProbeFailures() {
        TrackingInputStream unknown = new TrackingInputStream(new byte[]{1, 2, 3, 4});
        IOException unknownException = assertThrows(
                IOException.class,
                () -> ArkivoFormats.openStreamingReader(unknown)
        );
        assertEquals("Unrecognized archive format", unknownException.getMessage());
        assertTrue(unknown.closed());

        ZeroProgressInputStream stalled = new ZeroProgressInputStream();
        IOException stalledException = assertThrows(
                IOException.class,
                () -> ArkivoFormats.openStreamingReader(stalled)
        );
        assertEquals("Archive stream probe made no progress", stalledException.getMessage());
        assertTrue(stalled.closed());
    }

    /// Verifies named setup failures close the source.
    @Test
    void closesSourceAfterNamedSetupFailure() throws IOException {
        TrackingInputStream source = new TrackingInputStream(zipArchive());
        assertThrows(
                IOException.class,
                () -> ArkivoFormats.openStreamingReader("missing", source)
        );
        assertTrue(source.closed());
    }

    /// Verifies one writer factory retries only its target close.
    private static void assertWriterCloseRetry(String format, StreamingWriterFactory factory) throws IOException {
        FailingCloseWritableChannel target = new FailingCloseWritableChannel();
        ArkivoStreamingWriter writer = factory.open(target);
        writeEntry(writer);

        IOException failure = assertThrows(IOException.class, writer::close);
        assertEquals("close failed", failure.getMessage(), format);
        int finalizedSize = target.size();

        writer.close();
        writer.close();
        assertEquals(2, target.closeCount(), format);
        assertEquals(finalizedSize, target.size(), format);
    }

    /// Verifies one automatically detected reader retries only its source close.
    private static void assertReaderCloseRetry(String format, byte[] archive) throws IOException {
        FailingCloseReadableChannel source = new FailingCloseReadableChannel(archive);
        ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(source);

        IOException failure = assertThrows(IOException.class, reader::close);
        assertEquals("close failed", failure.getMessage(), format);

        reader.close();
        reader.close();
        assertEquals(2, source.closeCount(), format);
    }

    /// Creates a TAR archive with one entry.
    private static byte[] tarArchive() throws IOException {
        return tarArchive(ENTRY_PATH);
    }

    /// Creates a TAR archive with one entry at the given path.
    private static byte[] tarArchive(String entryPath) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(output)) {
            writeEntry(writer, entryPath);
        }
        return output.toByteArray();
    }

    /// Creates a gzip-compressed TAR archive with one entry.
    private static byte[] compressedTarArchive() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(
                output,
                TarArchiveOptions.CREATE_DEFAULTS.withCompression(
                        CompressionFormats.require("gzip").defaultCodec()
                )
        )) {
            writeEntry(writer);
        }
        return output.toByteArray();
    }

    /// Creates an AR archive with one entry.
    private static byte[] arArchive() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.open(output)) {
            writeEntry(writer);
        }
        return output.toByteArray();
    }

    /// Creates a ZIP archive with one entry.
    private static byte[] zipArchive() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(output)) {
            writeEntry(writer);
        }
        return output.toByteArray();
    }

    /// Creates a 7z archive with one entry.
    private static byte[] sevenZipArchive() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (SevenZipArkivoStreamingWriter writer = SevenZipArkivoStreamingWriter.open(output)) {
            writeEntry(writer);
        }
        return output.toByteArray();
    }

    /// Opens and verifies one archive from a nonzero position in an arbitrary seekable channel.
    private static void assertSingleChannelFileSystem(
            String format,
            byte[] archive,
            FileSystemFactory factory
    ) throws IOException {
        int archiveOffset = 5;
        byte[] embedded = new byte[archiveOffset + archive.length];
        System.arraycopy(archive, 0, embedded, archiveOffset, archive.length);
        ByteArraySeekableByteChannel channel = new ByteArraySeekableByteChannel(embedded);
        channel.position(archiveOffset);
        try (ArkivoFileSystem fileSystem = factory.open(channel)) {
            assertArrayEquals(
                    CONTENT,
                    Files.readAllBytes(fileSystem.getPath("/" + ENTRY_PATH)),
                    format
            );
        }
        assertFalse(channel.isOpen(), format);
    }

    /// Detects, opens, and verifies one archive from a nonzero position in an arbitrary seekable channel.
    private static void assertDetectedSingleChannelFileSystem(String format, byte[] archive) throws IOException {
        int archiveOffset = 5;
        byte[] embedded = new byte[archiveOffset + archive.length];
        System.arraycopy(archive, 0, embedded, archiveOffset, archive.length);
        ByteArraySeekableByteChannel channel = new ByteArraySeekableByteChannel(embedded);
        channel.position(archiveOffset);
        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(channel)) {
            assertArrayEquals(
                    CONTENT,
                    Files.readAllBytes(fileSystem.getPath("/" + ENTRY_PATH)),
                    format
            );
        }
        assertFalse(channel.isOpen(), format);
    }

    /// Opens and verifies one named archive from a nonzero position in an arbitrary seekable channel.
    private static void assertNamedSingleChannelFileSystem(String format, byte[] archive) throws IOException {
        int archiveOffset = 5;
        byte[] embedded = new byte[archiveOffset + archive.length];
        System.arraycopy(archive, 0, embedded, archiveOffset, archive.length);
        ByteArraySeekableByteChannel channel = new ByteArraySeekableByteChannel(embedded);
        channel.position(archiveOffset);
        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(format, channel)) {
            assertSingleEntryFileSystem(fileSystem, format);
        }
        assertFalse(channel.isOpen(), format);
    }

    /// Opens and verifies one archive path through detection or an explicit format name.
    private static void assertPathFileSystem(Path path, @Nullable String format) throws IOException {
        try (ArkivoFileSystem fileSystem = format == null
                ? ArkivoFormats.openFileSystem(path, ArchiveReadOptions.DEFAULT)
                : ArkivoFormats.openFileSystem(format, path, ArchiveReadOptions.DEFAULT)) {
            assertSingleEntryFileSystem(fileSystem, format == null ? path.toString() : format);
        }
    }

    /// Verifies the generated entry exposed by one archive file system.
    private static void assertSingleEntryFileSystem(ArkivoFileSystem fileSystem, String description) throws IOException {
        assertArrayEquals(
                CONTENT,
                Files.readAllBytes(fileSystem.getPath("/" + ENTRY_PATH)),
                description
        );
    }

    /// Returns a detected archive format after requiring a match.
    private static ArkivoFormat requireDetectedFormat(@Nullable ArkivoFormat format) {
        assertNotNull(format);
        return format;
    }

    /// Updates one path through detection or an explicit format and verifies transactional publication.
    private static void assertPathUpdate(
            @Nullable String selectedFormat,
            String archiveFormat,
            byte[] archive
    ) throws IOException {
        Path target = Files.createTempFile("arkivo-unified-path-update-", "." + archiveFormat);
        Files.write(target, archive);
        byte[] updatedContent = ("updated-" + archiveFormat).getBytes(StandardCharsets.UTF_8);
        try {
            try (ArkivoFileSystem fileSystem = selectedFormat == null
                    ? ArkivoFormats.updateFileSystem(target)
                    : ArkivoFormats.updateFileSystem(selectedFormat, target)) {
                assertFalse(fileSystem.isReadOnly(), archiveFormat);
                assertArrayEquals(
                        CONTENT,
                        Files.readAllBytes(fileSystem.getPath("/" + ENTRY_PATH)),
                        archiveFormat
                );
                Files.write(fileSystem.getPath("/" + ENTRY_PATH), updatedContent);
                Files.writeString(
                        fileSystem.getPath("/added.txt"),
                        "added-" + archiveFormat,
                        StandardCharsets.UTF_8
                );
                assertArrayEquals(
                        updatedContent,
                        Files.readAllBytes(fileSystem.getPath("/" + ENTRY_PATH)),
                        archiveFormat
                );
                assertEquals(
                        "added-" + archiveFormat,
                        Files.readString(fileSystem.getPath("/added.txt"), StandardCharsets.UTF_8),
                        archiveFormat
                );
            }
            assertUpdatedArchive(target, archiveFormat, updatedContent);
        } finally {
            Files.deleteIfExists(target);
        }
    }

    /// Verifies the updated and added entries in one derived archive through automatic format detection.
    private static void assertUpdatedArchive(
            Path target,
            String archiveFormat,
            byte[] updatedContent
    ) throws IOException {
        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(target)) {
            assertArrayEquals(
                    updatedContent,
                    Files.readAllBytes(fileSystem.getPath("/" + ENTRY_PATH)),
                    archiveFormat
            );
            assertEquals(
                    "added-" + archiveFormat,
                    Files.readString(fileSystem.getPath("/added.txt"), StandardCharsets.UTF_8),
                    archiveFormat
            );
        }
    }

    /// Opens and verifies one repeatable seekable source through detection or an explicit format.
    private static void assertRepeatableSourceFileSystem(
            @Nullable String format,
            byte[] archive,
            boolean empty
    ) throws IOException {
        TrackingSeekableSource source = new TrackingSeekableSource(archive);
        try (ArkivoFileSystem fileSystem = format == null
                ? ArkivoFormats.openFileSystem(source)
                : ArkivoFormats.openFileSystem(format, source, ArchiveReadOptions.DEFAULT)) {
            if (empty) {
                assertTrue(fileSystem.isOpen());
            } else {
                assertSingleEntryFileSystem(fileSystem, format == null ? "detected source" : format);
            }
        }
        assertEquals(1, source.closeCount(), format == null ? "detected source" : format);
    }

    /// Opens and verifies one volume source through detection or an explicit format.
    private static void assertVolumeSourceFileSystem(
            @Nullable String format,
            byte[][] volumes,
            boolean empty
    ) throws IOException {
        TrackingVolumeSource source = new TrackingVolumeSource(volumes);
        try (ArkivoFileSystem fileSystem = format == null
                ? ArkivoFormats.openFileSystem((ArkivoVolumeSource) source)
                : ArkivoFormats.openFileSystem(format, (ArkivoVolumeSource) source, ArchiveReadOptions.DEFAULT)) {
            if (empty) {
                assertTrue(fileSystem.isOpen());
            } else {
                assertSingleEntryFileSystem(fileSystem, format == null ? "detected volumes" : format);
            }
        }
        assertEquals(1, source.closeCount(), format == null ? "detected volumes" : format);
    }

    /// Creates one split archive through the named unified volume factory and verifies its committed content.
    private static void assertUnifiedVolumeCreation(String selectedFormat, String archiveFormat) throws IOException {
        long splitSize = volumeSplitSize(archiveFormat, 41L);
        byte[] content = volumeContent(archiveFormat);
        RecordingVolumeTarget target = new RecordingVolumeTarget();
        try (ArkivoFileSystem fileSystem = ArkivoFormats.createFileSystem(selectedFormat, target, splitSize)) {
            assertFalse(fileSystem.isReadOnly(), archiveFormat);
            Files.write(fileSystem.getPath("/" + ENTRY_PATH), content);
        }

        assertEquals(1, target.openOutputCount(), archiveFormat);
        assertTrue(target.committedVolumes().length > 1, archiveFormat);
        assertTrue(target.allCommittedVolumeSizesAtMost(splitSize), archiveFormat);
        assertEquals(0, target.rollbackCount(), archiveFormat);
        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(
                archiveFormat,
                (ArkivoVolumeSource) new TrackingVolumeSource(target.committedVolumes())
        )) {
            assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/" + ENTRY_PATH)), archiveFormat);
        }
    }

    /// Updates one split archive through detection or a named format and verifies endpoint ownership and publication.
    private static void assertUnifiedVolumeUpdate(
            @Nullable String selectedFormat,
            String archiveFormat,
            byte[] archive
    ) throws IOException {
        long splitSize = volumeSplitSize(archiveFormat, 37L);
        TrackingVolumeSource source = new TrackingVolumeSource(splitArchive(archive, 11));
        RecordingVolumeTarget target = new RecordingVolumeTarget();
        byte[] updatedContent = "zip".equals(archiveFormat)
                ? volumeContent(archiveFormat)
                : ("volume-" + archiveFormat).getBytes(StandardCharsets.UTF_8);
        try (ArkivoFileSystem fileSystem = selectedFormat == null
                ? ArkivoFormats.updateFileSystem(source, target, splitSize)
                : ArkivoFormats.updateFileSystem(selectedFormat, source, target, splitSize)) {
            assertFalse(fileSystem.isReadOnly(), archiveFormat);
            assertArrayEquals(CONTENT, Files.readAllBytes(fileSystem.getPath("/" + ENTRY_PATH)));
            Files.write(fileSystem.getPath("/" + ENTRY_PATH), updatedContent);
            Files.writeString(fileSystem.getPath("/added.txt"), "added", StandardCharsets.UTF_8);
        }

        assertEquals(1, source.closeCount(), archiveFormat);
        assertEquals(1, target.openOutputCount(), archiveFormat);
        assertTrue(target.committedVolumes().length > 1, archiveFormat);
        assertTrue(target.allCommittedVolumeSizesAtMost(splitSize), archiveFormat);
        assertEquals(0, target.rollbackCount(), archiveFormat);
        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(
                archiveFormat,
                (ArkivoVolumeSource) new TrackingVolumeSource(target.committedVolumes())
        )) {
            assertArrayEquals(updatedContent, Files.readAllBytes(fileSystem.getPath("/" + ENTRY_PATH)));
            assertEquals("added", Files.readString(fileSystem.getPath("/added.txt"), StandardCharsets.UTF_8));
        }
    }

    /// Writes physical ZIP volumes using the conventional numbered and final-path layout.
    private static @Unmodifiable List<Path> writeSplitZipVolumes(
            Path archivePath,
            byte[][] volumes
    ) throws IOException {
        if (volumes.length < 2) {
            throw new IllegalArgumentException("Split ZIP test output requires at least two volumes");
        }
        Path parent = Objects.requireNonNull(archivePath.getParent(), "archivePath parent");
        String fileName = archivePath.getFileName().toString();
        String baseName = fileName.substring(0, fileName.length() - ".zip".length());
        ArrayList<Path> paths = new ArrayList<>(volumes.length);
        for (int index = 0; index < volumes.length; index++) {
            Path volumePath;
            if (index == volumes.length - 1) {
                volumePath = archivePath;
            } else {
                String number = Integer.toString(index + 1);
                if (number.length() < 2) {
                    number = "0" + number;
                }
                volumePath = parent.resolve(baseName + ".z" + number);
            }
            Files.write(volumePath, volumes[index]);
            paths.add(volumePath);
        }
        return List.copyOf(paths);
    }

    /// Splits one logical archive into fixed-size physical volume byte arrays.
    private static byte[][] splitArchive(byte[] archive, int splitSize) {
        int volumeCount = (archive.length + splitSize - 1) / splitSize;
        byte[][] volumes = new byte[volumeCount][];
        for (int index = 0; index < volumeCount; index++) {
            int offset = index * splitSize;
            int length = Math.min(splitSize, archive.length - offset);
            volumes[index] = java.util.Arrays.copyOfRange(archive, offset, offset + length);
        }
        return volumes;
    }

    /// Creates, reopens, and verifies one archive through the unified multi-volume streaming writer factory.
    private static void assertUnifiedVolumeStreamingWriter(
            String selectedFormat,
            String archiveFormat
    ) throws IOException {
        long splitSize = volumeSplitSize(archiveFormat, 64L);
        byte[] content = volumeContent(archiveFormat);
        RecordingVolumeTarget target = new RecordingVolumeTarget();
        try (ArkivoStreamingWriter writer = ArkivoFormats.openStreamingWriter(
                selectedFormat,
                target,
                splitSize
        )) {
            writeEntry(writer, ENTRY_PATH, content);
        }

        byte[][] volumes = target.committedVolumes();
        assertTrue(volumes.length > 1, archiveFormat);
        assertTrue(target.allCommittedVolumeSizesAtMost(splitSize), archiveFormat);
        assertEquals(0, target.rollbackCount(), archiveFormat);

        TrackingVolumeSource source = new TrackingVolumeSource(volumes);
        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(source)) {
            assertArrayEquals(
                    content,
                    Files.readAllBytes(fileSystem.getPath("/" + ENTRY_PATH)),
                    archiveFormat
            );
        }
        assertEquals(1, source.closeCount(), archiveFormat);
    }

    /// Writes one regular file entry to a streaming archive writer.
    private static void writeEntry(ArkivoStreamingWriter writer) throws IOException {
        writeEntry(writer, ENTRY_PATH);
    }

    /// Writes one regular file entry with the given path to a streaming archive writer.
    private static void writeEntry(ArkivoStreamingWriter writer, String entryPath) throws IOException {
        writeEntry(writer, entryPath, CONTENT);
    }

    /// Writes one regular file entry with the given path and content.
    private static void writeEntry(
            ArkivoStreamingWriter writer,
            String entryPath,
            byte[] content
    ) throws IOException {
        var writerEntry1550 = writer.beginFile(entryPath);
        try (OutputStream body = writerEntry1550.openOutputStream()) {
            body.write(content);
        }
    }

    /// Returns a standards-compliant split size for ZIP or the requested size for another format.
    private static long volumeSplitSize(String archiveFormat, long otherFormatSplitSize) {
        return "zip".equals(archiveFormat)
                ? ZipArkivoFileSystem.MINIMUM_SPLIT_SIZE
                : otherFormatSplitSize;
    }

    /// Returns content that forces ZIP output across volumes while keeping other format tests compact.
    private static byte[] volumeContent(String archiveFormat) {
        if (!"zip".equals(archiveFormat)) {
            return CONTENT;
        }
        int size = Math.toIntExact(ZipArkivoFileSystem.MINIMUM_SPLIT_SIZE * 2L);
        byte[] content = new byte[size];
        new Random(0x41524b49564fL).nextBytes(content);
        return content;
    }

    /// Opens and verifies the single entry in an automatically detected archive.
    private static void assertArchive(byte[] archive) throws IOException {
        try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(
                new ByteArrayInputStream(archive)
        )) {
            assertEntry(reader);
        }
    }

    /// Verifies and consumes one streaming archive entry.
    private static void assertEntry(ArkivoStreamingReader reader) throws IOException {
        assertEntry(reader, CONTENT);
    }

    /// Verifies and consumes one streaming archive entry with the expected content.
    private static void assertEntry(ArkivoStreamingReader reader, byte[] expectedContent) throws IOException {
        var readerEntry1590 = java.util.Objects.requireNonNull(reader.nextEntry());
        BasicFileAttributes attributes = readerEntry1590.attributes(BasicFileAttributes.class);
        assertTrue(attributes.isRegularFile());
        try (InputStream body = readerEntry1590.openInputStream()) {
            assertArrayEquals(expectedContent, body.readAllBytes());
        }
        org.junit.jupiter.api.Assertions.assertNull(reader.nextEntry());
    }

    /// Opens an archive file system over one owned seekable channel.
    @FunctionalInterface
    @NotNullByDefault
    private interface FileSystemFactory {
        /// Opens one read-only archive file system.
        ArkivoFileSystem open(SeekableByteChannel source) throws IOException;
    }

    /// Opens a streaming writer over a caller-owned target channel.
    @FunctionalInterface
    @NotNullByDefault
    private interface StreamingWriterFactory {
        /// Opens one owning streaming writer.
        ArkivoStreamingWriter open(WritableByteChannel target) throws IOException;
    }

    /// Provides repeatable channels over one logical archive and tracks ownership closure.
    @NotNullByDefault
    private static final class TrackingSeekableSource implements ArkivoSeekableChannelSource {
        /// Immutable archive bytes.
        private final byte @Unmodifiable [] archive;

        /// Number of source close attempts.
        private int closeCount;

        /// Creates a repeatable source over copied bytes.
        private TrackingSeekableSource(byte[] archive) {
            this.archive = archive.clone();
        }

        /// Opens one independent archive channel.
        @Override
        public SeekableByteChannel openChannel() throws IOException {
            if (closeCount != 0) {
                throw new ClosedChannelException();
            }
            return new ByteArraySeekableByteChannel(archive);
        }

        /// Closes this source and records the attempt.
        @Override
        public void close() {
            closeCount++;
        }

        /// Returns the number of source close attempts.
        private int closeCount() {
            return closeCount;
        }
    }

    /// Provides repeatable physical volumes and tracks source close attempts.
    @NotNullByDefault
    private static final class TrackingVolumeSource implements ArkivoVolumeSource {
        /// Immutable physical volume bytes.
        private final byte @Unmodifiable [] @Unmodifiable [] volumes;

        /// Whether every close attempt should fail.
        private final boolean failClose;

        /// Number of source close attempts.
        private int closeCount;

        /// Creates a successfully closing volume source.
        private TrackingVolumeSource(byte[][] volumes) {
            this(volumes, false);
        }

        /// Creates a volume source with configurable close failure.
        private TrackingVolumeSource(byte[][] volumes, boolean failClose) {
            this.volumes = new byte[volumes.length][];
            for (int index = 0; index < volumes.length; index++) {
                this.volumes[index] = volumes[index].clone();
            }
            this.failClose = failClose;
        }

        /// Opens one independent physical volume channel.
        @Override
        public @Nullable SeekableByteChannel openVolume(long index) throws IOException {
            if (closeCount != 0) {
                throw new ClosedChannelException();
            }
            if (index < 0L || index >= volumes.length) {
                return null;
            }
            return new ByteArraySeekableByteChannel(volumes[(int) index]);
        }

        /// Closes this source and optionally reports a configured failure.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (failClose) {
                throw new IOException("source close failed");
            }
        }

        /// Returns the number of source close attempts.
        private int closeCount() {
            return closeCount;
        }
    }

    /// Creates one in-memory transactional output for unified volume factory tests.
    @NotNullByDefault
    private static final class RecordingVolumeTarget implements ArkivoVolumeTarget {
        /// Whether the transaction should reject commit.
        private final boolean failCommit;

        /// Number of output transactions opened by this target.
        private int openOutputCount;

        /// The latest output transaction, or `null` before use.
        private @Nullable RecordingVolumeOutput output;

        /// Creates a target whose output commits successfully.
        private RecordingVolumeTarget() {
            this(false);
        }

        /// Creates a target with configurable commit failure.
        private RecordingVolumeTarget(boolean failCommit) {
            this.failCommit = failCommit;
        }

        /// Opens a new recording volume transaction.
        @Override
        public ArkivoVolumeOutput openOutput() throws IOException {
            if (output != null) {
                throw new IOException("volume output is already open");
            }
            openOutputCount++;
            output = new RecordingVolumeOutput(failCommit);
            return output;
        }

        /// Returns the number of opened output transactions.
        private int openOutputCount() {
            return openOutputCount;
        }

        /// Returns copies of the committed physical volumes.
        private byte @Unmodifiable [] @Unmodifiable [] committedVolumes() {
            RecordingVolumeOutput current = Objects.requireNonNull(output, "output");
            return current.committedVolumes();
        }

        /// Returns whether all committed volumes respect the maximum size.
        private boolean allCommittedVolumeSizesAtMost(long maximumSize) {
            RecordingVolumeOutput current = Objects.requireNonNull(output, "output");
            return current.allCommittedVolumeSizesAtMost(maximumSize);
        }

        /// Returns the number of effective rollback operations.
        private int rollbackCount() {
            RecordingVolumeOutput current = Objects.requireNonNull(output, "output");
            return current.rollbackCount();
        }
    }

    /// Records one in-memory transactional multi-volume output.
    @NotNullByDefault
    private static final class RecordingVolumeOutput implements ArkivoVolumeOutput {
        /// Bytes written to each physical volume.
        private final ArrayList<ByteArrayOutputStream> volumes = new ArrayList<>();

        /// Channels opened for the physical volumes.
        private final ArrayList<WritableByteChannel> channels = new ArrayList<>();

        /// Whether this output rejects commit.
        private final boolean failCommit;

        /// Number of effective rollback operations.
        private int rollbackCount;

        /// Whether the output has committed.
        private boolean committed;

        /// Whether the transaction has committed or rolled back.
        private boolean finished;

        /// Creates a recording output with configurable commit failure.
        private RecordingVolumeOutput(boolean failCommit) {
            this.failCommit = failCommit;
        }

        /// Opens the next physical volume channel.
        @Override
        public WritableByteChannel openVolume(long index) throws IOException {
            if (finished) {
                throw new IOException("volume output is finished");
            }
            if (index != channels.size()) {
                throw new IllegalArgumentException("volume indexes must be contiguous");
            }
            if (!channels.isEmpty() && channels.get(channels.size() - 1).isOpen()) {
                throw new IOException("previous volume is still open");
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            WritableByteChannel channel = Channels.newChannel(bytes);
            volumes.add(bytes);
            channels.add(channel);
            return channel;
        }

        /// Commits every opened physical volume.
        @Override
        public void commit(long finalVolumeIndex) throws IOException {
            if (finished) {
                throw new IOException("volume output is finished");
            }
            if (finalVolumeIndex != channels.size() - 1L) {
                throw new IllegalArgumentException("finalVolumeIndex does not identify the last volume");
            }
            for (WritableByteChannel channel : channels) {
                if (channel.isOpen()) {
                    throw new IOException("volume channel is still open");
                }
            }
            if (failCommit) {
                throw new IOException("commit failed");
            }
            committed = true;
            finished = true;
        }

        /// Rolls back an unfinished transaction.
        @Override
        public void rollback() {
            if (!finished) {
                rollbackCount++;
                finished = true;
            }
        }

        /// Closes this transaction and rolls it back when unfinished.
        @Override
        public void close() {
            rollback();
        }

        /// Returns copies of every committed physical volume.
        private byte @Unmodifiable [] @Unmodifiable [] committedVolumes() {
            if (!committed) {
                throw new IllegalStateException("volume output is not committed");
            }
            byte[][] result = new byte[volumes.size()][];
            for (int index = 0; index < volumes.size(); index++) {
                result[index] = volumes.get(index).toByteArray();
            }
            return result;
        }

        /// Returns whether all committed volumes respect the maximum size.
        private boolean allCommittedVolumeSizesAtMost(long maximumSize) {
            if (!committed) {
                throw new IllegalStateException("volume output is not committed");
            }
            for (ByteArrayOutputStream volume : volumes) {
                if (volume.size() > maximumSize) {
                    return false;
                }
            }
            return true;
        }

        /// Returns the number of effective rollback operations.
        private int rollbackCount() {
            return rollbackCount;
        }
    }

    /// Provides a read-only in-memory seekable channel unrelated to file-system paths.
    @NotNullByDefault
    private static final class ByteArraySeekableByteChannel implements SeekableByteChannel {
        /// Immutable source bytes.
        private final byte @Unmodifiable [] bytes;

        /// Current channel position.
        private long position;

        /// Whether this channel remains open.
        private boolean open = true;

        /// Creates a channel over copied bytes.
        private ByteArraySeekableByteChannel(byte[] bytes) {
            this.bytes = bytes.clone();
        }

        /// Reads bytes from the current position.
        @Override
        public int read(ByteBuffer target) throws IOException {
            ensureOpen();
            if (!target.hasRemaining()) {
                return 0;
            }
            if (position >= bytes.length) {
                return -1;
            }
            int count = (int) Math.min((long) target.remaining(), bytes.length - position);
            target.put(bytes, (int) position, count);
            position += count;
            return count;
        }

        /// Rejects writes.
        @Override
        public int write(ByteBuffer source) {
            throw new UnsupportedOperationException("read-only");
        }

        /// Returns the current position.
        @Override
        public long position() throws IOException {
            ensureOpen();
            return position;
        }

        /// Changes the current position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            ensureOpen();
            if (newPosition < 0L) {
                throw new IllegalArgumentException("newPosition must not be negative");
            }
            position = newPosition;
            return this;
        }

        /// Returns the immutable channel size.
        @Override
        public long size() throws IOException {
            ensureOpen();
            return bytes.length;
        }

        /// Rejects truncation.
        @Override
        public SeekableByteChannel truncate(long size) {
            throw new UnsupportedOperationException("read-only");
        }

        /// Returns whether this channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this channel.
        @Override
        public void close() {
            open = false;
        }

        /// Requires this channel to remain open.
        private void ensureOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }

    /// Collects archive bytes and fails its first close attempt.
    @NotNullByDefault
    private static final class FailingCloseWritableChannel implements WritableByteChannel {
        /// Collected archive bytes.
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        /// Writable delegate over the collected bytes.
        private final WritableByteChannel delegate = Channels.newChannel(bytes);

        /// Number of close attempts.
        private int closeCount;

        /// Writes archive bytes.
        @Override
        public int write(ByteBuffer source) throws IOException {
            return delegate.write(source);
        }

        /// Returns whether the delegate remains open.
        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        /// Fails the first close attempt.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                throw new IOException("close failed");
            }
            delegate.close();
        }

        /// Returns the number of close attempts.
        private int closeCount() {
            return closeCount;
        }

        /// Returns the number of collected bytes.
        private int size() {
            return bytes.size();
        }
    }

    /// Reads archive bytes and fails its first close attempt.
    @NotNullByDefault
    private static final class FailingCloseReadableChannel implements ReadableByteChannel {
        /// Readable archive-data delegate.
        private final ReadableByteChannel delegate;

        /// Number of close attempts.
        private int closeCount;

        /// Creates a source over archive bytes.
        private FailingCloseReadableChannel(byte[] bytes) {
            delegate = Channels.newChannel(new ByteArrayInputStream(bytes));
        }

        /// Reads archive bytes.
        @Override
        public int read(ByteBuffer target) throws IOException {
            return delegate.read(target);
        }

        /// Returns whether the delegate remains open.
        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        /// Fails the first close attempt.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                throw new IOException("close failed");
            }
            delegate.close();
        }

        /// Returns the number of close attempts.
        private int closeCount() {
            return closeCount;
        }
    }

    /// Tracks close attempts on a byte-array input stream.
    @NotNullByDefault
    private static final class TrackingInputStream extends ByteArrayInputStream {
        /// The number of close attempts.
        private int closeCount;

        /// Creates a tracked source over the given bytes.
        private TrackingInputStream(byte[] content) {
            super(content);
        }

        /// Closes this source.
        @Override
        public void close() throws IOException {
            closeCount++;
            super.close();
        }

        /// Returns whether this source has closed.
        private boolean closed() {
            return closeCount > 0;
        }

        /// Returns the number of close attempts.
        private int closeCount() {
            return closeCount;
        }
    }

    /// Reports zero progress for every bulk read.
    @NotNullByDefault
    private static final class ZeroProgressInputStream extends InputStream {
        /// Whether this stream has closed.
        private boolean closed;

        /// Returns no byte for single-byte reads.
        @Override
        public int read() {
            return 0;
        }

        /// Reports no progress for bulk reads.
        @Override
        public int read(byte[] bytes, int offset, int length) {
            return 0;
        }

        /// Closes this source.
        @Override
        public void close() {
            closed = true;
        }

        /// Returns whether this source has closed.
        private boolean closed() {
            return closed;
        }
    }
}
