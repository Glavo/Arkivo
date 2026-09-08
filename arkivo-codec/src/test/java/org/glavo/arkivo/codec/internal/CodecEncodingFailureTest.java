// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.EncodingOptions;
import org.glavo.arkivo.codec.ResourceOwnership;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.WritableByteChannel;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies terminal encoding failures across capability, ownership, and interruptibility combinations.
@NotNullByDefault
final class CodecEncodingFailureTest {
    /// Supplies all supported operation and channel-lifecycle combinations.
    private static Stream<Arguments> operations() {
        return Stream.of(Capability.values()).flatMap(capability -> Stream.of(Phase.values())
                .filter(capability::supports).flatMap(phase -> Stream.of(ResourceOwnership.values())
                        .flatMap(ownership -> Stream.of(false, true)
                                .map(interruptible -> Arguments.of(capability, phase, ownership, interruptible)))));
    }

    /// Supplies engine and target failures before or after partial progress.
    private static Stream<Arguments> failures() {
        return operations().flatMap(arguments -> {
            Object[] values = arguments.get();
            Phase phase = (Phase) values[1];
            return Stream.of(false, true).filter(target -> !target || phase != Phase.START)
                    .flatMap(target -> Stream.of(FailureKind.values()).flatMap(kind -> Stream.of(false, true)
                            .map(partial -> Arguments.of(values[0], phase, values[2], values[3], target, kind, partial))));
        });
    }

    /// Stops every engine operation and releases resources after an engine or target failure.
    @ParameterizedTest(name = "{0}, {1}, {2}, interruptible={3}, target={4}, {5}, partial={6}")
    @MethodSource("failures")
    void failureIsTerminal(Capability capability, Phase phase, ResourceOwnership ownership,
                           boolean interruptible, boolean targetFailure, FailureKind kind, boolean partial)
            throws IOException {
        Engine engine = capability.newEngine();
        Target target = interruptible ? new InterruptibleTarget() : new Target();
        CompressingWritableByteChannel channel = CodecChannelAdapters.newWritableByteChannel(target, ownership, () -> engine);
        prepare(channel, phase);
        Throwable failure = kind.create();
        if (targetFailure) {
            target.failure = failure;
            target.partial = partial;
        } else {
            engine.failure = failure;
            engine.partial = partial;
        }
        @UnmodifiableView ByteBuffer source = source();
        long inputBefore = channel.inputBytes();
        long outputBefore = channel.outputBytes();
        assertSame(failure, assertThrows(failure.getClass(), () -> invoke(channel, phase, source)));
        assertFalse(channel.isOpen());
        assertEquals(1, engine.closeCalls);
        assertEquals(ownership == ResourceOwnership.OWNED ? 1 : 0, target.closeCalls);
        assertEquals(inputBefore + source.position() - 2, channel.inputBytes());
        assertEquals(outputBefore + (targetFailure && partial ? 1 : 0), channel.outputBytes());
        assertEquals(6, source.limit());
        assertEquals(ByteOrder.LITTLE_ENDIAN, source.order());
        assertStopped(channel, engine, target);
        assertEquals(ownership == ResourceOwnership.BORROWED, target.isOpen());
    }

    /// Supplies invalid outcomes, stalled engines, and zero-progress targets for output-producing operations.
    private static Stream<Arguments> invalidProgress() {
        return operations().filter(arguments -> arguments.get()[1] != Phase.START)
                .flatMap(arguments -> Stream.of(ProgressFault.values()).map(fault -> {
                    Object[] values = arguments.get();
                    return Arguments.of(values[0], values[1], values[2], values[3], fault);
                }));
    }

