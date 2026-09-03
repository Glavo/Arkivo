// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies conventional numbered 7z path validation and split-layout discovery.
@NotNullByDefault
final class SevenZipSplitVolumePathsTest {
    /// The fixed 7z signature used by synthetic first-volume headers.
    private static final byte @Unmodifiable [] SIGNATURE =
            new byte[]{'7', 'z', (byte) 0xbc, (byte) 0xaf, 0x27, 0x1c};

    /// Temporary directory containing synthetic split volumes.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies suffix width, numbering, and first-volume validation.
    @Test
    void constructsAndValidatesConventionalVolumePaths() {
        Path first = temporaryDirectory.resolve("sample.7z.0001");

        assertDoesNotThrow(() -> SevenZipSplitVolumePaths.requireFirstVolumePath(first));
        assertEquals("sample.7z.", SevenZipSplitVolumePaths.volumeFileNamePrefix(first));
        assertEquals(4, SevenZipSplitVolumePaths.volumeSuffixWidth(first));
        assertEquals(
                temporaryDirectory.resolve("sample.7z.0002"),
                SevenZipSplitVolumePaths.numberedVolumePath(first, 2)
        );
        assertEquals(
                temporaryDirectory.resolve("sample.7z.0012"),
                SevenZipSplitVolumePaths.numberedVolumePath(first, 12)
        );
        assertThrows(IllegalArgumentException.class, () -> SevenZipSplitVolumePaths.numberedVolumePath(first, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipSplitVolumePaths.requireFirstVolumePath(temporaryDirectory.resolve("sample.7z.002"))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipSplitVolumePaths.requireFirstVolumePath(temporaryDirectory.resolve("sample.7z.01"))
        );
    }

    /// Verifies conventional suffixes contain ASCII digits rather than visually similar Unicode digits.
    @Test
    void rejectsNonAsciiDigitSuffixes() {
        Path fullwidthDigits = temporaryDirectory.resolve("sample.7z.００１");
        Path arabicIndicDigits = temporaryDirectory.resolve("sample.7z.٠٠١");

        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipSplitVolumePaths.requireFirstVolumePath(fullwidthDigits)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipSplitVolumePaths.requireFirstVolumePath(arabicIndicDigits)
        );
    }

    /// Verifies discovery returns the contiguous existing prefix and an immutable result.
    @Test
    void discoversContiguousVolumesAndStopsAtFirstGap() throws IOException {
        Path first = temporaryDirectory.resolve("sample.7z.001");
        Path second = temporaryDirectory.resolve("sample.7z.002");
        Path third = temporaryDirectory.resolve("sample.7z.003");
        Path fifth = temporaryDirectory.resolve("sample.7z.005");
        Files.write(first, new byte[]{1, 2, 3});
        Files.write(second, new byte[]{4});
        Files.write(third, new byte[]{5});
        Files.write(fifth, new byte[]{6});

        List<Path> paths = Objects.requireNonNull(SevenZipSplitVolumePaths.discover(first));
        assertEquals(List.of(first, second, third), paths);
        assertThrows(UnsupportedOperationException.class, () -> paths.add(fifth));
    }

    /// Verifies an absent first or second volume does not establish a split layout.
    @Test
    void returnsNullWithoutAnEstablishedSplitLayout() throws IOException {
        Path first = temporaryDirectory.resolve("missing.7z.001");
        assertNull(SevenZipSplitVolumePaths.discover(first));

        Files.write(first, new byte[]{1});
        assertNull(SevenZipSplitVolumePaths.discover(first));
        assertNull(SevenZipSplitVolumePaths.discover(temporaryDirectory.resolve("missing.7z")));
    }

    /// Verifies a complete archive in the first numbered path takes precedence over stale following volumes.
    @Test
    void ignoresFollowingVolumesWhenFirstVolumeContainsCompleteArchive() throws IOException {
        Path first = temporaryDirectory.resolve("complete.7z.001");
        Path second = temporaryDirectory.resolve("complete.7z.002");
        Files.write(first, signatureHeader(2L, 3L, 5));
        Files.write(second, new byte[]{9, 8, 7});

        assertNull(SevenZipSplitVolumePaths.discover(first));
    }

    /// Verifies overflowing next-header bounds classify the first volume as an incomplete archive fragment.
    @Test
    void discoversSplitLayoutWhenFirstHeaderBoundsOverflow() throws IOException {
        Path first = temporaryDirectory.resolve("overflow.7z.001");
        Path second = temporaryDirectory.resolve("overflow.7z.002");
        Files.write(first, signatureHeader(Long.MAX_VALUE, 1L, 0));
        Files.write(second, new byte[]{1});

        assertEquals(List.of(first, second), SevenZipSplitVolumePaths.discover(first));
    }

    /// Creates a synthetic 7z signature header followed by the requested number of payload bytes.
    private static byte[] signatureHeader(long nextHeaderOffset, long nextHeaderSize, int trailingByteCount) {
        ByteBuffer buffer = ByteBuffer.allocate(SevenZipSignatureHeader.SIZE + trailingByteCount)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(SIGNATURE);
        buffer.put((byte) 0);
        buffer.put((byte) 4);
        buffer.putInt(0);
        buffer.putLong(nextHeaderOffset);
        buffer.putLong(nextHeaderSize);
        buffer.putInt(0);
        return buffer.array();
    }
}
