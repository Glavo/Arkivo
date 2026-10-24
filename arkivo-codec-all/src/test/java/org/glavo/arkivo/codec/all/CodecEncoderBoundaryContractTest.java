// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.all;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionFormats;
import org.glavo.arkivo.codec.EncodingOptions;
import org.glavo.arkivo.codec.bzip2.BZip2Codec;
import org.glavo.arkivo.codec.xz.XZCheckType;
import org.glavo.arkivo.codec.xz.XZCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;
import org.junit.jupiter.api.Named;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies buffer-engine boundary transitions across the codecs that expose each capability.
@NotNullByDefault
final class CodecEncoderBoundaryContractTest {
    /// Bounds test loops if a broken encoder emits output indefinitely for the small fixed payloads.
    private static final int MAX_ENGINE_CALLS = 1 << 16;

    /// Small deterministic input containing both repeated and varying bytes.
    private static final byte @Unmodifiable [] CONTENT = content(37);

    /// Different content exposes stale frame or flush state when the encoder is reused.
    private static final byte @Unmodifiable [] NEXT_CONTENT = content(91);

    /// Returns every installed codec, smaller BZip2 blocks, and an XZ configuration with frequent block boundaries.
    private static Stream<CompressionCodec<?>> codecs() {
        return Stream.concat(CompressionFormats.installed().stream().map(format -> format.defaultCodec()),
                Stream.of(BZip2Codec.DEFAULT.withCompressionLevel(1), BZip2Codec.DEFAULT.withCompressionLevel(2),
                        XZCodec.DEFAULT.withDictionarySize(1 << 16).withCheckType(XZCheckType.CRC32).withBlockSize(64)));
    }

    /// Expands supported boundary operations over heap and direct target windows.
    private static Stream<Arguments> boundaries() {
        return codecs().flatMap(codec -> Arrays.stream(Boundary.values())
                .filter(boundary -> boundary.supports(codec))
                .flatMap(boundary -> Stream.of(false, true).map(direct ->
                        Arguments.of(namedCodec(codec), boundary, direct))));
    }

    /// Expands framed codecs over both buffer storage kinds without silently skipping invocations.
    private static Stream<Arguments> framedCodecs() {
        return codecs().filter(codec -> codec instanceof CompressionCodec.Framed<?>)
                .flatMap(codec -> Stream.of(false, true).map(direct -> Arguments.of(namedCodec(codec), direct)));
    }

    /// Supplies stable names for separately reported closure tests.
    private static Stream<Named<CompressionCodec<?>>> namedCodecs() {
        return codecs().map(CodecEncoderBoundaryContractTest::namedCodec);
    }

    /// Covers empty, one-byte, and fragmented payloads with exact metadata for every installed format.
    private static Stream<Arguments> exactSourceSizes() {
        return CompressionFormats.installed().stream().flatMap(format -> Stream.of(false, true)
                .flatMap(direct -> Stream.of(0, 1, CONTENT.length)
                        .map(size -> Arguments.of(namedCodec(format.defaultCodec()), direct, size))));
    }

    /// Covers oversized initial buffers and excess bytes supplied after earlier progress.
    private static Stream<Arguments> excessSourceSizes() {
        return codecs().filter(CodecContractConfigurations::validatesSourceSize)
                .flatMap(codec -> Stream.of(false, true).flatMap(direct -> Stream.of(false, true)
                        .map(partial -> Arguments.of(namedCodec(codec), direct, partial))));
    }

    /// Covers terminal and frame-only completion for formats that validate input sizes.
    private static Stream<Arguments> incompleteSourceSizes() {
        return codecs().filter(CodecContractConfigurations::validatesSourceSize)
                .flatMap(codec -> Stream.of(Boundary.FINISH, Boundary.FRAME)
                        .filter(boundary -> boundary.supports(codec))
                        .flatMap(boundary -> Stream.of(false, true)
                                .map(direct -> Arguments.of(namedCodec(codec), boundary, direct))));
    }

