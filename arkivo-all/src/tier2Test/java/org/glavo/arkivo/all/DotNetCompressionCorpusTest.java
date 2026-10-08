// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.glavo.arkivo.codec.deflate.DeflateCodec;
import org.glavo.arkivo.codec.deflate.GzipCodec;
import org.glavo.arkivo.codec.deflate.ZlibCodec;
import org.glavo.arkivo.codec.zstd.ZstdCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Decodes .NET-produced compressed documents against their original binary files.
@NotNullByDefault
final class DotNetCompressionCorpusTest {
    /// Original document types retained in the selected corpus.
    private static final @Unmodifiable List<String> DOCUMENTS = List.of("doc", "docx", "pdf", "txt");

    /// One compressed representation of each document.
    ///
    /// @param directory upstream compressed-data directory
    /// @param suffix filename suffix following the original document name
    /// @param codec bounded decoder configuration
    @NotNullByDefault
    private record Format(String directory, String suffix, CompressionCodec<?> codec) {
    }

    /// Independent producer outputs for all supported formats in this selected document corpus.
    private static final @Unmodifiable List<Format> FORMATS = List.of(
            new Format("DeflateTestData", "", DeflateCodec.DEFAULT.withMaximumOutputSize(1 << 20)),
            new Format("GZipTestData", ".gz", GzipCodec.DEFAULT.withMaximumOutputSize(1 << 20)),
            new Format("ZLibTestData", ".z", ZlibCodec.DEFAULT.withMaximumOutputSize(1 << 20)),
            new Format("ZstandardTestData", ".zst", ZstdCodec.DEFAULT.withMaximumOutputSize(1 << 20)));

    /// Supplies bounded fragmentation and each original document representation.
    private static Stream<Arguments> streamCases() {
        return FORMATS.stream().flatMap(format -> DOCUMENTS.stream().flatMap(document -> Stream.of(1, 7, 8192)
                .map(chunk -> Arguments.of(format.directory(), format, document, chunk))));
    }

    /// Supplies heap, direct, and read-only source buffers with nonzero positions.
    private static Stream<Arguments> bufferCases() {
        return FORMATS.stream().flatMap(format -> DOCUMENTS.stream().flatMap(document -> Stream.of(0, 1, 2)
                .map(kind -> Arguments.of(format.directory(), format, document, kind))));
    }

    /// Checks exact corpus size and ensures every retained compressed document has an original.
    @Test
    void accountsForDocuments() throws IOException {
        for (Format format : FORMATS) {
            try (var files = Files.list(root().resolve(format.directory()))) {
                assertEquals(4, files.count());
            }
            for (String document : DOCUMENTS) {
                assertTrue(Files.size(plain(document)) > 0);
                assertTrue(Files.size(compressed(format, document)) > 0);
            }
        }
    }

    /// Reads full document bytes through short reads and an input which reports no available bytes.
    @ParameterizedTest(name = "{0}, {2}, chunk={3}")
    @MethodSource("streamCases")
    void readsFragmentedDocument(String directory, Format format, String document, int chunk) throws IOException {
        assertEquals(directory, format.directory());
        byte[] expected = Files.readAllBytes(plain(document));
        byte[] bytes = Files.readAllBytes(compressed(format, document));
        if (directory.equals("GZipTestData")) {
            int memberLength = gzipMemberLength(bytes, expected);
            if (memberLength < bytes.length) {
                // Three upstream files contain non-member bytes after a complete, valid member.
                try (var raw = new ChunkedInput(bytes, chunk); var input = format.codec().newInputStream(raw)) {
                    assertThrows(IOException.class, input::readAllBytes);
                }
                bytes = Arrays.copyOf(bytes, memberLength);
            }
        }
        try (var raw = new ChunkedInput(bytes, chunk);
             var input = format.codec().newInputStream(raw)) {
            assertArrayEquals(expected, input.readAllBytes());
            assertEquals(-1, input.read());
            assertEquals(-1, input.read());
        }
    }