    /// Treats invalid progress as terminal, including flush and frame-boundary loops.
    @ParameterizedTest(name = "{0}, {1}, {2}, interruptible={3}, {4}")
    @MethodSource("invalidProgress")
    void invalidProgressIsTerminal(Capability capability, Phase phase, ResourceOwnership ownership,
                                   boolean interruptible, ProgressFault fault) throws IOException {
        Engine engine = capability.newEngine();
        Target target = interruptible ? new InterruptibleTarget() : new Target();
        CompressingWritableByteChannel channel = CodecChannelAdapters.newWritableByteChannel(target, ownership, () -> engine);
        engine.progressFault = fault;
        target.zeroProgress = fault == ProgressFault.TARGET_STALL;
        assertThrows(IOException.class, () -> invoke(channel, phase, source()));
        assertFalse(channel.isOpen());
        assertEquals(1, engine.closeCalls);
        assertStopped(channel, engine, target);
    }

    /// Supplies each capability and endpoint kind for cleanup and caller-precondition tests.
    private static Stream<Arguments> channels() {
        return Stream.of(Capability.values()).flatMap(capability -> Stream.of(false, true)
                .map(interruptible -> Arguments.of(capability, interruptible)));
    }

    /// Keeps a failed operation primary while releasing the encoder once and retrying only target cleanup.
    @ParameterizedTest(name = "{0}, interruptible={1}")
    @MethodSource("channels")
    void cleanupFailuresDoNotRestartEncoding(Capability capability, boolean interruptible) throws IOException {
        Engine engine = capability.newEngine();
        Target target = interruptible ? new InterruptibleTarget() : new Target();
        CompressingWritableByteChannel channel = CodecChannelAdapters.newWritableByteChannel(
                target, ResourceOwnership.OWNED, () -> engine);
        IOException failure = new IOException("encoding failed");
        IllegalStateException releaseFailure = new IllegalStateException("engine release failed");
        IOException closeFailure = new IOException("target close failed");
        engine.failure = failure;
        engine.closeFailure = releaseFailure;
        target.closeFailure = closeFailure;
        assertSame(failure, assertThrows(IOException.class, () -> channel.write(source())));
        assertSame(releaseFailure, failure.getSuppressed()[0]);
        assertSame(closeFailure, releaseFailure.getSuppressed()[0]);
        assertFalse(channel.isOpen());
        assertTrue(target.isOpen());
        int calls = engine.calls;
        channel.close();
        channel.finish();
        assertEquals(calls, engine.calls);
        assertEquals(1, engine.closeCalls);
        assertEquals(2, target.closeCalls);
        assertFalse(target.isOpen());
    }

    /// Prevents a shared output and cleanup exception from replacing the original with self-suppression failure.
    @ParameterizedTest(name = "{0}, interruptible={1}")
    @MethodSource("channels")
    void preservesSharedFailureAcrossCloseRetries(Capability capability, boolean interruptible) throws IOException {
        Engine engine = capability.newEngine();
        Target target = interruptible ? new InterruptibleTarget() : new Target();
        CompressingWritableByteChannel channel = CodecChannelAdapters.newWritableByteChannel(
                target, ResourceOwnership.OWNED, () -> engine);
        IOException shared = new IOException("shared output and close failure");
        target.failure = shared;
        target.closeFailure = shared;
        assertSame(shared, assertThrows(IOException.class, () -> channel.write(source())));
        assertEquals(0, shared.getSuppressed().length);
        target.closeFailure = shared;
        IOException retry = assertThrows(IOException.class, channel::close);
        assertSame(shared, retry.getCause());
        channel.close();
        assertEquals(1, engine.closeCalls);
        assertEquals(3, target.closeCalls);
    }

