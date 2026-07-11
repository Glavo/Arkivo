// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.ArkivoFormat;
import org.glavo.arkivo.ArkivoFormats;
import org.glavo.arkivo.compress.CompressionCodec;
import org.glavo.arkivo.compress.CompressionCodecs;
import org.glavo.arkivo.tar.TarArkivoStreamingWriter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies runtime discovery through the all-in-one aggregate module.
@NotNullByDefault
final class AllAggregationTest {
    /// Verifies that all aggregated archive and codec providers are visible.
    @Test
    void discoversAllAggregatedProviders() {
        Set<String> archiveNames = ArkivoFormats.installed()
                .stream()
                .map(ArkivoFormat::name)
                .collect(Collectors.toUnmodifiableSet());
        Set<String> codecNames = CompressionCodecs.installed()
                .stream()
                .map(CompressionCodec::name)
                .collect(Collectors.toUnmodifiableSet());

        assertEquals(Set.of("7z", "ar", "rar", "tar", "zip"), archiveNames);
        assertEquals(Set.of("bzip2", "deflate", "gzip", "lzma", "xz", "zlib", "zstd"), codecNames);
    }

    /// Verifies unified signature detection and archive format descriptors.
    @Test
    void detectsAllAggregatedArchiveFormats() throws IOException {
        assertDetected("zip", new byte[]{'P', 'K', 3, 4});
        assertDetected("7z", new byte[]{'7', 'z', (byte) 0xbc, (byte) 0xaf, 0x27, 0x1c});
        assertDetected("rar", new byte[]{'R', 'a', 'r', '!', 0x1a, 0x07, 0x01, 0x00});
        assertDetected("ar", "!<arch>\n".getBytes(StandardCharsets.US_ASCII));
        assertDetected("tar", tarArchive());
        assertDetected("tar", new byte[1024]);

        assertEquals("7z", requireFormat("SeVeNzIp").name());
        assertEquals(List.of("zip", "jar"), requireFormat("zip").fileExtensions());
        assertEquals(List.of("tar"), requireFormat("tar").fileExtensions());
        assertEquals(List.of("7z"), requireFormat("7z").fileExtensions());
        assertEquals(List.of("rar"), requireFormat("rar").fileExtensions());
        assertEquals(List.of("a", "ar", "deb"), requireFormat("ar").fileExtensions());

        byte[] signature = new byte[]{'P', 'K', 5, 6};
        ByteBuffer prefix = ByteBuffer.allocate(signature.length + 2).order(ByteOrder.LITTLE_ENDIAN);
        prefix.position(1);
        prefix.put(signature);
        prefix.flip();
        prefix.position(1);
        prefix.mark();

        assertEquals("zip", requireDetected(ArkivoFormats.detect(prefix)).name());
        assertEquals(1, prefix.position());
        assertEquals(ByteOrder.LITTLE_ENDIAN, prefix.order());
        prefix.reset();
        assertEquals(1, prefix.position());
        assertNull(ArkivoFormats.detect(ByteBuffer.wrap(new byte[]{1, 2, 3, 4})));
    }

