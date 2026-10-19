// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.all;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionFormats;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// Verifies incremental decoder buffers and detectable stream boundaries across installed formats.
@NotNullByDefault
final class CodecDecoderBufferContractTest {
    /// Limits calls for the small fixed payloads so a broken decoder cannot loop indefinitely.
    private static final int MAX_CALLS = 1 << 16;

    /// Bytes belonging to the enclosing container, not the compressed stream.
    private static final byte @Unmodifiable [] TRAILER = {17, 23, 37, 41};

    /// Covers every codec with empty and nonempty payloads, independently of its termination convention.
    private static Stream<Arguments> fragmentedInputs() {
        return CompressionFormats.installed().stream().flatMap(format -> Arrays.stream(Buffers.values())
                .flatMap(buffers -> Stream.of(0, 4097).map(size -> Arguments.of(format.name(), buffers, size))));
    }

    /// Covers formats whose default encodings have an intrinsic end marker or frame length.
    private static Stream<Arguments> terminatedInputs() {
        return CompressionFormats.installed().stream()
                // These raw formats require physical EOF or externally supplied sizes instead of an intrinsic marker.
                .filter(format -> !format.name().equals("compress") && !format.name().equals("lz4-block")
                        && !format.name().equals("ppmd"))
                .flatMap(format -> Arrays.stream(Buffers.values()).flatMap(buffers -> Stream.of(false, true)
                        .map(finishing -> Arguments.of(format.name(), buffers, finishing))));
    }

    /// Selects only codecs that promise independently terminated concatenated frames.
    private static Stream<Arguments> framedInputs() {
        return CompressionFormats.installed().stream()
                .filter(format -> format.defaultCodec() instanceof CompressionCodec.Framed<?>)
                .flatMap(format -> Arrays.stream(Buffers.values()).flatMap(buffers -> Stream.of(false, true)
                        .map(finishing -> Arguments.of(format.name(), buffers, finishing))));
    }

