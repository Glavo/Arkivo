// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.ArkivoReadLimitException;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.stream.Stream;

import static org.glavo.arkivo.archive.zip.ZipTestArchiveFixtures.createTemporaryArchivePath;
import static org.glavo.arkivo.archive.zip.ZipTestArchiveFixtures.deleteTemporaryArchive;
import static org.glavo.arkivo.archive.zip.ZipTestArchiveFixtures.tamperFirstDataDescriptorCrc;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies writer-produced data descriptors across compressed ZIP methods and encryption schemes.
@NotNullByDefault
public final class ZipStreamingCompressedDescriptorTest {
    /// The ZIP LZMA general-purpose flag indicating an end-of-stream marker.
    private static final int LZMA_EOS_MARKER_FLAG = 1 << 1;

    /// The password shared by encrypted descriptor cases.
    private static final byte @Unmodifiable [] PASSWORD =
            "descriptor secret".getBytes(StandardCharsets.UTF_8);

    /// Combines stored-entry encryption, descriptor widths, optional signatures, and boundary-sized payloads.
    private static Stream<Arguments> storedBoundaries() {
        return Arrays.stream(ZipEncryption.values()).flatMap(encryption -> Stream.of(0, 1, 2).flatMap(layout ->
                Stream.of(false, true).flatMap(signature -> Stream.of(0, 1, 4, 64)
                        .map(size -> Arguments.of(encryption, layout, signature, size)))));
    }

    /// Reads short stored entries without treating authentication, descriptor, or subsequent header bytes as content.
    @ParameterizedTest(name = "{0}, layout={1}, signature={2}, size={3}")
    @MethodSource("storedBoundaries")
    void readsStoredDescriptorBoundaries(ZipEncryption encryption, int layout, boolean signature, int size) throws IOException {
        byte[] content = size == 4 ? new byte[]{(byte) 0xac, 0x0a, 0x7a, (byte) 0xd5} : new byte[size];
        if (size != 4) new Random(47L).nextBytes(content);
        Path path = createTemporaryArchivePath("stored-boundary-");
        var options = ZipArchiveOptions.READ_DEFAULTS.withPasswordProvider(ArkivoPasswordProvider.fixed(PASSWORD));
        try {
            writeDescriptorArchive(path, ZipMethod.STORED, encryption, content, new byte[]{42});
            byte[] archive = withFirstDescriptorLayout(Files.readAllBytes(path), layout != 0, layout == 1, signature);
            Files.write(path, archive);
            try (var fs = ZipArkivoFileSystem.open(path, options)) {
                assertArrayEquals(content, Files.readAllBytes(fs.getPath("/compressed.bin")));
                assertArrayEquals(new byte[]{42}, Files.readAllBytes(fs.getPath("/following.txt")));
            }
            int central = ByteArrayAccess.readIntLittleEndian(archive, archive.length - 6);
            int followingCentral = nextCentralRecord(archive, central);
            int followingOffset = ByteArrayAccess.readIntLittleEndian(archive, followingCentral + 42);
            for (boolean eof : new boolean[]{false, true}) {
                for (int consumption = 0; consumption < 3; consumption++) {
                    byte[] bytes = eof ? Arrays.copyOf(archive, followingOffset) : archive;
                    int chunk = consumption == 0 ? 1 : consumption == 1 ? 7 : 8192;
                    try (var reader = ZipArkivoStreamingReader.open(new FragmentedInput(bytes, chunk), options)) {
                        assertTrue(reader.next());
                        if (consumption != 2) {
                            try (var input = reader.openInputStream()) {
                                assertArrayEquals(consumption == 0 ? content : Arrays.copyOf(content, Math.min(1, size)),
                                        consumption == 0 ? input.readAllBytes() : input.readNBytes(Math.min(1, size)));
                            }
                        }
                        assertEquals(!eof, reader.next());
                        if (!eof) {
                            assertEquals("following.txt", reader.readAttributes(ZipArkivoEntryAttributes.class).path());
                            try (var input = reader.openInputStream()) {
                                assertArrayEquals(new byte[]{42}, input.readAllBytes());
                            }
                            assertFalse(reader.next());
                        }
                    }
                }
            }
        } finally {
            deleteTemporaryArchive(path);
        }
    }

