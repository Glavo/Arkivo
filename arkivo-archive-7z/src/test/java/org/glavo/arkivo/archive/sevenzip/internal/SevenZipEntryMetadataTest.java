// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoEntryAttributes;
import org.glavo.arkivo.archive.sevenzip.SevenZipCoderMethod;
import org.glavo.arkivo.archive.sevenzip.SevenZipPackedStream;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies construction, derived layout values, and immutable views of parsed 7z entry metadata.
@NotNullByDefault
final class SevenZipEntryMetadataTest {
    /// Retains a single packed stream and copies caller-owned coder arrays on input and output.
    @Test
    void retainsSingleStreamMetadataWithDefensiveCoderCopies() {
        byte[] methodId = SevenZipCoderMethod.LZMA2.methodId();
        byte[] properties = {16};
        FileTime creationTime = FileTime.from(Instant.ofEpochSecond(1L));
        FileTime accessTime = FileTime.from(Instant.ofEpochSecond(2L));
        FileTime modifiedTime = FileTime.from(Instant.ofEpochSecond(3L));
        SevenZipEntryMetadata metadata = new SevenZipEntryMetadata(
                "directory/payload.bin",
                false,
                4096L,
                64L,
                11L,
                123L,
                methodId,
                properties,
                creationTime,
                accessTime,
                modifiedTime,
                0x21
        );

        methodId[0] ^= 0x7f;
        properties[0] = 0;
        byte[] returnedMethodId = metadata.methodId();
        byte[] returnedProperties = metadata.coderProperties();
        returnedMethodId[0] ^= 0x7f;
        returnedProperties[0] = 0;

        assertEquals("directory/payload.bin", metadata.path());
        assertFalse(metadata.directory());
        assertEquals(4096L, metadata.size());
        assertEquals(64L, metadata.dataOffset());
        assertEquals(11L, metadata.decodedOffset());
        assertEquals(123L, metadata.packedSize());
        assertEquals(SevenZipEntryMetadata.UNKNOWN_CRC32, metadata.packedCrc32());
        assertEquals(SevenZipEntryMetadata.UNKNOWN_CRC32, metadata.crc32());
        assertArrayEquals(SevenZipCoderMethod.LZMA2.methodId(), metadata.methodId());
        assertArrayEquals(new byte[]{16}, metadata.coderProperties());
        assertTrue(metadata.hasMethod(SevenZipCoderMethod.LZMA2.methodId()));
        assertFalse(metadata.hasMethod(SevenZipCoderMethod.COPY.methodId()));
        assertEquals(creationTime, metadata.creationTime());
        assertEquals(accessTime, metadata.lastAccessTime());
        assertEquals(modifiedTime, metadata.lastModifiedTime());
        assertEquals(0x21, metadata.windowsAttributes());
        assertEquals(0, metadata.substreamIndex());
        assertEquals(1, metadata.substreamCount());
        assertFalse(metadata.solid());
        assertNotNull(metadata.coderGraph());
        assertEquals(
                List.of(new SevenZipPackedStream(64L, 123L, SevenZipPackedStream.UNKNOWN_CRC32)),
                metadata.packedStreams()
        );
        assertThrows(UnsupportedOperationException.class, () -> metadata.packedStreams().clear());
    }

    /// Represents an entry without a body using consistent public sentinel and empty-view values.
    @Test
    void representsEntryWithoutPackedData() {
        SevenZipEntryMetadata metadata = new SevenZipEntryMetadata(
                "empty",
                true,
                0L,
                SevenZipEntryMetadata.NO_DATA_OFFSET,
                0L,
                0L,
                SevenZipCoderMethod.COPY.methodId(),
                new byte[0],
                null,
                null,
                null,
                SevenZipArkivoEntryAttributes.UNKNOWN_WINDOWS_ATTRIBUTES
        );

        assertTrue(metadata.directory());
        assertEquals(SevenZipEntryMetadata.NO_DATA_OFFSET, metadata.dataOffset());
        assertEquals(0L, metadata.packedSize());
        assertEquals(SevenZipEntryMetadata.UNKNOWN_CRC32, metadata.packedCrc32());
        assertEquals(SevenZipArkivoEntryAttributes.NO_SUBSTREAM_INDEX, metadata.substreamIndex());
        assertEquals(0, metadata.substreamCount());
        assertEquals(List.of(), metadata.packedStreams());
        assertFalse(metadata.solid());
        assertNull(metadata.coderGraph());
        assertNull(metadata.creationTime());
        assertNull(metadata.lastAccessTime());
        assertNull(metadata.lastModifiedTime());
    }