    /// Keeps terminal cleanup failures distinct from their wrappers on subsequent close attempts.
    @ParameterizedTest(name = "{0}, interruptible={1}")
    @MethodSource("channels")
    void preservesSharedFinalizationCleanupFailure(Capability capability, boolean interruptible) throws IOException {
        for (boolean engineFails : new boolean[]{false, true}) {
            Engine engine = capability.newEngine();
            Target target = interruptible ? new InterruptibleTarget() : new Target();
            CompressingWritableByteChannel channel = CodecChannelAdapters.newWritableByteChannel(
                    target, ResourceOwnership.OWNED, () -> engine);
            IllegalStateException shared = new IllegalStateException("shared finalization cleanup failure");
            if (engineFails) {
                engine.closeFailure = shared;
            }
            target.closeFailure = shared;
            assertSame(shared, assertThrows(IllegalStateException.class, channel::finish));
            int calls = engine.calls;
            target.closeFailure = shared;
            IOException retry = assertThrows(IOException.class, channel::close);
            assertSame(shared, retry.getCause());
            channel.close();
            assertEquals(calls, engine.calls);
            assertEquals(1, engine.closeCalls);
            assertEquals(3, target.closeCalls);
        }
    }

    /// Rejects caller mistakes before entering an engine, leaving a healthy channel usable.
    @ParameterizedTest(name = "{0}, interruptible={1}")
    @MethodSource("channels")
    void callerPreconditionsDoNotAbortEncoding(Capability capability, boolean interruptible) throws IOException {
        Engine engine = capability.newEngine();
        Target target = interruptible ? new InterruptibleTarget() : new Target();
        CompressingWritableByteChannel channel = CodecChannelAdapters.newWritableByteChannel(
                target, ResourceOwnership.BORROWED, () -> engine);
        assertThrows(NullPointerException.class, () -> channel.write(null));
        assertThrows(NullPointerException.class, () -> channel.encode(null));
        if (channel instanceof CompressingWritableByteChannel.Framed framed) {
            assertThrows(NullPointerException.class, () -> framed.startFrame(null));
            assertThrows(IllegalStateException.class, framed::startFrame);
        }
        assertTrue(channel.isOpen());
        assertEquals(0, engine.calls);
        channel.encode(ByteBuffer.allocate(0));
        assertEquals(0, engine.calls);
        channel.write(source());
        channel.finish();
        assertEquals(1, engine.closeCalls);
        assertEquals(0, target.closeCalls);
        assertThrows(ClosedChannelException.class, () -> channel.encode(ByteBuffer.allocate(0)));
    }

    /// Returns a read-only direct buffer with a nonzero position and a narrowed limit.
    private static @UnmodifiableView ByteBuffer source() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(8);
        buffer.put(new byte[]{9, 9, 1, 2, 3, 4, 9, 9});
        buffer.position(2).limit(6);
        return buffer.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    /// Completes the initial frame before testing explicit frame initialization.
    private static void prepare(CompressingWritableByteChannel channel, Phase phase) throws IOException {
        if (phase == Phase.START) {
            ((CompressingWritableByteChannel.Framed) channel).finishFrame();
        }
    }

    /// Invokes one selected engine-driving operation.
    private static void invoke(CompressingWritableByteChannel channel, Phase phase, ByteBuffer source) throws IOException {
        switch (phase) {
            case WRITE -> channel.write(source);
            case FLUSH -> ((CompressingWritableByteChannel.Flushable) channel).flush();
            case FRAME -> ((CompressingWritableByteChannel.Framed) channel).finishFrame();
            case START -> ((CompressingWritableByteChannel.Framed) channel).startFrame();
            case FINISH -> channel.finish();
        }
    }

    /// Verifies that every further data operation is rejected and cleanup emits no more bytes.
    private static void assertStopped(CompressingWritableByteChannel channel, Engine engine, Target target) throws IOException {
        int calls = engine.calls;
        int writes = target.writes;
        byte[] bytes = target.bytes.toByteArray();
        long input = channel.inputBytes();
        long output = channel.outputBytes();
        assertThrows(ClosedChannelException.class, () -> channel.write(source()));
        assertThrows(ClosedChannelException.class, () -> channel.write(ByteBuffer.allocate(0)));
        assertThrows(ClosedChannelException.class, () -> channel.encode(ByteBuffer.allocate(0)));
        if (channel instanceof CompressingWritableByteChannel.Flushable flushable) {
            assertThrows(ClosedChannelException.class, flushable::flush);
        }
        if (channel instanceof CompressingWritableByteChannel.Framed framed) {
            assertThrows(ClosedChannelException.class, framed::finishFrame);
            assertThrows(ClosedChannelException.class, framed::startFrame);
        }
        channel.close();
        channel.finish();
        assertEquals(calls, engine.calls);
        assertEquals(1, engine.closeCalls);
        assertEquals(writes, target.writes);
        assertArrayEquals(bytes, target.bytes.toByteArray());
        assertEquals(input, channel.inputBytes());
        assertEquals(output, channel.outputBytes());
    }