    /// Verifies binary equality and source/target positions without relying on an Arkivo-produced compressed file.
    @ParameterizedTest(name = "{0}, {2}, buffer={3}")
    @MethodSource("bufferCases")
    void readsBufferDocument(String directory, Format format, String document, int kind) throws IOException {
        assertEquals(directory, format.directory());
        byte[] bytes = Files.readAllBytes(compressed(format, document));
        byte[] expected = Files.readAllBytes(plain(document));
        ByteBuffer source = kind == 1 ? ByteBuffer.allocateDirect(bytes.length + 6) : ByteBuffer.allocate(bytes.length + 6);
        source.position(3).put(bytes).limit(3 + bytes.length).position(3);
        if (kind == 2) source = source.asReadOnlyBuffer();
        ByteBuffer target = ByteBuffer.allocateDirect(expected.length + 8);
        target.position(4).limit(5 + expected.length);
        if (directory.equals("GZipTestData")) {
            try (var decoder = format.codec().newDecoder()) {
                assertEquals(CodecOutcome.FINISHED, decoder.finish(source, target));
            }
            int memberLength = gzipMemberLength(bytes, expected);
            assertEquals(3 + memberLength, source.position());
            byte[] remaining = new byte[source.remaining()];
            source.get(remaining);
            assertArrayEquals(Arrays.copyOfRange(bytes, memberLength, bytes.length), remaining);
        } else {
            format.codec().decompress(source, target);
            assertEquals(source.limit(), source.position());
        }
        assertEquals(4 + expected.length, target.position());
        target.flip().position(4);
        byte[] actual = new byte[target.remaining()];
        target.get(actual);
        assertArrayEquals(expected, actual);
    }

    /// Supplies real GZip members with each trailer field damaged or removed.
    private static Stream<Arguments> damagedMembers() {
        return DOCUMENTS.stream().flatMap(document -> Stream.of("crc", "size", "truncated")
                .flatMap(damage -> Stream.of(1, 8192).map(chunk -> Arguments.of(document, damage, chunk))));
    }

    /// Does not accept a complete plaintext as success when its enclosing real GZip member has an invalid trailer.
    @ParameterizedTest(name = "{0}, {1}, chunk={2}")
    @MethodSource("damagedMembers")
    void rejectsDamagedMember(String document, String damage, int chunk) throws IOException {
        byte[] bytes = Files.readAllBytes(root().resolve("GZipTestData/TestDocument." + document + ".gz"));
        bytes = Arrays.copyOf(bytes, gzipMemberLength(bytes, Files.readAllBytes(plain(document))));
        if (damage.equals("truncated")) bytes = Arrays.copyOf(bytes, bytes.length - 1);
        else bytes[bytes.length - (damage.equals("crc") ? 8 : 4)] ^= 1;
        try (var raw = new ChunkedInput(bytes, chunk); var input = GzipCodec.DEFAULT.withMaximumOutputSize(1 << 20).newInputStream(raw)) {
            assertThrows(IOException.class, input::readAllBytes);
        }
    }

    /// Locates the first member's end with JDK raw inflate, independently checking its body and both trailer fields.
    private static int gzipMemberLength(byte[] bytes, byte[] expected) throws IOException {
        assertEquals(0x00088b1f, ByteArrayAccess.readIntLittleEndian(bytes, 0));
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(bytes, 10, bytes.length - 10);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                assertTrue(count > 0 || inflater.finished());
                output.write(buffer, 0, count);
                assertTrue(output.size() <= expected.length);
            }
            assertArrayEquals(expected, output.toByteArray());
            int trailer = Math.toIntExact(10 + inflater.getBytesRead());
            CRC32 crc = new CRC32();
            crc.update(expected);
            assertEquals(crc.getValue(), Integer.toUnsignedLong(ByteArrayAccess.readIntLittleEndian(bytes, trailer)));
            assertEquals(expected.length, ByteArrayAccess.readIntLittleEndian(bytes, trailer + 4));
            return trailer + 8;
        } catch (DataFormatException exception) {
            throw new IOException("Invalid reference GZip body", exception);
        } finally {
            inflater.end();
        }
    }

    /// Resolves the original binary document.
    private static Path plain(String document) {
        return root().resolve("UncompressedTestFiles/TestDocument." + document);
    }

    /// Resolves one producer's compressed document.
    private static Path compressed(Format format, String document) {
        return root().resolve(format.directory()).resolve("TestDocument." + document + format.suffix());
    }

    /// Resolves the verified .NET assets directory.
    private static Path root() {
        return Path.of(Objects.requireNonNull(System.getProperty("arkivo.dotnet-assets.testDataDirectory")))
                .resolve("src/System.IO.Compression.TestData");
    }

    /// Returns short reads while reporting zero availability, as permitted by the InputStream contract.
    @NotNullByDefault
    private static final class ChunkedInput extends ByteArrayInputStream {
        /// Maximum bytes returned by a bulk read.
        private final int chunk;

        /// Wraps the complete original compressed member.
        private ChunkedInput(byte[] bytes, int chunk) {
            super(bytes);
            this.chunk = chunk;
        }

        /// Restricts bulk reads independently of the destination buffer size.
        @Override
        public synchronized int read(byte[] bytes, int offset, int length) {
            return super.read(bytes, offset, Math.min(chunk, length));
        }

        /// Reports no immediately available bytes without declaring end-of-input.
        @Override
        public synchronized int available() {
            return 0;
        }
    }
}