    /// Replaces both buffers after every call, preserving incomplete fields and overwriting all previous storage.
    @ParameterizedTest(name = "{0}, {1}, size={2}")
    @MethodSource("fragmentedInputs")
    void replacesCallerBuffers(String format, Buffers buffers, int size) throws IOException {
        CompressionCodec<?> codec = CompressionFormats.require(format).defaultCodec();
        byte[] expected = content(size, 37);
        byte[] encoded = encoded(codec, expected);
        int offset = 0;
        int available = Math.min(buffers.fragment, encoded.length);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var decoder = CodecContractConfigurations.decoderCodec(codec, size).newDecoder()) {
            for (int calls = 0; calls < MAX_CALLS; calls++) {
                byte[] fragment = Arrays.copyOfRange(encoded, offset, available);
                ByteBuffer storage = window(buffers.direct, fragment.length);
                storage.duplicate().put(fragment);
                ByteBuffer source = buffers.source(storage);
                int capacity = calls == 0 ? 0 : buffers.targetSize;
                ByteBuffer target = window(buffers.direct, capacity);
                CodecOutcome outcome = available == encoded.length
                        ? decoder.finish(source, target) : decoder.decode(source, target);
                int consumed = source.position() - 2;
                assertTrue(consumed >= 0 && consumed <= fragment.length);
                verifyWindow(source, fragment.length);
                assertArrayEquals(fragment, bytes(storage));
                offset += consumed;
                if (outcome == CodecOutcome.NEEDS_OUTPUT) assertEquals(target.limit(), target.position());
                drain(target, output, capacity);
                // Subsequent calls may reuse these allocations; retaining them must not affect decoding.
                overwrite(storage);
                overwrite(target);
                assertTrue(output.size() <= size, "Decoder produced more than the original payload");
                if (outcome == CodecOutcome.FINISHED) {
                    assertEquals(encoded.length, offset);
                    assertArrayEquals(expected, output.toByteArray());
                    return;
                }
                if (outcome == CodecOutcome.NEEDS_INPUT) {
                    assertTrue(available < encoded.length, "Final input must not request more input");
                    // The next fresh buffer contains any unconsumed field before the additional bytes.
                    available = Math.min(encoded.length, available + buffers.fragment);
                } else {
                    assertEquals(CodecOutcome.NEEDS_OUTPUT, outcome);
                }
            }
            fail("Decoder did not finish the fragmented payload");
        }
    }

    /// Finishes exactly at the compressed boundary, leaving container bytes untouched on repeated calls.
    @ParameterizedTest(name = "{0}, {1}, finish={2}")
    @MethodSource("terminatedInputs")
    void preservesTrailingInput(String format, Buffers buffers, boolean finishing) throws IOException {
        CompressionCodec<?> codec = CompressionFormats.require(format).defaultCodec();
        byte[] expected = content(4097, 11);
        byte[] encoded = encoded(codec, expected);
        ByteBuffer storage = window(buffers.direct, encoded.length + TRAILER.length);
        storage.duplicate().put(encoded).put(TRAILER);
        ByteBuffer source = buffers.source(storage);
        try (var decoder = codec.newDecoder()) {
            assertArrayEquals(expected, decodeFrame(decoder, source, buffers, finishing));
            assertEquals(encoded.length + 2, source.position());
            assertFinishedIsStable(decoder, source, buffers);
            assertArrayEquals(TRAILER, bytes(source));
        }
        assertEquals(2, storage.position());
        assertArrayEquals(encoded, Arrays.copyOf(bytes(storage), encoded.length));
    }

    /// Requires explicit reset between distinct and empty frames and reapplies the configured per-session limit.
    @ParameterizedTest(name = "{0}, {1}, finish={2}")
    @MethodSource("framedInputs")
    void resetsBetweenConcatenatedFrames(String format, Buffers buffers, boolean finishing) throws IOException {
        CompressionCodec<?> codec = CompressionFormats.require(format).defaultCodec();
        byte[][] contents = {content(4097, 19), new byte[0], content(4097, 71)};
        byte[][] frames = new byte[contents.length][];
        ByteArrayOutputStream combined = new ByteArrayOutputStream();
        for (int index = 0; index < frames.length; index++) {
            frames[index] = encoded(codec, contents[index]);
            combined.writeBytes(frames[index]);
        }
        combined.writeBytes(TRAILER);
        ByteBuffer storage = window(buffers.direct, combined.size());
        storage.duplicate().put(combined.toByteArray());
        ByteBuffer source = buffers.source(storage);
        try (var decoder = codec.withMaximumOutputSize(4097).newDecoder()) {
            int end = 2;
            for (int index = 0; index < frames.length; index++) {
                assertArrayEquals(contents[index], decodeFrame(decoder, source, buffers, finishing));
                end += frames[index].length;
                assertEquals(end, source.position());
                assertFinishedIsStable(decoder, source, buffers);
                if (index + 1 < frames.length) decoder.reset();
            }
            assertArrayEquals(TRAILER, bytes(source));
        }
        assertArrayEquals(combined.toByteArray(), bytes(storage));
    }

    /// Decodes a complete frame with bounded targets without allowing the engine to consume the next frame.
    private static byte[] decodeFrame(CompressionDecoder decoder, ByteBuffer source, Buffers buffers,
                                      boolean finishing) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int limit = source.limit();
        for (int calls = 0; calls < MAX_CALLS; calls++) {
            int capacity = calls == 0 ? 0 : buffers.targetSize;
            ByteBuffer target = window(buffers.direct, capacity);
            CodecOutcome outcome = finishing ? decoder.finish(source, target) : decoder.decode(source, target);
            verifyWindow(source, limit - 2);
            if (outcome == CodecOutcome.NEEDS_OUTPUT) assertEquals(target.limit(), target.position());
            drain(target, output, capacity);
            overwrite(target);
            if (outcome == CodecOutcome.FINISHED) return output.toByteArray();
            assertEquals(CodecOutcome.NEEDS_OUTPUT, outcome);
        }
        return fail("Decoder did not finish the complete frame");
    }

    /// Both terminal operations are no-ops until reset, even with nonempty source and writable target space.
    private static void assertFinishedIsStable(CompressionDecoder decoder, ByteBuffer source, Buffers buffers)
            throws IOException {
        int position = source.position();
        int limit = source.limit();
        for (int capacity : new int[]{0, 3}) {
            ByteBuffer target = window(buffers.direct, capacity);
            assertEquals(CodecOutcome.FINISHED, decoder.decode(source, target));
            assertEquals(CodecOutcome.FINISHED, decoder.finish(source, target));
            assertEquals(position, source.position());
            verifyWindow(source, limit - 2);
            assertEquals(2, target.position());
            verifyWindow(target, capacity);
            for (byte value : bytes(target)) assertEquals((byte) 0x5a, value);
        }
    }

    /// Creates a sliced window with a nonzero position, guard bytes, and little-endian byte order.
    private static ByteBuffer window(boolean direct, int size) {
        ByteBuffer allocation = direct ? ByteBuffer.allocateDirect(size + 9) : ByteBuffer.allocate(size + 9);
        ByteBuffer window = allocation.position(5).slice().order(ByteOrder.LITTLE_ENDIAN);
        while (window.hasRemaining()) window.put((byte) 0x5a);
        return window.position(2).limit(size + 2);
    }

    /// Checks window limits, byte order, and bytes outside the permitted source or target region.
    private static void verifyWindow(ByteBuffer buffer, int size) {
        assertEquals(size + 2, buffer.limit());
        assertEquals(ByteOrder.LITTLE_ENDIAN, buffer.order());
        ByteBuffer full = buffer.duplicate().clear();
        for (int index : new int[]{0, 1, size + 2, size + 3}) assertEquals((byte) 0x5a, full.get(index));
    }

    /// Copies produced target bytes without including either guard region.
    private static void drain(ByteBuffer target, ByteArrayOutputStream output, int size) {
        verifyWindow(target, size);
        ByteBuffer produced = target.duplicate().limit(target.position()).position(2);
        output.writeBytes(bytes(produced));
    }

    /// Reuses caller-owned storage after the engine operation has returned.
    private static void overwrite(ByteBuffer buffer) {
        buffer.clear();
        while (buffer.hasRemaining()) buffer.put((byte) 0x33);
    }

    /// Copies remaining bytes without modifying the original buffer's position.
    private static byte[] bytes(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        return bytes;
    }

    /// Encodes one independent stream using the format's default configuration.
    private static byte[] encoded(CompressionCodec<?> codec, byte[] content) throws IOException {
        return bytes(codec.compress(ByteBuffer.wrap(content)));
    }

    /// Creates deterministic input with long runs, varied literals, and distinct content for each seed.
    private static byte[] content(int size, int seed) {
        byte[] bytes = new byte[size];
        for (int index = 0; index < size; index++) {
            bytes[index] = (byte) (index % 31 < 23 ? seed + index % 7 : seed + index * 17);
        }
        return bytes;
    }

    /// Combines source mutability, storage kind, and independent input/output fragmentation sizes.
    @NotNullByDefault
    private enum Buffers {
        /// Writable heap slices with small input fragments.
        HEAP(false, false, 7, 31),
        /// Writable direct slices with one-byte output windows.
        DIRECT(true, false, 13, 1),
        /// Read-only heap slices with one-byte input fragments.
        READ_ONLY_HEAP(false, true, 1, 2),
        /// Read-only direct slices with one-byte input and output windows.
        READ_ONLY_DIRECT(true, true, 1, 1);

        /// Whether the backing storage is direct.
        private final boolean direct;
        /// Whether the decoder receives a read-only source view.
        private final boolean readOnly;
        /// Maximum number of newly exposed input bytes per input request.
        private final int fragment;
        /// Writable output bytes after the first zero-capacity call.
        private final int targetSize;

        /// Records the source kind and fragmentation bounds.
        Buffers(boolean direct, boolean readOnly, int fragment, int targetSize) {
            this.direct = direct;
            this.readOnly = readOnly;
            this.fragment = fragment;
            this.targetSize = targetSize;
        }

        /// Creates a source view with independent position and the selected mutability.
        ByteBuffer source(ByteBuffer storage) {
            return (readOnly ? storage.asReadOnlyBuffer() : storage.duplicate()).order(ByteOrder.LITTLE_ENDIAN);
        }
    }
}