    /// Engine-driving operations exposed by channel capabilities.
    @NotNullByDefault
    private enum Phase {
        /// Encodes source bytes.
        WRITE,
        /// Flushes a decodable boundary.
        FLUSH,
        /// Finishes one frame.
        FRAME,
        /// Starts another frame.
        START,
        /// Finishes the complete stream.
        FINISH
    }

    /// Runtime capability shapes selected by the channel factory.
    @NotNullByDefault
    private enum Capability {
        /// Encoding without optional operations.
        PLAIN,
        /// Encoding with flushing.
        FLUSHABLE,
        /// Encoding with frame boundaries.
        FRAMED,
        /// Encoding with flushing and frame boundaries.
        BOTH;

        /// Returns whether this capability exposes an operation.
        boolean supports(Phase phase) {
            return switch (phase) {
                case WRITE, FINISH -> true;
                case FLUSH -> this == FLUSHABLE || this == BOTH;
                case FRAME, START -> this == FRAMED || this == BOTH;
            };
        }

        /// Creates a recording engine with exactly this capability shape.
        Engine newEngine() {
            return switch (this) {
                case PLAIN -> new Engine();
                case FLUSHABLE -> new FlushEngine();
                case FRAMED -> new FrameEngine();
                case BOTH -> new CombinedEngine();
            };
        }
    }

    /// Checked and unchecked failures exposed by external implementations.
    @NotNullByDefault
    private enum FailureKind {
        /// An I/O failure.
        IO,
        /// An unchecked exception.
        RUNTIME,
        /// A serious failure.
        ERROR;

        /// Creates a distinct exception for one operation.
        Throwable create() {
            return switch (this) {
                case IO -> new IOException("injected encoding failure");
                case RUNTIME -> new IllegalStateException("injected encoding failure");
                case ERROR -> new AssertionError("injected encoding failure");
            };
        }
    }

    /// Engine and target protocol violations.
    @NotNullByDefault
    private enum ProgressFault {
        /// Returns a status belonging to another operation.
        OUTCOME,
        /// Requests output space without producing or consuming bytes.
        ENGINE_STALL,
        /// Returns zero from a nonempty target write.
        TARGET_STALL
    }

    /// Copies bytes and emits distinct boundary markers while recording engine access.
    @NotNullByDefault
    private static class Engine implements CompressionEncoder {
        /// Number of operations other than resource release.
        int calls;
        /// Number of release attempts.
        int closeCalls;
        /// Failure thrown by the next operation.
        @Nullable Throwable failure;
        /// Failure reported by release.
        @Nullable RuntimeException closeFailure;
        /// Whether an engine failure first changes buffer positions.
        boolean partial;
        /// Protocol violation returned instead of a normal status.
        @Nullable ProgressFault progressFault;

        /// Copies input or fails after consuming and producing one byte.
        @Override
        public CodecOutcome encode(ByteBuffer source, ByteBuffer target) throws IOException {
            calls++;
            if (failure != null) {
                if (partial) {
                    target.put(source.get());
                }
                throwFailure(failure);
            }
            if (progressFault == ProgressFault.ENGINE_STALL) {
                return CodecOutcome.NEEDS_OUTPUT;
            }
            target.put(source);
            return progressFault == ProgressFault.OUTCOME ? CodecOutcome.FINISHED : CodecOutcome.NEEDS_INPUT;
        }

        /// Emits a terminal marker.
        @Override
        public CodecOutcome finish(ByteBuffer target) throws IOException {
            return boundary(target, CodecOutcome.FINISHED, CodecOutcome.FLUSHED);
        }

