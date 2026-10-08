// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import com.github.luben.zstd.ZstdException;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.EOFException;
import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Adapts public frame and decoding assertions from Zstandard 1.5.7's fuzzer.c basic unit tests.
@NotNullByDefault
@Timeout(120)
final class ZstdOfficialBasicTest {
    /// Rejects the exact upstream skippable-size wraparound input without allocating its advertised payload.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void truncatedSkippableSizeNearUnsignedMaximum(int kind) throws IOException {
        byte[] frame = HexFormat.of().parseHex("502a4d18f8ffffff");
        ByteBuffer input = input(frame, kind);
        ZstdSkippableFrameInfo info = (ZstdSkippableFrameInfo) ZstdCodec.DEFAULT.frameInfo(input);
        assertEquals(0xfffffff8L, info.payloadSize());
        assertEquals(0, input.position());
        assertThrows(EOFException.class, () -> ZstdCodec.DEFAULT.frameCompressedSize(input));
        assertEquals(0, input.position());
        assertThrows(IOException.class, () -> ZstdCodec.DEFAULT.decompress(input, ByteBuffer.allocate(0)));
        try (var nativeDecoder = new ZstdDecompressCtx()) {
            assertThrows(ZstdException.class, () -> nativeDecoder.decompress(frame, 0));
        }
    }

    /// Preserves the original 5-MiB source size when checking truncation, trailing input and checksum policy.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void inputExtentAndChecksumValidation(int kind) throws IOException {
        byte[] plain = new byte[5 * 1024 * 1024];
        new Random(0x885946L).nextBytes(plain);
        for (int offset = 4096; offset < plain.length; offset += 8192) {
            System.arraycopy(plain, offset - 4096, plain, offset, 4096);
        }
        byte[] frame;
        try (var encoder = new ZstdCompressCtx()) {
            frame = encoder.setLevel(1).setChecksum(false).compress(plain);
        }
        ZstdCodec codec = ZstdCodec.DEFAULT;
        ZstdStandardFrameInfo info = (ZstdStandardFrameInfo) codec.frameInfo(input(frame, kind));
        assertEquals(plain.length, info.contentSize());
        assertEquals(Zstd.getFrameContentSize(frame), info.contentSize());
        assertEquals(Zstd.findFrameCompressedSize(frame), codec.frameCompressedSize(input(frame, kind)));
        ByteBuffer output = kind == 1 ? ByteBuffer.allocateDirect(plain.length + 1) : ByteBuffer.allocate(plain.length + 1);
        byte[] shortFrame = Arrays.copyOf(frame, frame.length - 1);
        byte[] trailingByte = Arrays.copyOf(frame, frame.length + 1);
        byte[] oversized = Arrays.copyOf(frame, Math.toIntExact(Zstd.compressBound(plain.length)));
        try (var nativeDecoder = new ZstdDecompressCtx()) {
            for (byte[] invalid : new byte[][]{shortFrame, trailingByte, oversized}) {
                ZstdException failure = assertThrows(ZstdException.class,
                        () -> nativeDecoder.decompress(invalid, plain.length + 1));
                assertEquals(Zstd.errSrcSizeWrong(), failure.getErrorCode());
                output.clear();
                assertThrows(IOException.class, () -> codec.decompress(input(invalid, kind), output));
            }
            assertThrows(BufferOverflowException.class,
                    () -> codec.decompress(input(frame, kind), ByteBuffer.allocate(0)));
            // The explicitly single-frame API must leave a trailing byte untouched instead of treating it as a frame.
            ByteBuffer source = input(trailingByte, kind);
            output.clear();
            codec.decompressFrame(source, output);
            assertEquals(frame.length, source.position());
            assertEquals(1, source.remaining());
            assertOutput(plain, output);

            byte[] damaged;
            try (var encoder = new ZstdCompressCtx()) {
                damaged = encoder.setLevel(1).setChecksum(true).compress(plain);
            }
            damaged[damaged.length - 1]++;
            ZstdException failure = assertThrows(ZstdException.class,
                    () -> nativeDecoder.decompress(damaged, plain.length + 1));
            assertEquals(Zstd.errChecksumWrong(), failure.getErrorCode());
            output.clear();
            assertThrows(IOException.class, () -> codec.decompress(input(damaged, kind), output));
            ZstdCodec unchecked = codec.withVerifyChecksums(false);
            output.clear();
            unchecked.decompress(input(damaged, kind), output);
            assertOutput(plain, output);
            byte[] missingChecksumByte = Arrays.copyOf(damaged, damaged.length - 1);
            output.clear();
            assertThrows(IOException.class, () -> unchecked.decompress(input(missingChecksumByte, kind), output));
        }
        output.clear();
        ByteBuffer source = input(frame, kind);
        codec.decompress(source, output);
        assertFalse(source.hasRemaining());
        assertOutput(plain, output);
    }

    /// Checks all produced bytes without assuming that output uses an accessible backing array.
    private static void assertOutput(byte[] expected, ByteBuffer output) {
        assertEquals(expected.length, output.position());
        byte[] actual = new byte[output.position()];
        output.flip().get(actual);
        assertArrayEquals(expected, actual);
    }

    /// Copies input into the selected heap, direct or read-only representation.
    private static ByteBuffer input(byte[] bytes, int kind) {
        ByteBuffer buffer = kind == 1 ? ByteBuffer.allocateDirect(bytes.length) : ByteBuffer.allocate(bytes.length);
        buffer.put(bytes).flip();
        return kind == 2 ? buffer.asReadOnlyBuffer() : buffer;
    }
}
