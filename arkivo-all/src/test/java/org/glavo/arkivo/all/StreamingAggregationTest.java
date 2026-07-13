// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArkivoFormat;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.archive.ArkivoStreamingReaderFormat;
import org.glavo.arkivo.archive.ArkivoStreamingReader;
import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.glavo.arkivo.archive.ArkivoStreamingWriterFormat;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.ArkivoVolumeFileSystemFormat;
import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.glavo.arkivo.archive.ar.ArArkivoStreamingWriter;
import org.glavo.arkivo.archive.ar.ArArkivoFileSystem;
import org.glavo.arkivo.archive.rar.RarArkivoStreamingReader;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoStreamingWriter;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoFileSystem;
import org.glavo.arkivo.archive.tar.TarArkivoFileSystem;
import org.glavo.arkivo.archive.tar.TarArkivoStreamingReader;
import org.glavo.arkivo.archive.tar.TarArkivoStreamingWriter;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.archive.zip.ZipArkivoStreamingWriter;
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
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
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
            assertFalse(reader.next());
        }
        assertTrue(rarSource.closed());
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
                IllegalArgumentException.class,
                () -> ArkivoFormats.openFileSystem(
                        channel,
                        Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), "invalid")
                )
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

        ByteArraySeekableByteChannel invalidEnvironment = new ByteArraySeekableByteChannel(zipArchive());
        assertThrows(
                IllegalArgumentException.class,
                () -> ArkivoFormats.openFileSystem(
                        "zip",
                        invalidEnvironment,
                        Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), "invalid")
                )
        );
        assertFalse(invalidEnvironment.isOpen());
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
                IllegalArgumentException.class,
                () -> ArkivoFormats.openFileSystem(
                        "zip",
                        (ArkivoVolumeSource) invalidZip,
                        Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), "invalid")
                )
        );
        assertEquals(1, invalidZip.closeCount());

        TrackingVolumeSource invalidRar = new TrackingVolumeSource(
                new byte[][]{{'R', 'a', 'r', '!', 0x1a, 0x07, 0x01, 0x00}},
                true
        );
        UnsupportedOperationException invalidRarFailure = assertThrows(
                UnsupportedOperationException.class,
                () -> ArkivoFormats.openFileSystem(
                        "rar",
                        (ArkivoVolumeSource) invalidRar,
                        Map.of(ArkivoFileSystem.OPEN_OPTIONS.key(), Set.of(java.nio.file.StandardOpenOption.WRITE))
                )
        );
        assertEquals(1, invalidRar.closeCount());
        assertEquals(1, invalidRarFailure.getSuppressed().length);
        assertEquals("source close failed", invalidRarFailure.getSuppressed()[0].getMessage());
    }

    /// Verifies lossless prefix replay for every format with a streaming writer.
    @Test
    void opensWritableStreamingFormatsFromInputStreams() throws IOException {
        assertArchive(tarArchive());
        assertArchive(arArchive());
        assertArchive(zipArchive());
    }

    /// Verifies TAR readers automatically detect every installed codec with a reliable stream signature.
    @Test
    void opensCompressedTarStreamsWithInstalledCodecs() throws IOException {
        for (String codecName : Set.of("bzip2", "gzip", "xz", "zlib", "zstd")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Map<String, Object> environment = Map.of(
                    TarArkivoFileSystem.COMPRESSION.key(),
                    codecName
            );
            WritableByteChannel target = Channels.newChannel(output);
            try (ArkivoStreamingWriter writer = ArkivoFormats.openStreamingWriter("tar", target, environment)) {
                writeEntry(writer);
            }
            assertFalse(target.isOpen(), codecName);

            ReadableByteChannel source = Channels.newChannel(
                    new ByteArrayInputStream(output.toByteArray())
            );
            try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(source)) {
                assertInstanceOf(TarArkivoStreamingReader.class, reader);
                assertEntry(reader);
            }
            assertFalse(source.isOpen(), codecName);

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
            assertFalse(namedSource.isOpen(), codecName);
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

    /// Verifies direct and unified writer setup close owned targets while preserving close failures.
    @Test
    void closesStreamingWriterTargetAfterSetupFailure() {
        FailingCloseWritableChannel directTarget = new FailingCloseWritableChannel();
        UnsupportedOperationException directFailure = assertThrows(
                UnsupportedOperationException.class,
                () -> ZipArkivoStreamingWriter.open(
                        directTarget,
                        Map.of(ZipArkivoFileSystem.SPLIT_SIZE.key(), 64L)
                )
        );

        assertTrue(directFailure.getMessage().contains("archive path"));
        assertEquals(1, directTarget.closeCount());
        assertEquals(1, directFailure.getSuppressed().length);
        assertEquals("close failed", directFailure.getSuppressed()[0].getMessage());

        FailingCloseWritableChannel unifiedTarget = new FailingCloseWritableChannel();
        UnsupportedOperationException unifiedFailure = assertThrows(
                UnsupportedOperationException.class,
                () -> ArkivoFormats.openStreamingWriter(
                        "zip",
                        unifiedTarget,
                        Map.of(ZipArkivoFileSystem.SPLIT_SIZE.key(), 64L)
                )
        );

        assertTrue(unifiedFailure.getMessage().contains("archive path"));
        assertEquals(2, unifiedTarget.closeCount());
        assertFalse(unifiedTarget.isOpen());
        assertEquals(1, unifiedFailure.getSuppressed().length);
        assertEquals("close failed", unifiedFailure.getSuppressed()[0].getMessage());
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

    /// Verifies environment options reach the detected format and setup failures close the source.
    @Test
    void forwardsEnvironmentAndClosesSourceAfterSetupFailure() throws IOException {
        TrackingInputStream source = new TrackingInputStream(zipArchive());
        assertThrows(
                IllegalArgumentException.class,
                () -> ArkivoFormats.openStreamingReader(
                        source,
                        Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), "invalid")
                )
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
                Map.of(TarArkivoFileSystem.COMPRESSION.key(), "gzip")
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
                ? ArkivoFormats.openFileSystem(path, Map.of())
                : ArkivoFormats.openFileSystem(format, path, Map.of())) {
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

    /// Opens and verifies one repeatable seekable source through detection or an explicit format.
    private static void assertRepeatableSourceFileSystem(
            @Nullable String format,
            byte[] archive,
            boolean empty
    ) throws IOException {
        TrackingSeekableSource source = new TrackingSeekableSource(archive);
        try (ArkivoFileSystem fileSystem = format == null
                ? ArkivoFormats.openFileSystem(source)
                : ArkivoFormats.openFileSystem(format, source, Map.of())) {
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
                : ArkivoFormats.openFileSystem(format, (ArkivoVolumeSource) source, Map.of())) {
            if (empty) {
                assertTrue(fileSystem.isOpen());
            } else {
                assertSingleEntryFileSystem(fileSystem, format == null ? "detected volumes" : format);
            }
        }
        assertEquals(1, source.closeCount(), format == null ? "detected volumes" : format);
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

    /// Writes one regular file entry to a streaming archive writer.
    private static void writeEntry(ArkivoStreamingWriter writer) throws IOException {
        writeEntry(writer, ENTRY_PATH);
    }

    /// Writes one regular file entry with the given path to a streaming archive writer.
    private static void writeEntry(ArkivoStreamingWriter writer, String entryPath) throws IOException {
        writer.beginFile(entryPath);
        try (OutputStream body = writer.openOutputStream()) {
            body.write(CONTENT);
        }
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
        assertTrue(reader.next());
        BasicFileAttributes attributes = reader.readAttributes(BasicFileAttributes.class);
        assertTrue(attributes.isRegularFile());
        try (InputStream body = reader.openInputStream()) {
            assertArrayEquals(CONTENT, body.readAllBytes());
        }
        assertFalse(reader.next());
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

    /// Tracks whether a byte-array input stream has been closed.
    @NotNullByDefault
    private static final class TrackingInputStream extends ByteArrayInputStream {
        /// Whether this stream has closed.
        private boolean closed;

        /// Creates a tracked source over the given bytes.
        private TrackingInputStream(byte[] content) {
            super(content);
        }

        /// Closes this source.
        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        /// Returns whether this source has closed.
        private boolean closed() {
            return closed;
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
