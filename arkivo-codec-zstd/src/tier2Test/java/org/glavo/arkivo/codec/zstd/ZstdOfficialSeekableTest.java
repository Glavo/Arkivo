// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import com.github.luben.zstd.Zstd;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.SeekableEncodingOptions;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Adapts Zstandard 1.5.7's seekable_tests.c and finite seekable_roundtrip.c parameter cases.
///
/// The upstream out-of-range decompression errors map to Channel EOF for a valid empty index. A malformed
/// extent is rejected while loading the index. Native frames independently verify the writer's table entries.
@NotNullByDefault
@Timeout(30)
final class ZstdOfficialSeekableTest {
    /// Holds generated seekable encodings; no binary fixtures are stored in the repository.
    @TempDir
    Path directory;

    /// Checks the upstream 4000-byte single-frame round trip and each exposed seek-table accessor.
    @Test
    void simpleRoundTrip() throws IOException {
        byte[] plain = new byte[4000];
        new Random(2335).nextBytes(plain);
        Path path = encode(plain, 4000, 9, false);
        try (var source = Files.newByteChannel(path)) {
            var index = ZstdCodec.DEFAULT.readIndex(source);
            assertNotNull(index);
            assertEquals(1, index.frameCount());
            assertEquals(0, index.frameCompressedOffset(0));
            assertEquals(0, index.frameUncompressedOffset(0));
            assertTrue(index.frameCompressedSize(0) <= 5000);
            assertEquals(4000, index.frameUncompressedSize(0));
            try (var decoded = index.newReadableByteChannel(source, ResourceOwnership.BORROWED)) {
                assertRange(decoded, plain, 0, plain.length);
                assertRange(decoded, plain, 1, 1);
            }
            verifyNativeFrames(path, index, plain, false);
        }
    }

    /// Checks the two exact upstream no-hang inputs without imposing native out-of-range API semantics.
    @ParameterizedTest
    @ValueSource(strings = {
            "5e2a4d18090000000000000003b1ea928f",
            "28b52ffd0032910000005e2a4d18090000000000000000b1ea928f"
    })
    void historicalNoHangInputs(String hex) throws IOException {
        byte[] encoded = HexFormat.of().parseHex(hex);
        Path path = directory.resolve("no-hang.zst");
        Files.write(path, encoded);
        try (var source = Files.newByteChannel(path)) {
            if (encoded.length == 27) {
                // The table declares no frames although ten bytes precede it.
                assertThrows(IOException.class, () -> ZstdCodec.DEFAULT.readIndex(source));
            } else {
                var index = ZstdCodec.DEFAULT.readIndex(source);
                assertNotNull(index);
                assertEquals(0, index.frameCount());
                assertEquals(0, index.uncompressedSize());
                try (var decoded = index.newReadableByteChannel(source, ResourceOwnership.BORROWED)) {
                    decoded.position(2);
                    ByteBuffer target = ByteBuffer.allocate(32);
                    assertEquals(-1, decoded.read(target));
                    assertEquals(0, target.position());
                    assertEquals(2, decoded.position());
                }
            }
            assertEquals(0, source.position());
        }
    }

    /// Checks that empty input begins with a standard frame, not just the terminal skippable table.
    @Test
    void emptyInputHasStandardMagic() throws IOException {
        byte[] plain = new byte[0];
        Path path = encode(plain, 255, 1, true);
        assertEquals(0xFD2FB528, ByteArrayAccess.readIntLittleEndian(Files.readAllBytes(path), 0));
        try (var source = Files.newByteChannel(path)) {
            var index = ZstdCodec.DEFAULT.readIndex(source);
            assertNotNull(index);
            assertEquals(1, index.frameCount());
            assertEquals(0, index.uncompressedSize());
            verifyNativeFrames(path, index, plain, true);
        }
    }

    /// Preserves the upstream forward-read amplification check and all six backward/forward range cases.
    @Test
    void repeatedSmallReadsAndSeeks() throws IOException {
        byte[] plain = ("Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt\0")
                .getBytes(StandardCharsets.US_ASCII);
        Path path = encode(plain, 40, 9, false);
        try (var source = new CountingSource(Files.newByteChannel(path))) {
            var index = ZstdCodec.DEFAULT.readIndex(source);
            assertNotNull(index);
            assertEquals(3, index.frameCount());
            try (var decoded = index.newReadableByteChannel(source, ResourceOwnership.BORROWED)) {
                for (int position = 0; position < plain.length; position += 2) {
                    assertRange(decoded, plain, position, 1);
                }
                assertTrue(source.totalRead <= Files.size(path), "Forward reads must not reread compressed frames");
                int[][] ranges = {{20, 40}, {60, 10}, {50, 20}, {10, 10}, {25, 10}, {60, 10}};
                for (int[] range : ranges) {
                    assertRange(decoded, plain, range[0], range[1]);
                }
            }
            verifyNativeFrames(path, index, plain, false);
        }
    }

