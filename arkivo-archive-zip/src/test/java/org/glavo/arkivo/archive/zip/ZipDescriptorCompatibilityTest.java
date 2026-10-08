// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.internal.ReadOnlyByteArrayChannel;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Exercises optional descriptor signatures and ignored local size fields across ZIP32 and ZIP64 layouts.
///
/// The regression scenarios include OpenJDK issues JDK-8056934 and JDK-8321396. Archives are generated
/// independently at runtime; central-directory offsets remain valid after changing local records.
@NotNullByDefault
final class ZipDescriptorCompatibilityTest {
    /// The first entry's data, with enough repetition to separate compressed and uncompressed sizes.
    private static final byte @Unmodifiable [] CONTENT =
            "descriptor boundary\n".repeat(257).getBytes(StandardCharsets.UTF_8);

    /// The next entry's data, used to detect descriptor over-read.
    private static final byte @Unmodifiable [] FOLLOWING = {7, 0, 3, 1, 9};

    /// Combines descriptor widths, signatures, stale header values, short reads, and entry consumption modes.
    private static Stream<Arguments> configurations() {
        return layouts().flatMap(layout -> Stream.of(0, 42, 0x7ffffffe).flatMap(placeholder ->
                Stream.of(1, 7, 8192).flatMap(chunk -> Stream.of(0, 1, 2).map(consumption ->
                        Arguments.of(layout[0], layout[1], placeholder, chunk, consumption)))));
    }

    /// Supplies each descriptor width with and without the optional signature.
    private static Stream<boolean @Unmodifiable []> layouts() {
        return Stream.of(new boolean[]{false, false}, new boolean[]{false, true},
                new boolean[]{true, false}, new boolean[]{true, true});
    }