    /// Verifies path detection and position restoration for seekable channels.
    @Test
    void detectsPathsAndRestoresChannelPositions() throws IOException {
        byte[] archive = tarArchive();
        Path path = Files.createTempFile("arkivo-format-probe-", ".bin");
        try {
            Files.write(path, archive);
            assertEquals("tar", requireDetected(ArkivoFormats.detect(path)).name());

            int archiveOffset = 3;
            byte[] embedded = new byte[archiveOffset + archive.length];
            System.arraycopy(archive, 0, embedded, archiveOffset, archive.length);
            Files.write(path, embedded);
            try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
                channel.position(archiveOffset);
                assertEquals("tar", requireDetected(ArkivoFormats.detect(channel)).name());
                assertEquals(archiveOffset, channel.position());
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }

    /// Verifies TAR checksum rejection and channel restoration after a no-progress failure.
    @Test
    void rejectsInvalidTarChecksumsAndRestoresFailedProbes() throws IOException {
        byte[] invalidTar = tarArchive();
        invalidTar[0] ^= 1;
        assertNull(ArkivoFormats.detect(ByteBuffer.wrap(invalidTar)));
        assertNull(ArkivoFormats.detect(ByteBuffer.wrap(new byte[512])));

        try (ZeroReadSeekableByteChannel channel = new ZeroReadSeekableByteChannel()) {
            channel.position(7L);
            IOException exception = assertThrows(IOException.class, () -> ArkivoFormats.detect(channel));
            assertEquals("Archive format probe made no progress", exception.getMessage());
            assertEquals(7L, channel.position());
        }
    }

    /// Verifies ByteBuffer round trips through every aggregated compression codec.
    @Test
    void roundTripsBuffersThroughAllAggregatedCodecs() throws IOException {
        byte[] expected = "Arkivo ByteBuffer codec round trip\n"
                .repeat(256)
                .getBytes(StandardCharsets.UTF_8);

        for (CompressionCodec codec : CompressionCodecs.installed()) {
            assertTrue(codec.canCompressBuffers(), codec.name());
            assertTrue(codec.canDecompressBuffers(), codec.name());

            ByteBuffer source = ByteBuffer.allocateDirect(expected.length + 4);
            source.position(2);
            source.put(expected);
            source.flip();
            source.position(2);

            ByteBuffer compressed = ByteBuffer.allocateDirect(expected.length * 2 + 4096);
            int compressedStart = 3;
            compressed.position(compressedStart);
            codec.compress(source, compressed);
            assertFalse(source.hasRemaining(), codec.name());

            compressed.limit(compressed.position());
            compressed.position(compressedStart);
            ByteBuffer decoded = ByteBuffer.allocateDirect(expected.length + 4);
            int decodedStart = 2;
            decoded.position(decodedStart);
            decoded.limit(decodedStart + expected.length);
            codec.decompress(compressed, decoded);

            assertFalse(compressed.hasRemaining(), codec.name());
            assertEquals(decoded.limit(), decoded.position(), codec.name());
            assertArrayEquals(expected, bufferBytes(decoded, decodedStart, decoded.position()), codec.name());
        }
    }

    /// Returns bytes from the given absolute buffer range without changing its state.
    private static byte[] bufferBytes(ByteBuffer buffer, int start, int end) {
        ByteBuffer view = buffer.duplicate();
        view.position(start);
        view.limit(end);
        byte[] bytes = new byte[view.remaining()];
        view.get(bytes);
        return bytes;
    }

    /// Creates a small TAR archive with a checksummed regular-file header.
    private static byte[] tarArchive() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(output)) {
            writer.beginFile("value.txt");
            try (OutputStream body = writer.openOutputStream()) {
                body.write("value".getBytes(StandardCharsets.UTF_8));
            }
        }
        return output.toByteArray();
    }

    /// Asserts that a prefix is detected as the expected installed format.
    private static void assertDetected(String expectedName, byte[] prefix) {
        assertEquals(expectedName, requireDetected(ArkivoFormats.detect(ByteBuffer.wrap(prefix))).name());
    }

    /// Returns the installed format with the given name or alias.
    private static ArkivoFormat requireFormat(String name) {
        @Nullable ArkivoFormat format = ArkivoFormats.find(name);
        assertNotNull(format);
        return format;
    }

    /// Returns a detected format after asserting that detection succeeded.
    private static ArkivoFormat requireDetected(@Nullable ArkivoFormat format) {
        assertNotNull(format);
        return format;
    }

    /// Implements a seekable channel whose reads never make progress.
    @NotNullByDefault
    private static final class ZeroReadSeekableByteChannel implements SeekableByteChannel {
        /// The current channel position.
        private long position;

        /// Whether this channel is open.
        private boolean open = true;

        /// Creates a zero-read channel.
        private ZeroReadSeekableByteChannel() {
        }

        /// Reports no read progress.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            ensureOpen();
            return 0;
        }

        /// Rejects writes.
        @Override
        public int write(ByteBuffer source) throws IOException {
            ensureOpen();
            throw new NonWritableChannelException();
        }

        /// Returns the current position.
        @Override
        public long position() throws IOException {
            ensureOpen();
            return position;
        }

        /// Sets the current position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            ensureOpen();
            if (newPosition < 0L) {
                throw new IllegalArgumentException("newPosition must not be negative");
            }
            position = newPosition;
            return this;
        }

        /// Returns a synthetic channel size.
        @Override
        public long size() throws IOException {
            ensureOpen();
            return 1024L;
        }

        /// Rejects truncation.
        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            ensureOpen();
            throw new NonWritableChannelException();
        }

        /// Returns whether this channel is open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this channel.
        @Override
        public void close() {
            open = false;
        }

        /// Requires this channel to be open.
        private void ensureOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }
}