    /// Samples the upstream fuzz target's level, checksum and subrange dimensions with reproducible data.
    @ParameterizedTest
    @ValueSource(ints = {-131072, -5, 1, 9, 22})
    void seekableRoundTripParameters(int level) throws IOException {
        Random random = new Random(0x5EECAB1EL);
        for (boolean checksum : new boolean[]{false, true}) {
            for (int size : new int[]{0, 1, 39, 40, 41, 4096}) {
                byte[] plain = new byte[size];
                random.nextBytes(plain);
                Path path = encode(plain, Math.max(1, size), level, checksum);
                try (var source = Files.newByteChannel(path)) {
                    var index = ZstdCodec.DEFAULT.readIndex(source);
                    assertNotNull(index);
                    verifyNativeFrames(path, index, plain, checksum);
                    try (var decoded = index.newReadableByteChannel(source, ResourceOwnership.BORROWED)) {
                        assertRange(decoded, plain, 0, size);
                        assertRange(decoded, plain, size, 0);
                        for (int iteration = 0; iteration < 16; iteration++) {
                            int count = random.nextInt(size + 1);
                            int offset = random.nextInt(size - count + 1);
                            assertRange(decoded, plain, offset, count);
                        }
                    }
                }
            }
        }
    }

    /// Encodes one input using the upstream test's level, frame-size and table-checksum settings.
    private Path encode(byte[] plain, int frameSize, int level, boolean checksum) throws IOException {
        Path path = Files.createTempFile(directory, "seekable-", ".zst");
        try (var target = Files.newByteChannel(path, java.nio.file.StandardOpenOption.WRITE);
             var encoder = ZstdCodec.DEFAULT.withCompressionLevel(level).withFrameChecksum(checksum)
                     .newSeekableWritableByteChannel(target,
                             new SeekableEncodingOptions(plain.length, frameSize), ResourceOwnership.BORROWED)) {
            ByteBuffer input = ByteBuffer.wrap(plain);
            encoder.encode(input);
            assertFalse(input.hasRemaining());
            encoder.finish();
        }
        return path;
    }

    /// Checks a logical range, including the zero-length operation allowed at EOF by the Channel contract.
    private static void assertRange(SeekableByteChannel decoded, byte[] plain, int offset, int count)
            throws IOException {
        decoded.position(offset);
        ByteBuffer target = ByteBuffer.allocate(count);
        assertEquals(count, decoded.read(target));
        assertEquals(offset + count, decoded.position());
        assertArrayEquals(Arrays.copyOfRange(plain, offset, offset + count), target.array());
    }

    /// Independently decodes each indexed frame and validates the table's checksum-presence flag.
    private static void verifyNativeFrames(Path path, CompressionCodec.Seekable.Index index,
                                           byte[] plain, boolean checksum) throws IOException {
        byte[] encoded = Files.readAllBytes(path);
        assertEquals(checksum ? 0x80 : 0, encoded[encoded.length - 5] & 0x80);
        assertEquals(plain.length, index.uncompressedSize());
        assertEquals(encoded.length, index.compressedSize());
        for (int frame = 0; frame < index.frameCount(); frame++) {
            int start = Math.toIntExact(index.frameCompressedOffset(frame));
            int end = start + Math.toIntExact(index.frameCompressedSize(frame));
            int offset = Math.toIntExact(index.frameUncompressedOffset(frame));
            int size = Math.toIntExact(index.frameUncompressedSize(frame));
            byte[] decoded = Zstd.decompress(Arrays.copyOfRange(encoded, start, end), size);
            assertArrayEquals(Arrays.copyOfRange(plain, offset, offset + size), decoded);
        }
    }

    /// Counts physical bytes read by the official forward-seek scenario.
    @NotNullByDefault
    private static final class CountingSource implements SeekableByteChannel {
        /// The owned encoded file channel.
        private final SeekableByteChannel delegate;
        /// The cumulative successful byte count, including seek-table reads.
        private long totalRead;
        /// Wraps an encoded file at its current position.
        private CountingSource(SeekableByteChannel delegate) { this.delegate = delegate; }
        /// Adds only actual progress to the cumulative count.
        @Override public int read(ByteBuffer target) throws IOException {
            int read = delegate.read(target);
            if (read > 0) totalRead += read;
            return read;
        }
        /// Rejects writes to the test source.
        @Override public int write(ByteBuffer source) { throw new NonWritableChannelException(); }
        /// Returns the physical position.
        @Override public long position() throws IOException { return delegate.position(); }
        /// Moves to a physical position.
        @Override public CountingSource position(long position) throws IOException {
            delegate.position(position);
            return this;
        }
        /// Returns the encoded extent.
        @Override public long size() throws IOException { return delegate.size(); }
        /// Rejects truncation of the test source.
        @Override public SeekableByteChannel truncate(long size) { throw new NonWritableChannelException(); }
        /// Returns the file's open state.
        @Override public boolean isOpen() { return delegate.isOpen(); }
        /// Closes the owned file channel.
        @Override public void close() throws IOException { delegate.close(); }
    }
}