    /// Exact metadata accepts the whole input and is restored when the encoder is reused.
    @ParameterizedTest(name = "{0}, direct={1}, size={2}")
    @MethodSource("exactSourceSizes")
    void acceptsExactSourceSize(CompressionCodec<?> codec, boolean direct, int size) throws IOException {
        try (var encoder = codec.newEncoder(EncodingOptions.ofSourceSize(size))) {
            for (byte[] content : new byte[][]{Arrays.copyOf(CONTENT, size), Arrays.copyOf(NEXT_CONTENT, size)}) {
                ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                encode(encoder, Arrays.copyOfRange(content, 0, size / 2), encoded, direct);
                encode(encoder, Arrays.copyOfRange(content, size / 2, size), encoded, direct);
                complete(encoder, Boundary.FINISH, encoded, direct);
                assertDecodes(codec, encoded.toByteArray(), content);
                encoder.reset();
            }
        }
    }

    /// Oversized input is rejected without consuming the failing buffer, and reset restores the initial pledge.
    @ParameterizedTest(name = "{0}, direct={1}, partial={2}")
    @MethodSource("excessSourceSizes")
    void resetsAfterExcessSourceSize(CompressionCodec<?> codec, boolean direct, boolean partial) throws IOException {
        for (int size : new int[]{0, 3, CONTENT.length}) {
            try (var encoder = codec.newEncoder(EncodingOptions.ofSourceSize(size))) {
                int prefix = partial ? size / 2 : 0;
                if (prefix > 0) {
                    encode(encoder, Arrays.copyOf(CONTENT, prefix), new ByteArrayOutputStream(), direct);
                }
                byte[] excess = Arrays.copyOf(CONTENT, size - prefix + 1);
                ByteBuffer storage = direct ? ByteBuffer.allocateDirect(excess.length + 6)
                        : ByteBuffer.allocate(excess.length + 6);
                storage.position(3).put(excess).flip().position(3);
                @UnmodifiableView ByteBuffer source = storage.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
                ByteBuffer target = target(direct, 32);
                assertThrows(IOException.class, () -> encoder.encode(source, target));
                assertEquals(3, source.position());
                assertEquals(excess.length + 3, source.limit());
                assertEquals(ByteOrder.LITTLE_ENDIAN, source.order());
                verifyTarget(target, 32);

                encoder.reset();
                byte[] expected = Arrays.copyOf(NEXT_CONTENT, size);
                ByteArrayOutputStream recovered = new ByteArrayOutputStream();
                encode(encoder, expected, recovered, direct);
                complete(encoder, Boundary.FINISH, recovered, direct);
                assertDecodes(codec, recovered.toByteArray(), expected);
            }
        }
    }

    /// Incomplete streams and frames fail before completion and can be discarded without changing the pledge.
    @ParameterizedTest(name = "{0}, {1}, direct={2}")
    @MethodSource("incompleteSourceSizes")
    void resetsAfterIncompleteSourceSize(CompressionCodec<?> codec, Boundary boundary, boolean direct)
            throws IOException {
        for (int size : new int[]{1, CONTENT.length}) {
            for (int supplied : new int[]{0, size - 1}) {
                try (var encoder = codec.newEncoder(EncodingOptions.ofSourceSize(size))) {
                    encode(encoder, Arrays.copyOf(CONTENT, supplied), new ByteArrayOutputStream(), direct);
                    assertThrows(IOException.class,
                            () -> complete(encoder, boundary, new ByteArrayOutputStream(), direct));
                    encoder.reset();
                    encoder.reset();
                    ByteArrayOutputStream recovered = new ByteArrayOutputStream();
                    byte[] expected = Arrays.copyOf(NEXT_CONTENT, size);
                    encode(encoder, expected, recovered, direct);
                    complete(encoder, boundary, recovered, direct);
                    assertDecodes(codec, recovered.toByteArray(), expected);
                }
            }
        }
    }

    /// Reset restores the initial frame options rather than a later frame's different size declaration.
    @ParameterizedTest(name = "{0}, direct={1}")
    @MethodSource("framedCodecs")
    void restoresInitialFrameSourceSize(CompressionCodec.Framed<?> codec, boolean direct) throws IOException {
        try (var encoder = codec.newEncoder(EncodingOptions.ofSourceSize(CONTENT.length))) {
            encode(encoder, CONTENT, new ByteArrayOutputStream(), direct);
            complete(encoder, Boundary.FRAME, new ByteArrayOutputStream(), direct);
            encoder.startFrame(EncodingOptions.ofSourceSize(0));
            complete(encoder, Boundary.FRAME, new ByteArrayOutputStream(), direct);
            encoder.reset();
            ByteArrayOutputStream restored = new ByteArrayOutputStream();
            encode(encoder, NEXT_CONTENT, restored, direct);
            complete(encoder, Boundary.FINISH, restored, direct);
            assertDecodes(codec, restored.toByteArray(), NEXT_CONTENT);
            if (CodecContractConfigurations.validatesSourceSize(codec)) {
                encoder.reset();
                assertThrows(IOException.class,
                        () -> complete(encoder, Boundary.FINISH, new ByteArrayOutputStream(), direct));
            }
        }
    }