    /// Derives aggregate size and a non-addressable packed checksum from a coherent four-stream coder graph.
    @Test
    void derivesMultiplePackedStreamLayout() {
        SevenZipFolderMethod method = SevenZipFolderMethod.graph(
                new byte[][]{SevenZipCoderMethod.BCJ2.methodId()},
                new byte[][]{new byte[0]},
                new int[]{4},
                new int[]{1},
                new int[0],
                new int[0],
                new int[]{0, 1, 2, 3},
                new long[]{100L}
        );
        ArrayList<SevenZipPackedStream> streams = new ArrayList<>(List.of(
                new SevenZipPackedStream(100L, 2L, 1L),
                new SevenZipPackedStream(200L, 3L, 2L),
                new SevenZipPackedStream(300L, 5L, 3L),
                new SevenZipPackedStream(400L, 7L, 4L)
        ));
        SevenZipEntryMetadata metadata = new SevenZipEntryMetadata(
                "bcj2.bin",
                false,
                100L,
                0L,
                0,
                1,
                streams,
                0xffff_ffffL,
                method,
                null,
                null,
                null,
                0
        );
        streams.clear();

        assertEquals(100L, metadata.dataOffset());
        assertEquals(17L, metadata.packedSize());
        assertEquals(SevenZipEntryMetadata.UNKNOWN_CRC32, metadata.packedCrc32());
        assertEquals(0xffff_ffffL, metadata.crc32());
        assertEquals(4, metadata.packedStreams().size());
        var graph = Objects.requireNonNull(metadata.coderGraph());
        assertEquals(SevenZipCoderMethod.BCJ2, graph.coders().get(0).method());
    }

    /// Rejects invalid scalar values and inconsistent no-data state in the public constructor.
    @Test
    void rejectsInvalidSingleStreamLayout() {
        assertThrows(IllegalArgumentException.class, () -> singleStream(-1L, 0L, 0L, 0L));
        assertThrows(IllegalArgumentException.class, () -> singleStream(0L, -2L, 0L, 0L));
        assertThrows(IllegalArgumentException.class, () -> singleStream(0L, 0L, -1L, 0L));
        assertThrows(IllegalArgumentException.class, () -> singleStream(0L, 0L, 0L, -1L));
        assertThrows(
                IllegalArgumentException.class,
                () -> singleStream(0L, SevenZipEntryMetadata.NO_DATA_OFFSET, 0L, 1L)
        );
    }

    /// Rejects invalid substream indexes, CRC values, stream presence, and aggregate-size overflow.
    @Test
    void rejectsInvalidGeneralLayout() {
        SevenZipFolderMethod method = SevenZipFolderMethod.single(new byte[]{0}, new byte[0], 0L);
        List<SevenZipPackedStream> oneStream = List.of(
                new SevenZipPackedStream(0L, 0L, SevenZipPackedStream.UNKNOWN_CRC32)
        );

        assertGeneralLayoutFailure(0L, -2, 0, List.of(), SevenZipEntryMetadata.UNKNOWN_CRC32, method);
        assertGeneralLayoutFailure(0L, -1, -1, List.of(), SevenZipEntryMetadata.UNKNOWN_CRC32, method);
        assertGeneralLayoutFailure(0L, 0, 0, List.of(), SevenZipEntryMetadata.UNKNOWN_CRC32, method);
        assertGeneralLayoutFailure(0L, 2, 2, oneStream, SevenZipEntryMetadata.UNKNOWN_CRC32, method);
        assertGeneralLayoutFailure(0L, 0, 1, List.of(), SevenZipEntryMetadata.UNKNOWN_CRC32, method);
        assertGeneralLayoutFailure(0L, -1, 0, oneStream, SevenZipEntryMetadata.UNKNOWN_CRC32, method);
        assertGeneralLayoutFailure(0L, 0, 1, oneStream, -2L, method);
        assertGeneralLayoutFailure(0L, 0, 1, oneStream, 0x1_0000_0000L, method);

        List<SevenZipPackedStream> overflowing = List.of(
                new SevenZipPackedStream(0L, Long.MAX_VALUE, SevenZipPackedStream.UNKNOWN_CRC32),
                new SevenZipPackedStream(0L, 1L, SevenZipPackedStream.UNKNOWN_CRC32)
        );
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> generalMetadata(0L, 0, 1, overflowing, SevenZipEntryMetadata.UNKNOWN_CRC32, method)
        );
        assertEquals("packed stream sizes are too large", exception.getMessage());
        assertNotNull(exception.getCause());
    }

    /// Creates one public-constructor metadata instance with fixed non-layout values.
    private static SevenZipEntryMetadata singleStream(
            long size,
            long dataOffset,
            long decodedOffset,
            long packedSize
    ) {
        return new SevenZipEntryMetadata(
                "entry",
                false,
                size,
                dataOffset,
                decodedOffset,
                packedSize,
                new byte[]{0},
                new byte[0],
                null,
                null,
                null,
                SevenZipArkivoEntryAttributes.UNKNOWN_WINDOWS_ATTRIBUTES
        );
    }

    /// Verifies that one general-layout construction attempt fails validation.
    private static void assertGeneralLayoutFailure(
            long decodedOffset,
            int substreamIndex,
            int substreamCount,
            List<SevenZipPackedStream> streams,
            long crc32,
            SevenZipFolderMethod method
    ) {
        assertThrows(
                IllegalArgumentException.class,
                () -> generalMetadata(decodedOffset, substreamIndex, substreamCount, streams, crc32, method)
        );
    }

    /// Creates one general-layout metadata instance with fixed non-layout values.
    private static SevenZipEntryMetadata generalMetadata(
            long decodedOffset,
            int substreamIndex,
            int substreamCount,
            List<SevenZipPackedStream> streams,
            long crc32,
            SevenZipFolderMethod method
    ) {
        return new SevenZipEntryMetadata(
                "entry",
                false,
                0L,
                decodedOffset,
                substreamIndex,
                substreamCount,
                streams,
                crc32,
                method,
                null,
                null,
                null,
                SevenZipArkivoEntryAttributes.UNKNOWN_WINDOWS_ATTRIBUTES
        );
    }
}