    /// Limits each bulk source read without changing its byte order or end-of-input behavior.
    @NotNullByDefault
    private static final class FragmentedInput extends ByteArrayInputStream {
        /// Maximum bytes returned by one bulk read.
        private final int chunk;

        /// Creates a source with the given positive bulk-read bound.
        private FragmentedInput(byte[] bytes, int chunk) {
            super(bytes);
            this.chunk = chunk;
        }

        @Override
        public synchronized int read(byte[] target, int offset, int length) {
            return super.read(target, offset, Math.min(length, chunk));
        }
    }

    /// Releases every descriptor decoder without consuming more input after its entry-size limit fails.
    @ParameterizedTest(name = "{0} with {1}, AE-2={2}")
    @MethodSource("descriptorConfigurations")
    public void closesRejectedDescriptorWithoutReadingMore(
            ZipMethod method, ZipEncryption encryption, boolean ae2
    ) throws IOException {
        Path archivePath = createTemporaryArchivePath("limited-descriptor-");
        byte[] content = new byte[64 * 1024];
        new Random(31L).nextBytes(content);
        try {
            writeDescriptorArchive(archivePath, method, encryption, content, new byte[]{1, 2, 3});
            byte[] archive = Files.readAllBytes(archivePath);
            ByteArrayInputStream source = new ByteArrayInputStream(ae2 ? withAe2FirstEntry(archive) : archive);
            var common = ArchiveReadOptions.DEFAULT
                    .withLimits(ArchiveReadLimits.builder().maximumEntrySize(8L).build())
                    .withPasswordProvider(ArkivoPasswordProvider.fixed(PASSWORD));
            try (var reader = ZipArkivoStreamingReader.open(source, new ZipArchiveOptions.Read(common))) {
                assertTrue(reader.next());
                var input = reader.openInputStream();
                assertThrows(ArkivoReadLimitException.class, input::readAllBytes);
                int remaining = source.available();
                assertThrows(ArkivoReadLimitException.class, input::close);
                assertEquals(remaining, source.available(), "Rejected entries must not drain on close");
                input.close();
                assertThrows(ArkivoReadLimitException.class, reader::next);
                reader.close();
                assertEquals(remaining, source.available(), "Reader cleanup must not resume decoding");
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Reads a compressed data-descriptor entry and the stored entry immediately following it.
    @ParameterizedTest(name = "{0} with {1}, AE-2={2}, signature={3}")
    @MethodSource("descriptorLayouts")
    public void readsWriterProducedDescriptor(ZipMethod method, ZipEncryption encryption, boolean ae2, boolean signature) throws IOException {
        Path archivePath = createTemporaryArchivePath("compressed-descriptor-");
        byte @Unmodifiable [] content = ("descriptor content for " + method + " and " + encryption + "\n")
                .repeat(128)
                .getBytes(StandardCharsets.UTF_8);
        byte @Unmodifiable [] followingContent = "following stored entry".getBytes(StandardCharsets.UTF_8);

        try {
            writeDescriptorArchive(archivePath, method, encryption, content, followingContent);
            if (ae2) {
                Files.write(archivePath, withAe2FirstEntry(Files.readAllBytes(archivePath)));
            }
            if (!signature) {
                Files.write(archivePath, withoutFirstDescriptorSignature(Files.readAllBytes(archivePath)));
            }

            ZipArchiveOptions.Read readOptions = ZipArchiveOptions.READ_DEFAULTS
                    .withPasswordProvider(ArkivoPasswordProvider.fixed(PASSWORD));
            byte @Unmodifiable [] archive = Files.readAllBytes(archivePath);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    readOptions
            )) {
                assertTrue(reader.next());
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("compressed.bin", attributes.path());
                assertEquals(method, attributes.compressionMethod());
                assertEquals(encryption, attributes.encryption());
                assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, attributes.compressedSize());
                assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, attributes.size());
                if (method == ZipMethod.LZMA) {
                    assertTrue((attributes.generalPurposeFlags() & LZMA_EOS_MARKER_FLAG) != 0);
                }
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(content, input.readAllBytes());
                }

                assertTrue(reader.next());
                ZipArkivoEntryAttributes followingAttributes =
                        reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("following.txt", followingAttributes.path());
                assertEquals(ZipMethod.STORED, followingAttributes.compressionMethod());
                assertEquals(ZipEncryption.NONE, followingAttributes.encryption());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(followingContent, input.readAllBytes());
                }
                assertFalse(reader.next());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Rejects a corrupt descriptor without consuming the following local file record.
    @ParameterizedTest(name = "{0} with {1}, AE-2={2}, signature={3}")
    @MethodSource("descriptorLayouts")
    public void rejectsDescriptorCrcMismatchWithoutLosingFollowingEntry(
            ZipMethod method,
            ZipEncryption encryption,
            boolean ae2,
            boolean signature
    ) throws IOException {
        Path archivePath = createTemporaryArchivePath("corrupt-compressed-descriptor-");
        byte @Unmodifiable [] content = ("corrupt descriptor content for " + method + " and " + encryption + "\n")
                .repeat(128)
                .getBytes(StandardCharsets.UTF_8);
        byte @Unmodifiable [] followingContent = "following stored entry".getBytes(StandardCharsets.UTF_8);

        try {
            writeDescriptorArchive(archivePath, method, encryption, content, followingContent);
            byte[] original = Files.readAllBytes(archivePath);
            byte[] corrupt = tamperFirstDataDescriptorCrc(ae2 ? withAe2FirstEntry(original) : original);
            byte @Unmodifiable [] archive = signature ? corrupt : withoutFirstDescriptorSignature(corrupt);
            ZipArchiveOptions.Read readOptions = ZipArchiveOptions.READ_DEFAULTS
                    .withPasswordProvider(ArkivoPasswordProvider.fixed(PASSWORD));

            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    readOptions
            )) {
                assertTrue(reader.next());
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals(method, attributes.compressionMethod());
                assertEquals(encryption, attributes.encryption());
                var input = reader.openInputStream();
                IOException exception = assertThrows(IOException.class, input::readAllBytes);
                assertTrue(exception.getMessage().contains("data descriptor does not match"));
                input.close();

                assertTrue(reader.next());
                ZipArkivoEntryAttributes followingAttributes =
                        reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("following.txt", followingAttributes.path());
                assertEquals(ZipMethod.STORED, followingAttributes.compressionMethod());
                assertEquals(ZipEncryption.NONE, followingAttributes.encryption());
                try (var followingInput = reader.openInputStream()) {
                    assertArrayEquals(followingContent, followingInput.readAllBytes());
                }
                assertFalse(reader.next());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Requires AE-1 plaintext CRC validation even when header and descriptor CRCs agree with one another.
    @ParameterizedTest
    @EnumSource(value = ZipMethod.class, names = {"STORED", "DEFLATED", "DEFLATE64", "BZIP2", "LZMA", "ZSTANDARD", "XZ"})
    void rejectsAe1BodyCrcMismatch(ZipMethod method) throws IOException {
        Path archivePath = createTemporaryArchivePath("aes-body-crc-");
        byte[] content = "Authenticated ciphertext cannot replace the AE-1 plaintext CRC check."
                .repeat(16).getBytes(StandardCharsets.UTF_8);
        var options = ZipArchiveOptions.READ_DEFAULTS.withPasswordProvider(ArkivoPasswordProvider.fixed(PASSWORD));
        try {
            writeDescriptorArchive(archivePath, method, ZipEncryption.WINZIP_AES_256, content, new byte[]{42});
            try (var fileSystem = ZipArkivoFileSystem.open(archivePath, options)) {
                assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/compressed.bin")));
            }
            byte[] bytes = Files.readAllBytes(archivePath);
            int central = ByteArrayAccess.readIntLittleEndian(bytes, bytes.length - 22 + 16);
            int compressedSize = ByteArrayAccess.readIntLittleEndian(bytes, central + 20);
            int data = 30 + Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(bytes, 26))
                    + Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(bytes, 28));
            int changedCrc = ByteArrayAccess.readIntLittleEndian(bytes, central + 16) ^ 1;
            assertEquals(0x08074b50, ByteArrayAccess.readIntLittleEndian(bytes, data + compressedSize));
            ByteArrayAccess.writeIntLittleEndian(bytes, central + 16, changedCrc);
            ByteArrayAccess.writeIntLittleEndian(bytes, data + compressedSize + 4, changedCrc);
            Files.write(archivePath, bytes);
            try (var fileSystem = ZipArkivoFileSystem.open(archivePath, options)) {
                IOException failure = assertThrows(IOException.class,
                        () -> Files.readAllBytes(fileSystem.getPath("/compressed.bin")));
                assertEquals("ZIP entry data does not match central directory", failure.getMessage());
            }

            // Give the streaming reader explicit sizes, exercising its non-descriptor validation path.
            short flags = ByteArrayAccess.readShortLittleEndian(bytes, 6);
            ByteArrayAccess.writeShortLittleEndian(bytes, 6, (short) (flags & ~(1 << 3)));
            ByteArrayAccess.writeIntLittleEndian(bytes, 14, changedCrc);
            ByteArrayAccess.writeIntLittleEndian(bytes, 18, compressedSize);
            ByteArrayAccess.writeIntLittleEndian(bytes, 22, content.length);
            try (var reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(bytes), options)) {
                assertTrue(reader.next());
                IOException failure = assertThrows(IOException.class, () -> {
                    try (var input = reader.openInputStream()) {
                        input.readAllBytes();
                    }
                });
                assertEquals("ZIP entry data does not match local header", failure.getMessage());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Exercises optional signatures with the same methods and encryption variants as signed descriptors.
    private static Stream<Arguments> descriptorLayouts() {
        return descriptorConfigurations().flatMap(configuration -> Stream.of(false, true).map(signature -> {
            Object[] arguments = Arrays.copyOf(configuration.get(), 4);
            arguments[3] = signature;
            return Arguments.of(arguments);
        }));
    }

    /// Removes the first optional descriptor signature and adjusts the following entry and directory offsets.
    private static byte[] withoutFirstDescriptorSignature(byte[] original) {
        return withFirstDescriptorLayout(original, false, false, false);
    }

    /// Changes only the first local ZIP64 declaration and descriptor representation, then adjusts directory offsets.
    private static byte[] withFirstDescriptorLayout(byte[] original, boolean declared64, boolean actual64, boolean signature) {
        byte[] archive = original.clone();
        int central = ByteArrayAccess.readIntLittleEndian(archive, archive.length - 6);
        int extraSize = Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(archive, 28));
        int data = 30 + Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(archive, 26)) + extraSize;
        int compressedSize = ByteArrayAccess.readIntLittleEndian(archive, central + 20);
        int uncompressedSize = ByteArrayAccess.readIntLittleEndian(archive, central + 24);
        int descriptor = data + compressedSize;
        assertEquals(0x08074b50, ByteArrayAccess.readIntLittleEndian(archive, descriptor));
        byte[] extra = new byte[declared64 ? 20 : 0];
        if (declared64) {
            ByteArrayAccess.writeShortLittleEndian(extra, 0, (short) 1);
            ByteArrayAccess.writeShortLittleEndian(extra, 2, (short) 16);
            ByteArrayAccess.writeShortLittleEndian(archive, 4, (short) 45);
            ByteArrayAccess.writeIntLittleEndian(archive, 18, -1);
            ByteArrayAccess.writeIntLittleEndian(archive, 22, -1);
            ByteArrayAccess.writeShortLittleEndian(archive, 28, (short) (extraSize + extra.length));
        }
        byte[] fields = new byte[(actual64 ? 20 : 12) + (signature ? 4 : 0)];
        int offset = signature ? 4 : 0;
        if (signature) ByteArrayAccess.writeIntLittleEndian(fields, 0, 0x08074b50);
        ByteArrayAccess.writeIntLittleEndian(fields, offset, ByteArrayAccess.readIntLittleEndian(archive, descriptor + 4));
        if (actual64) {
            ByteArrayAccess.writeLongLittleEndian(fields, offset + 4, compressedSize);
            ByteArrayAccess.writeLongLittleEndian(fields, offset + 12, uncompressedSize);
        } else {
            ByteArrayAccess.writeIntLittleEndian(fields, offset + 4, compressedSize);
            ByteArrayAccess.writeIntLittleEndian(fields, offset + 8, uncompressedSize);
        }
        int delta = extra.length + fields.length - 16;
        int following = nextCentralRecord(archive, central);
        ByteArrayAccess.writeIntLittleEndian(archive, following + 42,
                ByteArrayAccess.readIntLittleEndian(archive, following + 42) + delta);
        ByteArrayAccess.writeIntLittleEndian(archive, archive.length - 6, central + delta);
        var output = new ByteArrayOutputStream(archive.length + delta);
        output.write(archive, 0, data);
        output.writeBytes(extra);
        output.write(archive, data, compressedSize);
        output.writeBytes(fields);
        output.write(archive, descriptor + 16, archive.length - descriptor - 16);
        return output.toByteArray();
    }

    /// Returns the central directory record following a complete record in these two-entry fixtures.
    private static int nextCentralRecord(byte[] archive, int central) {
        return central + 46 + Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(archive, central + 28))
                + Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(archive, central + 30))
                + Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(archive, central + 32));
    }

    /// Returns the complete compressed-method and encryption cross-product supported by streaming descriptors.
    private static Stream<Arguments> descriptorConfigurations() {
        return Stream.of(ZipMethod.STORED, ZipMethod.DEFLATED, ZipMethod.DEFLATE64,
                        ZipMethod.BZIP2, ZipMethod.LZMA, ZipMethod.ZSTANDARD, ZipMethod.XZ)
                .flatMap(method -> Stream.of(
                        ZipEncryption.NONE,
                        ZipEncryption.ZIP_CRYPTO,
                        ZipEncryption.WINZIP_AES_256
                ).flatMap(encryption -> (encryption == ZipEncryption.WINZIP_AES_256
                        ? Stream.of(false, true) : Stream.of(false))
                        .map(ae2 -> Arguments.of(method, encryption, ae2))));
    }

    /// Converts the first writer-generated AE-1 entry to AE-2 without changing its ciphertext or authentication code.
    private static byte[] withAe2FirstEntry(byte[] archive) {
        int central = ByteArrayAccess.readIntLittleEndian(archive, archive.length - 22 + 16);
        assertEquals(0x02014b50, ByteArrayAccess.readIntLittleEndian(archive, central));
        int data = 30 + Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(archive, 26))
                + Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(archive, 28));
        int descriptor = data + ByteArrayAccess.readIntLittleEndian(archive, central + 20);
        assertEquals(0x08074b50, ByteArrayAccess.readIntLittleEndian(archive, descriptor));
        ByteArrayAccess.writeIntLittleEndian(archive, 14, 0);
        ByteArrayAccess.writeIntLittleEndian(archive, central + 16, 0);
        ByteArrayAccess.writeIntLittleEndian(archive, descriptor + 4, 0);
        for (int header : new int[]{0, central}) {
            int fixedSize = header == 0 ? 30 : 46;
            int nameSize = Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(archive, header + (header == 0 ? 26 : 28)));
            int extraSize = Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(archive, header + (header == 0 ? 28 : 30)));
            int start = header + fixedSize + nameSize;
            boolean found = false;
            for (int offset = start; offset < start + extraSize;
                 offset += 4 + Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(archive, offset + 2))) {
                if (Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(archive, offset)) == 0x9901) {
                    assertEquals(1, ByteArrayAccess.readShortLittleEndian(archive, offset + 4));
                    ByteArrayAccess.writeShortLittleEndian(archive, offset + 4, (short) 2);
                    found = true;
                    break;
                }
            }
            assertTrue(found);
        }
        return archive;
    }

    /// Writes one compressed descriptor entry followed by an unencrypted stored entry.
    private static void writeDescriptorArchive(
            Path archivePath,
            ZipMethod method,
            ZipEncryption encryption,
            byte @Unmodifiable [] content,
            byte @Unmodifiable [] followingContent
    ) throws IOException {
        ZipArchiveOptions.Create createOptions = ZipArchiveOptions.CREATE_DEFAULTS
                .withPasswordProvider(ArkivoPasswordProvider.fixed(PASSWORD));
        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath, createOptions)) {
            ZipArkivoStreamingWriter.Entry compressedEntry = writer.beginFile("compressed.bin");
            ZipArkivoEntryAttributeView compressedView =
                    compressedEntry.attributeView(ZipArkivoEntryAttributeView.class);
            assertNotNull(compressedView);
            compressedView.setMethod(method);
            compressedView.setEncryption(encryption);
            try (var output = compressedEntry.openOutputStream()) {
                output.write(content);
            }

            ZipArkivoStreamingWriter.Entry followingEntry = writer.beginFile("following.txt");
            ZipArkivoEntryAttributeView followingView =
                    followingEntry.attributeView(ZipArkivoEntryAttributeView.class);
            assertNotNull(followingView);
            followingView.setMethod(ZipMethod.STORED);
            try (var output = followingEntry.openOutputStream()) {
                output.write(followingContent);
            }
        }
    }
}