    /// Identifies format and non-default configuration without object identity hashes in reports.
    private static Named<CompressionCodec<?>> namedCodec(CompressionCodec<?> codec) {
        String name = codec.format().name();
        if (codec instanceof BZip2Codec bzip2) name += "/level=" + bzip2.compressionLevel();
        if (codec instanceof XZCodec xz) name += "/blockSize=" + xz.blockSize();
        return Named.of(name, codec);
    }

    /// Rejects conflicting operations while output is pending, then resumes the same operation to completion.
    @ParameterizedTest(name = "{0}, {1}, direct={2}")
    @MethodSource("boundaries")
    void rejectsConflictingOperations(CompressionCodec<?> codec, Boundary boundary, boolean direct) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (var encoder = codec.newEncoder()) {
            encode(encoder, CONTENT, encoded, direct);
            ByteBuffer blocked = target(direct, 0);
            assertEquals(CodecOutcome.NEEDS_OUTPUT, boundary.apply(encoder, blocked));
            verifyTarget(blocked, 0);
            assertThrows(IllegalStateException.class,
                    () -> encoder.encode(ByteBuffer.allocate(0), ByteBuffer.allocate(1)));
            for (Boundary other : Boundary.values()) {
                if (other != boundary && other.supports(codec)) {
                    assertThrows(IllegalStateException.class, () -> other.apply(encoder, ByteBuffer.allocate(1)));
                }
            }
            if (encoder instanceof CompressionEncoder.Framed framed) {
                assertThrows(IllegalStateException.class, framed::startFrame);
            }
            complete(encoder, boundary, encoded, direct);
            if (boundary == Boundary.FLUSH) {
                encode(encoder, NEXT_CONTENT, encoded, direct);
            }
            complete(encoder, Boundary.FINISH, encoded, direct);
            assertEquals(CodecOutcome.FINISHED, encoder.finish(ByteBuffer.allocate(0)));
            assertThrows(IllegalStateException.class,
                    () -> encoder.encode(ByteBuffer.allocate(0), ByteBuffer.allocate(1)));
            if (encoder instanceof CompressionEncoder.Flushable flushable) {
                assertThrows(IllegalStateException.class, () -> flushable.flush(ByteBuffer.allocate(1)));
            }
            if (encoder instanceof CompressionEncoder.Framed framed) {
                assertThrows(IllegalStateException.class, () -> framed.finishFrame(ByteBuffer.allocate(1)));
                assertThrows(IllegalStateException.class, framed::startFrame);
            }
        }
        byte[] expected = boundary == Boundary.FLUSH ? concatenate(CONTENT, NEXT_CONTENT) : CONTENT;
        assertDecodes(codec, encoded.toByteArray(), expected);
    }

    /// Terminal completion excludes new input even when the initial header has not reached the output.
    @ParameterizedTest(name = "{0}")
    @MethodSource("namedCodecs")
    void finishesEmptyStreamBeforeHeaderDrains(CompressionCodec<?> codec) throws IOException {
        for (boolean direct : new boolean[]{false, true}) {
            for (int capacity : new int[]{0, 1}) {
                try (var encoder = codec.newEncoder()) {
                    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                    ByteBuffer first = target(direct, capacity);
                    CodecOutcome outcome = encoder.finish(first);
                    assertTrue(outcome == CodecOutcome.NEEDS_OUTPUT || outcome == CodecOutcome.FINISHED);
                    drain(first, encoded, capacity);
                    ByteBuffer source = ByteBuffer.wrap(new byte[]{7});
                    ByteBuffer rejected = target(direct, 32);
                    assertThrows(IllegalStateException.class, () -> encoder.encode(source, rejected));
                    assertEquals(0, source.position());
                    assertEquals(2, rejected.position());
                    verifyTarget(rejected, 32);
                    complete(encoder, Boundary.FINISH, encoded, direct);
                    assertDecodes(codec, encoded.toByteArray(), new byte[0]);

                    encoder.reset();
                    ByteArrayOutputStream recovered = new ByteArrayOutputStream();
                    encode(encoder, NEXT_CONTENT, recovered, direct);
                    complete(encoder, Boundary.FINISH, recovered, direct);
                    assertDecodes(codec, recovered.toByteArray(), NEXT_CONTENT);
                }
            }
        }
    }

    /// Closing an active encoder releases it without allowing reset or any further boundary operation.
    @ParameterizedTest(name = "{0}")
    @MethodSource("namedCodecs")
    void rejectsOperationsAfterClose(CompressionCodec<?> codec) throws IOException {
        try (var encoder = codec.newEncoder()) {
            encode(encoder, CONTENT, new ByteArrayOutputStream(), false);
            encoder.close();
            encoder.close();
            assertThrows(IllegalStateException.class, encoder::reset);
            assertThrows(IllegalStateException.class,
                    () -> encoder.encode(ByteBuffer.allocate(0), ByteBuffer.allocate(0)));
            for (Boundary boundary : Boundary.values()) {
                if (boundary.supports(codec)) {
                    assertThrows(IllegalStateException.class, () -> boundary.apply(encoder, ByteBuffer.allocate(0)));
                }
            }
            if (encoder instanceof CompressionEncoder.Framed framed) {
                assertThrows(IllegalStateException.class, framed::startFrame);
            }
        }
    }

    /// Discards partially emitted boundaries and verifies reuse against both populated and empty streams.
    @ParameterizedTest(name = "{0}, {1}, direct={2}")
    @MethodSource("boundaries")
    void resetDiscardsPendingBoundary(CompressionCodec<?> codec, Boundary boundary, boolean direct) throws IOException {
        try (var encoder = codec.newEncoder()) {
            encode(encoder, CONTENT, new ByteArrayOutputStream(), direct);
            ByteBuffer partial = target(direct, 1);
            assertEquals(CodecOutcome.NEEDS_OUTPUT, boundary.apply(encoder, partial));
            verifyTarget(partial, 1);
            assertEquals(partial.limit(), partial.position());
            encoder.reset();
            encoder.reset();
            ByteArrayOutputStream recovered = new ByteArrayOutputStream();
            encode(encoder, NEXT_CONTENT, recovered, direct);
            complete(encoder, Boundary.FINISH, recovered, direct);
            assertDecodes(codec, recovered.toByteArray(), NEXT_CONTENT);

            encoder.reset();
            ByteArrayOutputStream empty = new ByteArrayOutputStream();
            complete(encoder, Boundary.FINISH, empty, direct);
            assertDecodes(codec, empty.toByteArray(), new byte[0]);
        }
    }

    /// Preserves explicit empty frames, implicit next frames, and no-output operations between frames.
    @ParameterizedTest(name = "{0}, direct={1}")
    @MethodSource("framedCodecs")
    void preservesFrameBoundaries(CompressionCodec.Framed<?> codec, boolean direct) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (var encoder = codec.newEncoder(EncodingOptions.ofSourceSize(CONTENT.length))) {
            assertThrows(IllegalStateException.class, encoder::startFrame);
            encode(encoder, CONTENT, encoded, direct);
            complete(encoder, Boundary.FRAME, encoded, direct);
            int firstEnd = encoded.size();
            assertEquals(CodecOutcome.BOUNDARY_REACHED, encoder.finishFrame(ByteBuffer.allocate(0)));
            assertEquals(CodecOutcome.NEEDS_INPUT,
                    encoder.encode(ByteBuffer.allocate(0), ByteBuffer.allocate(0)));
            if (encoder instanceof CompressionEncoder.Flushable flushable) {
                assertEquals(CodecOutcome.FLUSHED, flushable.flush(ByteBuffer.allocate(0)));
            }
            encoder.startFrame(EncodingOptions.ofSourceSize(0));
            assertThrows(IllegalStateException.class, encoder::startFrame);
            complete(encoder, Boundary.FRAME, encoded, direct);
            int emptyEnd = encoded.size();
            assertTrue(emptyEnd > firstEnd);

            // Implicit frames use default options, not the preceding empty frame's zero-size pledge.
            encode(encoder, NEXT_CONTENT, encoded, direct);
            complete(encoder, Boundary.FRAME, encoded, direct);
            int lastEnd = encoded.size();
            ByteBuffer terminal = target(direct, 7);
            assertEquals(CodecOutcome.FINISHED, encoder.finish(terminal));
            assertEquals(2, terminal.position());
            verifyTarget(terminal, 7);
            assertEquals(CodecOutcome.FINISHED, encoder.finish(ByteBuffer.allocate(0)));
            assertThrows(IllegalStateException.class, encoder::startFrame);
            assertThrows(IllegalStateException.class, () -> encoder.finishFrame(ByteBuffer.allocate(1)));
            byte[] bytes = encoded.toByteArray();
            assertEquals(lastEnd, bytes.length);
            assertDecodes(codec, Arrays.copyOfRange(bytes, 0, firstEnd), CONTENT);
            assertDecodes(codec, Arrays.copyOfRange(bytes, firstEnd, emptyEnd), new byte[0]);
            assertDecodes(codec, Arrays.copyOfRange(bytes, emptyEnd, lastEnd), NEXT_CONTENT);
            assertDecodes(codec, bytes, concatenate(CONTENT, NEXT_CONTENT));
        }
    }

    /// Finishing an explicitly started empty frame emits it and excludes other operations while its output is pending.
    @ParameterizedTest(name = "{0}, direct={1}")
    @MethodSource("framedCodecs")
    void finishesExplicitEmptyFrame(CompressionCodec.Framed<?> codec, boolean direct) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (var encoder = codec.newEncoder()) {
            encode(encoder, CONTENT, encoded, direct);
            complete(encoder, Boundary.FRAME, encoded, direct);
            int firstEnd = encoded.size();
            encoder.startFrame(EncodingOptions.ofSourceSize(0));
            ByteBuffer blocked = target(direct, 0);
            assertEquals(CodecOutcome.NEEDS_OUTPUT, encoder.finish(blocked));
            verifyTarget(blocked, 0);
            assertThrows(IllegalStateException.class, encoder::startFrame);
            assertThrows(IllegalStateException.class,
                    () -> encoder.encode(ByteBuffer.allocate(0), ByteBuffer.allocate(1)));
            assertThrows(IllegalStateException.class, () -> encoder.finishFrame(ByteBuffer.allocate(1)));
            if (encoder instanceof CompressionEncoder.Flushable flushable) {
                assertThrows(IllegalStateException.class, () -> flushable.flush(ByteBuffer.allocate(1)));
            }
            complete(encoder, Boundary.FINISH, encoded, direct);
            assertTrue(encoded.size() > firstEnd);
            assertDecodes(codec, Arrays.copyOfRange(encoded.toByteArray(), firstEnd, encoded.size()), new byte[0]);
            assertDecodes(codec, encoded.toByteArray(), CONTENT);
        }
    }

    /// Encodes through read-only source views and fresh bounded targets, checking progress at every call.
    private static void encode(CompressionEncoder encoder, byte[] bytes, ByteArrayOutputStream output,
                               boolean direct) throws IOException {
        ByteBuffer storage = direct ? ByteBuffer.allocateDirect(bytes.length + 6) : ByteBuffer.allocate(bytes.length + 6);
        storage.position(3).put(bytes).flip().position(3);
        @UnmodifiableView ByteBuffer source = storage.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
        CodecOutcome outcome;
        int calls = 0;
        do {
            assertTrue(calls++ < MAX_ENGINE_CALLS, "Encoder did not consume the test payload");
            int before = source.position();
            ByteBuffer target = target(direct, 3);
            outcome = encoder.encode(source, target);
            assertTrue(outcome == CodecOutcome.NEEDS_INPUT || outcome == CodecOutcome.NEEDS_OUTPUT);
            assertEquals(bytes.length + 3, source.limit());
            assertEquals(ByteOrder.LITTLE_ENDIAN, source.order());
            assertTrue(source.position() > before || target.position() > 2 || outcome == CodecOutcome.NEEDS_INPUT);
            if (outcome == CodecOutcome.NEEDS_OUTPUT) assertEquals(target.limit(), target.position());
            drain(target, output, 3);
        } while (outcome == CodecOutcome.NEEDS_OUTPUT);
        assertFalse(source.hasRemaining());
        assertEquals(3, storage.position());
    }

    /// Drains a boundary with fresh two-byte windows and rejects stalled or unexpected outcomes.
    private static void complete(CompressionEncoder encoder, Boundary boundary, ByteArrayOutputStream output,
                                 boolean direct) throws IOException {
        int calls = 0;
        while (true) {
            assertTrue(calls++ < MAX_ENGINE_CALLS, "Encoder did not complete " + boundary);
            ByteBuffer target = target(direct, 2);
            CodecOutcome outcome;
            try {
                outcome = boundary.apply(encoder, target);
            } finally {
                verifyTarget(target, 2);
            }
            if (outcome == CodecOutcome.NEEDS_OUTPUT) assertEquals(target.limit(), target.position());
            drain(target, output, 2);
            if (outcome != CodecOutcome.NEEDS_OUTPUT) {
                assertEquals(boundary.completed, outcome);
                return;
            }
        }
    }

    /// Allocates a writable window between sentinel bytes, with a non-default byte order.
    private static ByteBuffer target(boolean direct, int size) {
        ByteBuffer target = direct ? ByteBuffer.allocateDirect(size + 4) : ByteBuffer.allocate(size + 4);
        while (target.hasRemaining()) target.put((byte) 0x5a);
        return target.position(2).limit(size + 2).order(ByteOrder.LITTLE_ENDIAN);
    }

    /// Checks that an engine neither overwrote outside the target window nor changed its limit or order.
    private static void verifyTarget(ByteBuffer target, int size) {
        assertEquals(size + 2, target.limit());
        assertEquals(ByteOrder.LITTLE_ENDIAN, target.order());
        ByteBuffer storage = target.duplicate().clear();
        for (int index : new int[]{0, 1, size + 2, size + 3}) assertEquals((byte) 0x5a, storage.get(index));
    }

    /// Copies only bytes produced inside a checked target window.
    private static void drain(ByteBuffer target, ByteArrayOutputStream output, int size) {
        verifyTarget(target, size);
        target.limit(target.position()).position(2);
        while (target.hasRemaining()) output.write(target.get());
    }

    /// Checks decoded content with format-specific externally supplied sizes where required.
    private static void assertDecodes(CompressionCodec<?> codec, byte[] encoded, byte[] expected) throws IOException {
        ByteBuffer decoded = CodecContractConfigurations.decoderCodec(codec, expected.length)
                .withMaximumOutputSize(expected.length).decompress(ByteBuffer.wrap(encoded));
        byte[] actual = new byte[decoded.remaining()];
        decoded.get(actual);
        assertArrayEquals(expected, actual);
    }

    /// Joins two expected payloads without sharing mutable storage.
    private static byte[] concatenate(byte[] first, byte[] second) {
        byte[] bytes = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, bytes, first.length, second.length);
        return bytes;
    }

    /// Creates content long enough to exercise the small-block XZ configuration.
    private static byte[] content(int seed) {
        byte[] bytes = new byte[513];
        for (int index = 0; index < bytes.length; index++) bytes[index] = (byte) (seed + index * 31 + index / 17);
        return bytes;
    }

    /// The resumable encoder operations and their successful terminal results.
    @NotNullByDefault
    private enum Boundary {
        /// A nonterminal flush within a stream.
        FLUSH(CodecOutcome.FLUSHED),
        /// A nonterminal frame boundary.
        FRAME(CodecOutcome.BOUNDARY_REACHED),
        /// Terminal completion of the encoding session.
        FINISH(CodecOutcome.FINISHED);

        /// The result reported when the operation has emitted all pending bytes.
        private final CodecOutcome completed;

        /// Records the completion result for this operation.
        Boundary(CodecOutcome completed) {
            this.completed = completed;
        }

        /// Selects only public capabilities advertised by the codec.
        boolean supports(CompressionCodec<?> codec) {
            return switch (this) {
                case FLUSH -> codec instanceof CompressionCodec.Flushable<?>;
                case FRAME -> codec instanceof CompressionCodec.Framed<?>;
                case FINISH -> true;
            };
        }

        /// Invokes this operation on an encoder selected for its supported capabilities.
        CodecOutcome apply(CompressionEncoder encoder, ByteBuffer target) throws IOException {
            return switch (this) {
                case FLUSH -> ((CompressionEncoder.Flushable) encoder).flush(target);
                case FRAME -> ((CompressionEncoder.Framed) encoder).finishFrame(target);
                case FINISH -> encoder.finish(target);
            };
        }
    }
}