        /// Emits a flush marker when the selected capability exposes flushing.
        public CodecOutcome flush(ByteBuffer target) throws IOException {
            return boundary(target, CodecOutcome.FLUSHED, CodecOutcome.FINISHED);
        }

        /// Emits a frame marker when the selected capability exposes frames.
        public CodecOutcome finishFrame(ByteBuffer target) throws IOException {
            return boundary(target, CodecOutcome.BOUNDARY_REACHED, CodecOutcome.FINISHED);
        }

        /// Records explicit initialization, optionally failing after entry into the engine.
        public void startFrame(EncodingOptions options) throws IOException {
            calls++;
            throwFailure(failure);
        }

        /// Produces a boundary or injects a failure or progress violation.
        private CodecOutcome boundary(ByteBuffer target, CodecOutcome outcome, CodecOutcome invalid) throws IOException {
            calls++;
            if (failure != null) {
                if (partial) {
                    target.put((byte) 7);
                }
                throwFailure(failure);
            }
            if (progressFault == ProgressFault.ENGINE_STALL) {
                return CodecOutcome.NEEDS_OUTPUT;
            }
            target.put((byte) 7);
            return progressFault == ProgressFault.OUTCOME ? invalid : outcome;
        }

        /// Rejects unexpected engine reuse.
        @Override
        public void reset() {
            throw new AssertionError("Unexpected encoder reset");
        }

        /// Records release without emitting bytes.
        @Override
        public void close() {
            closeCalls++;
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }

    /// Exposes flushing on the recording engine.
    @NotNullByDefault
    private static final class FlushEngine extends Engine implements CompressionEncoder.Flushable {
    }

    /// Exposes frame operations on the recording engine.
    @NotNullByDefault
    private static final class FrameEngine extends Engine implements CompressionEncoder.Framed {
    }

    /// Exposes both optional capability families on the recording engine.
    @NotNullByDefault
    private static final class CombinedEngine extends Engine implements CompressionEncoder.FlushableFramed {
    }

    /// Collects bytes while injecting recoverable target failures and close failures.
    @NotNullByDefault
    private static class Target implements WritableByteChannel {
        /// Bytes actually accepted by the endpoint.
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        /// Number of target write calls.
        int writes;
        /// Number of target close attempts.
        int closeCalls;
        /// Whether the endpoint remains open.
        boolean open = true;
        /// One-shot target write failure.
        @Nullable Throwable failure;
        /// One-shot target close failure.
        @Nullable Throwable closeFailure;
        /// Whether a failing write first consumes one byte.
        boolean partial;
        /// Whether writes return zero.
        boolean zeroProgress;

        /// Records partial or complete progress and consumes the configured write failure.
        @Override
        public int write(ByteBuffer source) throws IOException {
            writes++;
            @Nullable Throwable exception = failure;
            failure = null;
            if (exception != null) {
                if (partial) {
                    bytes.write(source.get());
                }
                throwFailure(exception);
            }
            if (zeroProgress) {
                return 0;
            }
            int count = source.remaining();
            while (source.hasRemaining()) {
                bytes.write(source.get());
            }
            return count;
        }

        /// Returns whether endpoint cleanup has completed.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Consumes a one-shot close failure or marks the endpoint closed.
        @Override
        public void close() throws IOException {
            closeCalls++;
            @Nullable Throwable exception = closeFailure;
            closeFailure = null;
            throwFailure(exception);
            open = false;
        }
    }

    /// Selects the interruptible adapter without requiring a blocking thread in fault-matrix tests.
    @NotNullByDefault
    private static final class InterruptibleTarget extends Target implements InterruptibleChannel {
    }

    /// Throws an injected exception without changing its identity or category.
    private static void throwFailure(@Nullable Throwable failure) throws IOException {
        if (failure instanceof IOException exception) {
            throw exception;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error exception) {
            throw exception;
        }
    }
}