    /// Reads complete entries, partially closed entries, and entries skipped without opening a stream.
    @ParameterizedTest(name = "zip64={0}, signature={1}, placeholder={2}, chunk={3}, consumption={4}")
    @MethodSource("configurations")
    void readsDescriptors(boolean zip64, boolean signature, int placeholder, int chunk, int consumption)
            throws IOException {
        Fixture fixture = fixture(zip64, signature, placeholder);
        try (var reader = ZipArkivoStreamingReader.open(new FragmentedInput(fixture.archive(), chunk))) {
            assertTrue(reader.next());
            var snapshot = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("payload", snapshot.path());
            assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, snapshot.size());
            assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, snapshot.compressedSize());
            assertEquals(ZipArkivoEntryAttributes.UNKNOWN_CRC32, snapshot.crc32());
            if (consumption != 2) {
                try (var input = reader.openInputStream()) {
                    if (consumption == 0) {
                        assertArrayEquals(CONTENT, input.readAllBytes());
                        assertEquals(-1, input.read());
                    } else {
                        assertArrayEquals(Arrays.copyOf(CONTENT, 3), input.readNBytes(3));
                    }
                }
            }
            assertTrue(reader.next());
            assertEquals("following", reader.readAttributes(ZipArkivoEntryAttributes.class).path());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(FOLLOWING, input.readAllBytes());
            }
            assertFalse(reader.next());
            assertFalse(reader.next());
            // Attributes already returned to the caller remain snapshots, not mutable completion results.
            assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, snapshot.size());
        }
        try (var fileSystem = ZipArkivoFileSystem.open(new ReadOnlyByteArrayChannel(fixture.archive()))) {
            assertArrayEquals(FOLLOWING, Files.readAllBytes(fileSystem.getPath("/following")));
            assertArrayEquals(CONTENT, Files.readAllBytes(fileSystem.getPath("/payload")));
        }
        // JDK streaming ZIP64 selection depends on actual sizes, so use it as the ZIP32 reference only.
        if (!zip64) {
            try (var input = new ZipInputStream(new FragmentedInput(fixture.archive(), chunk))) {
                assertEquals("payload", input.getNextEntry().getName());
                assertArrayEquals(CONTENT, input.readAllBytes());
                assertEquals("following", input.getNextEntry().getName());
                assertArrayEquals(FOLLOWING, input.readAllBytes());
                assertNull(input.getNextEntry());
            }
        }
    }

    /// Supplies CRC and size words, including ZIP64 high words, for each consumption mode.
    private static Stream<Arguments> corruptions() {
        return layouts().flatMap(layout -> IntStream.range(0, layout[0] ? 5 : 3).boxed()
                .flatMap(word -> Stream.of(0, 1, 2).map(consumption ->
                        Arguments.of(layout[0], layout[1], word, consumption))));
    }

    /// Rejects incorrect descriptor fields even though the central directory still contains correct values.
    @ParameterizedTest(name = "zip64={0}, signature={1}, word={2}, consumption={3}")
    @MethodSource("corruptions")
    void rejectsCorruptDescriptor(boolean zip64, boolean signature, int word, int consumption) throws IOException {
        Fixture fixture = fixture(zip64, signature, 42);
        byte[] corrupt = fixture.archive().clone();
        int offset = fixture.descriptorOffset() + (signature ? 4 : 0);
        offset += word * 4;
        corrupt[offset] ^= 0x40;
        assertThrows(IOException.class, () -> {
            try (var reader = ZipArkivoStreamingReader.open(new FragmentedInput(corrupt, 1))) {
                assertTrue(reader.next());
                if (consumption == 2) {
                    reader.next();
                } else {
                    try (var input = reader.openInputStream()) {
                        if (consumption == 0) {
                            input.readAllBytes();
                        } else {
                            assertArrayEquals(Arrays.copyOf(CONTENT, 3), input.readNBytes(3));
                        }
                    }
                }
            }
        });
        assertThrows(IOException.class, () -> {
            try (var fileSystem = ZipArkivoFileSystem.open(new ReadOnlyByteArrayChannel(corrupt))) {
                Files.readAllBytes(fileSystem.getPath("/payload"));
            }
        });
    }

    /// Supplies every incomplete descriptor prefix, including a completely absent descriptor.
    private static Stream<Arguments> truncations() {
        return layouts().flatMap(layout -> IntStream.range(
                0, (layout[0] ? 20 : 12) + (layout[1] ? 4 : 0)
        ).mapToObj(length -> Arguments.of(layout[0], layout[1], length)));
    }

    /// Requires a complete descriptor even when all compressed bytes have already been decoded.
    @ParameterizedTest(name = "zip64={0}, signature={1}, descriptor prefix={2}")
    @MethodSource("truncations")
    void rejectsTruncatedDescriptor(boolean zip64, boolean signature, int length) throws IOException {
        Fixture fixture = fixture(zip64, signature, 0);
        byte[] truncated = Arrays.copyOf(fixture.archive(), fixture.descriptorOffset() + length);
        assertThrows(IOException.class, () -> {
            try (var reader = ZipArkivoStreamingReader.open(new FragmentedInput(truncated, 7))) {
                assertTrue(reader.next());
                try (var input = reader.openInputStream()) {
                    input.readAllBytes();
                }
            }
        });
    }

    /// Builds two JDK-produced entries and replaces only the first local header and descriptor layout.
    private static Fixture fixture(boolean zip64, boolean signature, int placeholder) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            ZipEntry payload = new ZipEntry("payload");
            payload.setTimeLocal(LocalDateTime.of(2000, 1, 1, 0, 0));
            zip.putNextEntry(payload);
            zip.write(CONTENT);
            zip.closeEntry();
            ZipEntry following = new ZipEntry("following");
            following.setTimeLocal(LocalDateTime.of(2000, 1, 1, 0, 0));
            zip.putNextEntry(following);
            zip.write(FOLLOWING);
        }
        byte[] original = output.toByteArray();
        int central = ByteArrayAccess.readIntLittleEndian(original, original.length - 6);
        int compressedSize = ByteArrayAccess.readIntLittleEndian(original, central + 20);
        int nameLength = Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(original, 26));
        int extraLength = Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(original, 28));
        int data = 30 + nameLength + extraLength;
        int descriptor = data + compressedSize;
        assertEquals(0x08074b50, ByteArrayAccess.readIntLittleEndian(original, descriptor));
        byte[] local = Arrays.copyOf(original, data);
        ByteArrayAccess.writeIntLittleEndian(local, 14, placeholder);
        ByteArrayAccess.writeIntLittleEndian(local, 18, placeholder);
        ByteArrayAccess.writeIntLittleEndian(local, 22, placeholder);
        var rebuilt = new ByteArrayOutputStream();
        if (zip64) {
            ByteArrayAccess.writeShortLittleEndian(local, 4, (short) 45);
            ByteArrayAccess.writeShortLittleEndian(local, 28, (short) (extraLength + 20));
        }
        rebuilt.writeBytes(local);
        if (zip64) {
            byte[] extra = new byte[20];
            ByteArrayAccess.writeShortLittleEndian(extra, 0, (short) 1);
            ByteArrayAccess.writeShortLittleEndian(extra, 2, (short) 16);
            rebuilt.writeBytes(extra);
        }
        rebuilt.write(original, data, compressedSize);
        int newDescriptor = rebuilt.size();
        byte[] fields = new byte[(signature ? 4 : 0) + (zip64 ? 20 : 12)];
        int fieldOffset = signature ? 4 : 0;
        if (signature) {
            ByteArrayAccess.writeIntLittleEndian(fields, 0, 0x08074b50);
        }
        ByteArrayAccess.writeIntLittleEndian(fields, fieldOffset,
                ByteArrayAccess.readIntLittleEndian(original, descriptor + 4));
        if (zip64) {
            ByteArrayAccess.writeLongLittleEndian(fields, fieldOffset + 4, compressedSize);
            ByteArrayAccess.writeLongLittleEndian(fields, fieldOffset + 12, CONTENT.length);
        } else {
            ByteArrayAccess.writeIntLittleEndian(fields, fieldOffset + 4, compressedSize);
            ByteArrayAccess.writeIntLittleEndian(fields, fieldOffset + 8, CONTENT.length);
        }
        rebuilt.writeBytes(fields);
        rebuilt.write(original, descriptor + 16, original.length - descriptor - 16);
        byte[] archive = rebuilt.toByteArray();
        int delta = archive.length - original.length;
        int nextCentral = central + 46
                + Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(original, central + 28))
                + Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(original, central + 30))
                + Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(original, central + 32));
        ByteArrayAccess.writeIntLittleEndian(archive, nextCentral + delta + 42,
                ByteArrayAccess.readIntLittleEndian(original, nextCentral + 42) + delta);
        ByteArrayAccess.writeIntLittleEndian(archive, archive.length - 6, central + delta);
        return new Fixture(archive, newDescriptor);
    }

    /// Holds generated bytes and the first descriptor's position for targeted mutations.
    ///
    /// @param archive the complete two-entry ZIP
    /// @param descriptorOffset the first descriptor's byte offset
    @NotNullByDefault
    private record Fixture(byte @Unmodifiable [] archive, int descriptorOffset) {
    }

    /// Limits each bulk source read without changing single-byte or end-of-input behavior.
    @NotNullByDefault
    private static final class FragmentedInput extends ByteArrayInputStream {
        /// The maximum number of bytes returned by a bulk read.
        private final int chunk;

        /// Creates a source over the supplied archive bytes.
        private FragmentedInput(byte @Unmodifiable [] bytes, int chunk) {
            super(bytes);
            this.chunk = chunk;
        }

        @Override
        public synchronized int read(byte[] target, int offset, int length) {
            return super.read(target, offset, Math.min(length, chunk));
        }
    }
}
