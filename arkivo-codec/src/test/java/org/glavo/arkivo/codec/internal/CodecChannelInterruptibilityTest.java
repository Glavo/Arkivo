// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.EncodingOptions;
import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.internal.PrefixReplayReadableByteChannel;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.Channel;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.channels.spi.AbstractInterruptibleChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies interruptible-channel capability preservation and terminal cancellation semantics.
@NotNullByDefault
final class CodecChannelInterruptibilityTest {
    /// Maximum time allowed for a test operation to enter or leave its blocking section.
    private static final long TIMEOUT_SECONDS = 5L;

    /// Verifies that adapters over ordinary channels do not advertise interruptible-channel semantics.
    @Test
    void doesNotAdvertiseInterruptibilityForPlainBackings() throws IOException {
        PlainWritableChannel target = new PlainWritableChannel();
        TrackingEncoder encoderEngine = new TrackingEncoder();
        CompressingWritableByteChannel encoder = CodecChannelAdapters.newWritableByteChannel(
                target,
                ResourceOwnership.BORROWED,
                () -> encoderEngine
        );
        try {
            assertFalse(encoder instanceof InterruptibleChannel);
        } finally {
            encoder.close();
            target.close();
        }

        PlainReadableChannel source = new PlainReadableChannel();
        TrackingDecoder decoderEngine = new TrackingDecoder();
        DecompressingReadableByteChannel decoder = CodecChannelAdapters.newReadableByteChannel(
                source,
                ResourceOwnership.BORROWED,
                () -> decoderEngine
        );
        try {
            assertFalse(decoder instanceof InterruptibleChannel);
        } finally {
            decoder.close();
            source.close();
        }
    }

    /// Verifies that direct encoding and decoding adapters retain an interruptible backing channel's capability.
    @Test
    void preservesInterruptibilityForInterruptibleBackings() throws IOException {
        BlockingWritableChannel target = new BlockingWritableChannel();
        CompressingWritableByteChannel encoder = CodecChannelAdapters.newWritableByteChannel(
                target,
                ResourceOwnership.BORROWED,
                TrackingEncoder::new
        );
        try {
            assertInstanceOf(InterruptibleChannel.class, encoder);
            encoder.close();
            assertTrue(target.isOpen());
        } finally {
            if (encoder.isOpen()) {
                encoder.close();
            }
            target.close();
        }

        BlockingReadableChannel source = new BlockingReadableChannel();
        DecompressingReadableByteChannel decoder = CodecChannelAdapters.newReadableByteChannel(
                source,
                ResourceOwnership.BORROWED,
                TrackingDecoder::new
        );
        try {
            assertInstanceOf(InterruptibleChannel.class, decoder);
            decoder.close();
            assertTrue(source.isOpen());
        } finally {
            if (decoder.isOpen()) {
                decoder.close();
            }
            source.close();
        }
    }

    /// Verifies interruptibility for each engine-capability-specific adapter path.
    @Test
    void preservesInterruptibilityAcrossAdapterCapabilities() throws IOException {
        BlockingWritableChannel dynamicallyTypedTarget = new BlockingWritableChannel();
        assertInterruptibleAdapter(
                CodecChannelAdapters.newWritableByteChannel(
                        dynamicallyTypedTarget,
                        ResourceOwnership.BORROWED,
                        CapabilityEncoder::new
                ),
                dynamicallyTypedTarget
        );

        BlockingWritableChannel flushableTarget = new BlockingWritableChannel();
        assertInterruptibleAdapter(
                CodecChannelAdapters.newFlushableWritableByteChannel(
                        flushableTarget,
                        ResourceOwnership.BORROWED,
                        CapabilityEncoder::new
                ),
                flushableTarget
        );

        BlockingWritableChannel framedTarget = new BlockingWritableChannel();
        assertInterruptibleAdapter(
                CodecChannelAdapters.newFramedWritableByteChannel(
                        framedTarget,
                        ResourceOwnership.BORROWED,
                        CapabilityEncoder::new
                ),
                framedTarget
        );

        BlockingWritableChannel flushableFramedTarget = new BlockingWritableChannel();
        assertInterruptibleAdapter(
                CodecChannelAdapters.newFlushableFramedWritableByteChannel(
                        flushableFramedTarget,
                        ResourceOwnership.BORROWED,
                        CapabilityEncoder::new
                ),
                flushableFramedTarget
        );

        BlockingReadableChannel dynamicallyTypedSource = new BlockingReadableChannel();
        assertInterruptibleAdapter(
                CodecChannelAdapters.newReadableByteChannel(
                        dynamicallyTypedSource,
                        ResourceOwnership.BORROWED,
                        CapabilityDecoder::new
                ),
                dynamicallyTypedSource
        );

        BlockingReadableChannel framedSource = new BlockingReadableChannel();
        assertInterruptibleAdapter(
                CodecChannelAdapters.newFramedReadableByteChannel(
                        framedSource,
                        ResourceOwnership.BORROWED,
                        CapabilityDecoder::new
                ),
                framedSource
        );
    }

    /// Verifies that stream-based SPI adapters do not claim channel interruption semantics they cannot preserve.
    @Test
    void streamBasedAdaptersDoNotAdvertiseInterruptibility() throws IOException {
        PlainWritableChannel plainTarget = new PlainWritableChannel();
        CompressingWritableByteChannel plainEncoder = StreamCodecAdapters.newWritableByteChannel(
                plainTarget,
                ResourceOwnership.BORROWED,
                output -> output
        );
        assertFalse(plainEncoder instanceof InterruptibleChannel);
        plainEncoder.close();
        plainTarget.close();

        PlainReadableChannel plainSource = new PlainReadableChannel();
        DecompressingReadableByteChannel plainDecoder = StreamCodecAdapters.newReadableByteChannel(
                plainSource,
                ResourceOwnership.BORROWED,
                input -> input
        );
        assertFalse(plainDecoder instanceof InterruptibleChannel);
        plainDecoder.close();
        plainSource.close();

        BlockingWritableChannel target = new BlockingWritableChannel();
        CompressingWritableByteChannel encoder = StreamCodecAdapters.newWritableByteChannel(
                target,
                ResourceOwnership.BORROWED,
                output -> output
        );
        assertFalse(encoder instanceof InterruptibleChannel);
        encoder.close();
        target.close();

        BlockingReadableChannel source = new BlockingReadableChannel();
        DecompressingReadableByteChannel decoder = StreamCodecAdapters.newReadableByteChannel(
                source,
                ResourceOwnership.BORROWED,
                input -> input
        );
        assertFalse(decoder instanceof InterruptibleChannel);
        decoder.close();
        source.close();
    }

    /// Verifies capability preservation for the JDK channel types expected in file and socket pipelines.
    @Test
    void preservesInterruptibilityForFileAndSocketChannels() throws IOException {
        Path path = Files.createTempFile("arkivo-interruptible-channel", ".bin");
        try {
            FileChannel target = FileChannel.open(path, StandardOpenOption.WRITE);
            assertInterruptibleAdapter(
                    CodecChannelAdapters.newWritableByteChannel(
                            target,
                            ResourceOwnership.BORROWED,
                            TrackingEncoder::new
                    ),
                    target
            );
        } finally {
            Files.deleteIfExists(path);
        }

        SocketChannel source = SocketChannel.open();
        assertTrue(source.isBlocking());
        assertInterruptibleAdapter(
                CodecChannelAdapters.newReadableByteChannel(
                        source,
                        ResourceOwnership.BORROWED,
                        TrackingDecoder::new
                ),
                source
        );
    }

    /// Verifies an already interrupted thread can perform an idle close that requires no backing I/O.
    @Test
    void preInterruptedIdleCloseRemainsGraceful() throws IOException {
        BlockingWritableChannel target = new BlockingWritableChannel();
        TrackingEncoder encoderEngine = new TrackingEncoder();
        CompressingWritableByteChannel encoder = CodecChannelAdapters.newWritableByteChannel(
                target,
                ResourceOwnership.BORROWED,
                () -> encoderEngine
        );
        Thread.currentThread().interrupt();
        try {
            encoder.close();
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, encoderEngine.finishCalls());
            assertEquals(1, encoderEngine.closeCalls());
            assertTrue(target.isOpen());
        } finally {
            Thread.interrupted();
            target.close();
        }
    }

    /// Verifies counters retain transport progress completed before an interruption exception.
    @Test
    void recordsPartialTransportProgressBeforeInterruption() throws IOException {
        PartiallyInterruptingWritableChannel target = new PartiallyInterruptingWritableChannel();
        CompressingWritableByteChannel encoder = CodecChannelAdapters.newWritableByteChannel(
                target,
                ResourceOwnership.BORROWED,
                TrackingEncoder::new
        );
        try {
            assertThrows(
                    ClosedByInterruptException.class,
                    () -> encoder.write(ByteBuffer.wrap(new byte[]{1, 2, 3}))
            );
            assertEquals(3L, encoder.inputBytes());
            assertEquals(1L, encoder.outputBytes());
        } finally {
            assertTrue(Thread.interrupted());
            target.close();
        }

        PartiallyInterruptingReadableChannel source = new PartiallyInterruptingReadableChannel();
        DecompressingReadableByteChannel decoder = CodecChannelAdapters.newReadableByteChannel(
                source,
                ResourceOwnership.BORROWED,
                TrackingDecoder::new
        );
        try {
            assertThrows(
                    ClosedByInterruptException.class,
                    () -> decoder.read(ByteBuffer.allocate(1))
            );
            assertEquals(1L, decoder.sourceBytes());
            assertEquals(0L, decoder.inputBytes());
            assertEquals(0L, decoder.outputBytes());
            ByteBuffer unconsumed = decoder.unconsumedInput();
            assertEquals(1, unconsumed.remaining());
            assertEquals((byte) 1, unconsumed.get());
        } finally {
            assertTrue(Thread.interrupted());
            source.close();
        }

        ImmediateReadableChannel engineSource = new ImmediateReadableChannel(new byte[]{1});
        DecompressingReadableByteChannel engineDecoder = CodecChannelAdapters.newReadableByteChannel(
                engineSource,
                ResourceOwnership.BORROWED,
                PartiallyInterruptingDecoder::new
        );
        ByteBuffer decoded = ByteBuffer.allocate(1);
        try {
            assertThrows(ClosedByInterruptException.class, () -> engineDecoder.read(decoded));
            assertEquals(1, decoded.position());
            assertEquals(1L, engineDecoder.sourceBytes());
            assertEquals(1L, engineDecoder.inputBytes());
            assertEquals(1L, engineDecoder.outputBytes());
        } finally {
            assertTrue(Thread.interrupted());
            engineSource.close();
        }
    }

    /// Verifies that a pre-existing interrupt aborts encoding and permanently closes the adapter.
    @Test
    void preInterruptedEncodingClosesWrapper() throws IOException {
        BlockingWritableChannel target = new BlockingWritableChannel();
        TrackingEncoder encoderEngine = new TrackingEncoder();
        CompressingWritableByteChannel encoder = CodecChannelAdapters.newWritableByteChannel(
                target,
                ResourceOwnership.BORROWED,
                () -> encoderEngine
        );
        try {
            Thread.currentThread().interrupt();
            assertThrows(
                    ClosedByInterruptException.class,
                    () -> encoder.write(ByteBuffer.wrap(new byte[]{1}))
            );
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(encoder.isOpen());
            assertFalse(target.isOpen());
            assertEquals(0, encoderEngine.finishCalls());
            assertEquals(1, encoderEngine.closeCalls());

            Thread.interrupted();
            encoder.close();
            assertEquals(0, encoderEngine.finishCalls());
            assertThrows(
                    ClosedChannelException.class,
                    () -> encoder.write(ByteBuffer.wrap(new byte[]{2}))
            );
        } finally {
            Thread.interrupted();
            if (encoder.isOpen()) {
                encoder.close();
            }
            target.close();
        }
    }

    /// Verifies that a pre-existing interrupt aborts decoding and permanently closes the adapter.
    @Test
    void preInterruptedDecodingClosesWrapper() throws IOException {
        BlockingReadableChannel source = new BlockingReadableChannel();
        TrackingDecoder decoderEngine = new TrackingDecoder();
        DecompressingReadableByteChannel decoder = CodecChannelAdapters.newReadableByteChannel(
                source,
                ResourceOwnership.BORROWED,
                () -> decoderEngine
        );
        try {
            Thread.currentThread().interrupt();
            assertThrows(
                    ClosedByInterruptException.class,
                    () -> decoder.read(ByteBuffer.allocate(1))
            );
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(decoder.isOpen());
            assertFalse(source.isOpen());
            assertEquals(1, decoderEngine.closeCalls());

            Thread.interrupted();
            decoder.close();
            assertThrows(
                    ClosedChannelException.class,
                    () -> decoder.read(ByteBuffer.allocate(1))
            );
        } finally {
            Thread.interrupted();
            if (decoder.isOpen()) {
                decoder.close();
            }
            source.close();
        }
    }

    /// Verifies that interrupting a blocked write aborts the encoder without emitting a terminal trailer.
    @Test
    void interruptingBlockedEncodingAbortsWithoutFinishing() throws Exception {
        BlockingWritableChannel target = new BlockingWritableChannel();
        TrackingEncoder encoderEngine = new TrackingEncoder();
        CompressingWritableByteChannel encoder = CodecChannelAdapters.newWritableByteChannel(
                target,
                ResourceOwnership.BORROWED,
                () -> encoderEngine
        );
        Operation operation = startOperation(
                "codec-interrupted-writer",
                () -> encoder.write(ByteBuffer.wrap(new byte[]{1, 2, 3}))
        );
        try {
            assertTrue(target.awaitBlocked(), "encoding did not reach the backing channel");
            operation.thread().interrupt();

            Throwable failure = awaitFailure(operation);
            assertInstanceOf(ClosedByInterruptException.class, failure);
            assertTrue(operation.thread().isInterrupted());
            assertFalse(encoder.isOpen());
            assertFalse(target.isOpen());
            assertEquals(0, encoderEngine.finishCalls());
            assertEquals(1, encoderEngine.closeCalls());

            encoder.close();
            assertEquals(0, encoderEngine.finishCalls());
            assertThrows(
                    ClosedChannelException.class,
                    () -> encoder.write(ByteBuffer.wrap(new byte[]{4}))
            );
        } finally {
            stopOperation(operation, target);
            if (encoder.isOpen()) {
                encoder.close();
            }
        }
    }

    /// Verifies that interrupting a blocked read closes both the backing source and decoding adapter.
    @Test
    void interruptingBlockedDecodingClosesWrapper() throws Exception {
        BlockingReadableChannel source = new BlockingReadableChannel();
        TrackingDecoder decoderEngine = new TrackingDecoder();
        DecompressingReadableByteChannel decoder = CodecChannelAdapters.newReadableByteChannel(
                source,
                ResourceOwnership.BORROWED,
                () -> decoderEngine
        );
        Operation operation = startOperation(
                "codec-interrupted-reader",
                () -> decoder.read(ByteBuffer.allocate(1))
        );
        try {
            assertTrue(source.awaitBlocked(), "decoding did not reach the backing channel");
            operation.thread().interrupt();

            Throwable failure = awaitFailure(operation);
            assertInstanceOf(ClosedByInterruptException.class, failure);
            assertTrue(operation.thread().isInterrupted());
            assertFalse(decoder.isOpen());
            assertFalse(source.isOpen());
            assertEquals(1, decoderEngine.closeCalls());
            assertThrows(
                    ClosedChannelException.class,
                    () -> decoder.read(ByteBuffer.allocate(1))
            );
        } finally {
            stopOperation(operation, source);
            if (decoder.isOpen()) {
                decoder.close();
            }
        }
    }

    /// Verifies that concurrent close aborts a blocked encoding operation with asynchronous-close semantics.
    @Test
    void concurrentCloseAbortsBlockedEncoding() throws Exception {
        BlockingWritableChannel target = new BlockingWritableChannel();
        TrackingEncoder encoderEngine = new TrackingEncoder();
        CompressingWritableByteChannel encoder = CodecChannelAdapters.newWritableByteChannel(
                target,
                ResourceOwnership.BORROWED,
                () -> encoderEngine
        );
        Operation operation = startOperation(
                "codec-asynchronously-closed-writer",
                () -> encoder.write(ByteBuffer.wrap(new byte[]{1, 2, 3}))
        );
        try {
            assertTrue(target.awaitBlocked(), "encoding did not reach the backing channel");
            encoder.close();

            Throwable failure = awaitFailure(operation);
            assertInstanceOf(AsynchronousCloseException.class, failure);
            assertFalse(failure instanceof ClosedByInterruptException);
            assertFalse(operation.thread().isInterrupted());
            assertFalse(encoder.isOpen());
            assertFalse(target.isOpen());
            assertEquals(0, encoderEngine.finishCalls());
            assertEquals(1, encoderEngine.closeCalls());
        } finally {
            stopOperation(operation, target);
            if (encoder.isOpen()) {
                encoder.close();
            }
        }
    }

    /// Verifies that concurrent close aborts a blocked decoding operation with asynchronous-close semantics.
    @Test
    void concurrentCloseAbortsBlockedDecoding() throws Exception {
        BlockingReadableChannel source = new BlockingReadableChannel();
        TrackingDecoder decoderEngine = new TrackingDecoder();
        DecompressingReadableByteChannel decoder = CodecChannelAdapters.newReadableByteChannel(
                source,
                ResourceOwnership.BORROWED,
                () -> decoderEngine
        );
        Operation operation = startOperation(
                "codec-asynchronously-closed-reader",
                () -> decoder.read(ByteBuffer.allocate(1))
        );
        try {
            assertTrue(source.awaitBlocked(), "decoding did not reach the backing channel");
            decoder.close();

            Throwable failure = awaitFailure(operation);
            assertInstanceOf(AsynchronousCloseException.class, failure);
            assertFalse(failure instanceof ClosedByInterruptException);
            assertFalse(operation.thread().isInterrupted());
            assertFalse(decoder.isOpen());
            assertFalse(source.isOpen());
            assertEquals(1, decoderEngine.closeCalls());
        } finally {
            stopOperation(operation, source);
            if (decoder.isOpen()) {
                decoder.close();
            }
        }
    }

    /// Verifies that concurrent close calls wait for, rather than abort, an already active graceful close.
    @Test
    void concurrentCloseDoesNotAbortGracefulFinalization() throws Exception {
        ImmediateWritableChannel target = new ImmediateWritableChannel();
        BlockingFinishEncoder encoderEngine = new BlockingFinishEncoder();
        CompressingWritableByteChannel encoder = CodecChannelAdapters.newWritableByteChannel(
                target,
                ResourceOwnership.BORROWED,
                () -> encoderEngine
        );
        CloseOperation first = startClose("codec-first-graceful-close", encoder);
        assertTrue(encoderEngine.awaitFinish(), "first close did not enter encoder finalization");
        CloseOperation second = startClose("codec-second-graceful-close", encoder);
        try {
            awaitWaiting(second.thread());
            assertTrue(target.isOpen(), "concurrent close aborted the borrowed target");

            encoderEngine.releaseFinish();
            assertTrue(first.completion().get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertTrue(second.completion().get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertTrue(target.isOpen());
            assertEquals(1, encoderEngine.finishCalls());
            assertEquals(1, encoderEngine.closeCalls());
        } finally {
            encoderEngine.releaseFinish();
            target.close();
            first.thread().join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            second.thread().join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        }
    }

    /// Verifies that a non-retryable engine-release failure is visible to the closing thread and later close calls.
    @Test
    void reportsAbortCleanupFailureToConcurrentCloser() throws Exception {
        BlockingWritableChannel target = new BlockingWritableChannel();
        FailingCloseEncoder encoderEngine = new FailingCloseEncoder();
        CompressingWritableByteChannel encoder = CodecChannelAdapters.newWritableByteChannel(
                target,
                ResourceOwnership.BORROWED,
                () -> encoderEngine
        );
        Operation operation = startOperation(
                "codec-cleanup-failing-writer",
                () -> encoder.write(ByteBuffer.wrap(new byte[]{1}))
        );
        try {
            assertTrue(target.awaitBlocked(), "encoding did not reach the backing channel");
            IllegalStateException closeFailure = assertThrows(IllegalStateException.class, encoder::close);
            assertTrue(closeFailure == encoderEngine.failure());

            Throwable operationFailure = awaitFailure(operation);
            assertInstanceOf(AsynchronousCloseException.class, operationFailure);
            assertEquals(1, operationFailure.getSuppressed().length);
            assertTrue(operationFailure.getSuppressed()[0] == closeFailure);
            assertEquals(1, encoderEngine.releaseCalls());
            assertTrue(assertThrows(IllegalStateException.class, encoder::close) == closeFailure);
        } finally {
            stopOperation(operation, target);
        }
    }

    /// Verifies checked endpoint-close failures remain retryable instead of becoming persistent cleanup failures.
    @Test
    void retriesAbortEndpointCloseFailures() throws IOException {
        RetryableCloseWritableChannel target = new RetryableCloseWritableChannel(2);
        TrackingEncoder encoderEngine = new TrackingEncoder();
        CompressingWritableByteChannel encoder = CodecChannelAdapters.newWritableByteChannel(
                target,
                ResourceOwnership.OWNED,
                () -> encoderEngine
        );

        Thread.currentThread().interrupt();
        try {
            ClosedByInterruptException failure = assertThrows(
                    ClosedByInterruptException.class,
                    () -> encoder.write(ByteBuffer.wrap(new byte[]{1}))
            );
            assertEquals(1, failure.getSuppressed().length);
            assertTrue(target.isOpen());
            assertEquals(2, target.closeCalls());
        } finally {
            assertTrue(Thread.interrupted());
        }

        encoder.close();
        assertFalse(target.isOpen());
        assertEquals(3, target.closeCalls());
        assertEquals(1, encoderEngine.closeCalls());
        encoder.close();
        assertEquals(3, target.closeCalls());
    }

    /// Verifies abortive closure crosses the borrowed ownership boundary introduced by prefix replay.
    @Test
    void forceClosesBorrowedSourceThroughPrefixReplay() throws IOException {
        ImmediateReadableChannel source = new ImmediateReadableChannel(new byte[]{2});
        ReadableByteChannel replay = PrefixReplayReadableByteChannel.create(
                ByteBuffer.wrap(new byte[]{1}),
                source,
                ResourceOwnership.BORROWED
        );
        DecompressingReadableByteChannel decoder = CodecChannelAdapters.newReadableByteChannel(
                replay,
                ResourceOwnership.OWNED,
                TrackingDecoder::new
        );
        Thread.currentThread().interrupt();
        try {
            assertThrows(ClosedByInterruptException.class, () -> decoder.read(ByteBuffer.allocate(1)));
            assertFalse(source.isOpen());
        } finally {
            assertTrue(Thread.interrupted());
            decoder.close();
        }
    }

    /// Verifies a finite output decorator still delegates empty reads to the interruptible lifecycle.
    @Test
    void limitedDecoderChecksLifecycleForEmptyReads() throws IOException {
        ImmediateReadableChannel source = new ImmediateReadableChannel(new byte[]{1});
        DecompressingReadableByteChannel decoder = CompressionDecoderSupport.limitChannelOutput(
                CodecChannelAdapters.newReadableByteChannel(
                        source,
                        ResourceOwnership.BORROWED,
                        TrackingDecoder::new
                ),
                1L
        );
        Thread.currentThread().interrupt();
        try {
            assertThrows(ClosedByInterruptException.class, () -> decoder.read(ByteBuffer.allocate(0)));
        } finally {
            assertTrue(Thread.interrupted());
        }
        assertThrows(ClosedChannelException.class, () -> decoder.read(ByteBuffer.allocate(0)));
        decoder.close();
    }

    /// Verifies capability-specific operations use the same terminal pre-interruption lifecycle as read and write.
    @Test
    void capabilityOperationsHonorPreInterruption() throws IOException {
        ImmediateWritableChannel flushTarget = new ImmediateWritableChannel();
        CompressingWritableByteChannel.FlushableFramed flushable =
                (CompressingWritableByteChannel.FlushableFramed) CodecChannelAdapters.newWritableByteChannel(
                        flushTarget,
                        ResourceOwnership.BORROWED,
                        CapabilityEncoder::new
                );
        Thread.currentThread().interrupt();
        try {
            assertThrows(ClosedByInterruptException.class, flushable::flush);
            assertFalse(flushTarget.isOpen());
        } finally {
            assertTrue(Thread.interrupted());
            flushable.close();
        }

        ImmediateWritableChannel frameTarget = new ImmediateWritableChannel();
        CompressingWritableByteChannel.Framed framedEncoder = CodecChannelAdapters.newFramedWritableByteChannel(
                frameTarget,
                ResourceOwnership.BORROWED,
                CapabilityEncoder::new
        );
        Thread.currentThread().interrupt();
        try {
            assertThrows(ClosedByInterruptException.class, framedEncoder::finishFrame);
            assertFalse(frameTarget.isOpen());
        } finally {
            assertTrue(Thread.interrupted());
            framedEncoder.close();
        }

        ImmediateReadableChannel frameSource = new ImmediateReadableChannel(new byte[]{1});
        DecompressingReadableByteChannel.Framed framedDecoder = CodecChannelAdapters.newFramedReadableByteChannel(
                frameSource,
                ResourceOwnership.BORROWED,
                CapabilityDecoder::new
        );
        Thread.currentThread().interrupt();
        try {
            assertThrows(
                    ClosedByInterruptException.class,
                    () -> framedDecoder.decodeFrame(ByteBuffer.allocate(1))
            );
            assertFalse(frameSource.isOpen());
        } finally {
            assertTrue(Thread.interrupted());
            framedDecoder.close();
        }
    }

    /// Starts an operation and captures either its failure or an assertion failure for unexpected completion.
    private static Operation startOperation(String threadName, IOOperation operation) {
        CompletableFuture<Throwable> completion = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try {
                operation.run();
                completion.complete(new AssertionError("blocking operation completed normally"));
            } catch (Throwable failure) {
                completion.complete(failure);
            }
        }, threadName);
        thread.start();
        return new Operation(thread, completion);
    }

    /// Starts a close expected to complete normally.
    private static CloseOperation startClose(String threadName, Channel channel) {
        CompletableFuture<Boolean> completion = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try {
                channel.close();
                completion.complete(Boolean.TRUE);
            } catch (Throwable failure) {
                completion.completeExceptionally(failure);
            }
        }, threadName);
        thread.start();
        return new CloseOperation(thread, completion);
    }

    /// Waits until a close worker is blocked in lifecycle coordination.
    private static void awaitWaiting(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            if (!thread.isAlive()) {
                throw new AssertionError("close worker terminated before waiting");
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
        }
        assertEquals(Thread.State.WAITING, thread.getState(), "close worker did not wait");
    }

    /// Verifies a marker-bearing adapter's ordinary close while retaining its borrowed backing channel.
    private static void assertInterruptibleAdapter(
            Channel adapter,
            AbstractInterruptibleChannel backing
    ) throws IOException {
        try {
            assertInstanceOf(InterruptibleChannel.class, adapter);
            adapter.close();
            assertTrue(backing.isOpen());
        } finally {
            if (adapter.isOpen()) {
                adapter.close();
            }
            backing.close();
        }
    }

    /// Waits for a blocking operation to terminate and returns the failure it observed.
    private static Throwable awaitFailure(Operation operation)
            throws ExecutionException, InterruptedException, TimeoutException {
        Throwable failure = operation.completion().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        operation.thread().join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        assertFalse(operation.thread().isAlive(), "blocking operation did not terminate");
        return failure;
    }

    /// Closes the test backing channel and prevents a failed test from leaving a worker blocked.
    private static void stopOperation(Operation operation, AbstractInterruptibleChannel backing)
            throws IOException, InterruptedException {
        backing.close();
        if (operation.thread().isAlive()) {
            operation.thread().interrupt();
            operation.thread().join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        }
        assertFalse(operation.thread().isAlive(), "blocking operation leaked a worker thread");
    }

    /// Executes one channel operation that may fail with an I/O exception.
    @FunctionalInterface
    @NotNullByDefault
    private interface IOOperation {
        /// Runs the operation.
        void run() throws IOException;
    }

    /// Holds a worker and the failure reported by its blocking operation.
    ///
    /// @param thread     the operation worker
    /// @param completion the captured terminal result
    @NotNullByDefault
    private record Operation(Thread thread, CompletableFuture<Throwable> completion) {
    }

    /// Holds a close worker and its successful completion signal.
    ///
    /// @param thread     the close worker
    /// @param completion the close result
    @NotNullByDefault
    private record CloseOperation(Thread thread, CompletableFuture<Boolean> completion) {
    }

    /// Implements a noninterruptible writable channel for negative capability tests.
    @NotNullByDefault
    private static final class PlainWritableChannel implements WritableByteChannel {
        /// Whether the channel remains open.
        private boolean open = true;

        /// Consumes every offered byte.
        @Override
        public int write(ByteBuffer source) throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
            int count = source.remaining();
            source.position(source.limit());
            return count;
        }

        /// Returns whether the channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes the channel.
        @Override
        public void close() {
            open = false;
        }
    }

    /// Implements a noninterruptible readable channel for negative capability tests.
    @NotNullByDefault
    private static final class PlainReadableChannel implements ReadableByteChannel {
        /// Whether the channel remains open.
        private boolean open = true;

        /// Reports physical end-of-input.
        @Override
        public int read(ByteBuffer target) throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
            return -1;
        }

        /// Returns whether the channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes the channel.
        @Override
        public void close() {
            open = false;
        }
    }

    /// Provides a close-aware blocking section for interruptible test channels.
    @NotNullByDefault
    private abstract static class BlockingChannel extends AbstractInterruptibleChannel {
        /// Signals that an operation has installed its worker and entered the blocking section.
        private final CountDownLatch blocked = new CountDownLatch(1);

        /// Worker parked by the current operation.
        private volatile @Nullable Thread blockedThread;

        /// Waits until an operation reaches the blocking section.
        protected final boolean awaitBlocked() throws InterruptedException {
            return blocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        /// Blocks until interruption or an asynchronous channel close terminates the operation.
        protected final void block() throws IOException {
            begin();
            try {
                blockedThread = Thread.currentThread();
                blocked.countDown();
                while (isOpen()) {
                    LockSupport.park(this);
                }
            } finally {
                blockedThread = null;
                end(false);
            }
            throw new ClosedChannelException();
        }

        /// Unparks the active worker so that `end(false)` can report why the operation ended.
        @Override
        protected final void implCloseChannel() {
            @Nullable Thread thread = blockedThread;
            if (thread != null) {
                LockSupport.unpark(thread);
            }
        }
    }

    /// Implements an interruptible writable channel that blocks every nonempty write.
    @NotNullByDefault
    private static final class BlockingWritableChannel extends BlockingChannel implements WritableByteChannel {
        /// Blocks instead of consuming source bytes.
        @Override
        public int write(ByteBuffer source) throws IOException {
            if (!source.hasRemaining()) {
                return 0;
            }
            block();
            throw new AssertionError("unreachable");
        }
    }

    /// Consumes bytes immediately while advertising interruption support.
    @NotNullByDefault
    private static final class ImmediateWritableChannel
            extends AbstractInterruptibleChannel
            implements WritableByteChannel {
        /// Consumes every remaining source byte.
        @Override
        public int write(ByteBuffer source) throws ClosedChannelException {
            if (!isOpen()) {
                throw new ClosedChannelException();
            }
            int count = source.remaining();
            source.position(source.limit());
            return count;
        }

        /// Releases no additional test resources.
        @Override
        protected void implCloseChannel() {
        }
    }

    /// Keeps an interruptible endpoint open until a configured number of close failures have been retried.
    @NotNullByDefault
    private static final class RetryableCloseWritableChannel
            implements WritableByteChannel, InterruptibleChannel {
        /// Number of close attempts that fail before closure succeeds.
        private final int failedCloseAttempts;

        /// Number of close attempts made while the endpoint remained open.
        private int closeCalls;

        /// Whether the endpoint remains open.
        private boolean open = true;

        /// Creates a channel with a fixed number of initial close failures.
        private RetryableCloseWritableChannel(int failedCloseAttempts) {
            this.failedCloseAttempts = failedCloseAttempts;
        }

        /// Consumes every remaining source byte while open.
        @Override
        public int write(ByteBuffer source) throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
            int count = source.remaining();
            source.position(source.limit());
            return count;
        }

        /// Returns whether the endpoint remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Fails the configured initial close attempts and then closes the endpoint.
        @Override
        public void close() throws IOException {
            if (!open) {
                return;
            }
            closeCalls++;
            if (closeCalls <= failedCloseAttempts) {
                throw new IOException("retryable close failure");
            }
            open = false;
        }

        /// Returns the number of close attempts made while open.
        private int closeCalls() {
            return closeCalls;
        }
    }

    /// Implements an interruptible readable channel that blocks every nonempty read.
    @NotNullByDefault
    private static final class BlockingReadableChannel extends BlockingChannel implements ReadableByteChannel {
        /// Blocks instead of producing source bytes.
        @Override
        public int read(ByteBuffer target) throws IOException {
            if (!target.hasRemaining()) {
                return 0;
            }
            block();
            throw new AssertionError("unreachable");
        }
    }

    /// Advances a write by one byte before reporting interruption.
    @NotNullByDefault
    private static final class PartiallyInterruptingWritableChannel
            extends AbstractInterruptibleChannel
            implements WritableByteChannel {
        /// Consumes one byte, closes this channel, and reports interruption.
        @Override
        public int write(ByteBuffer source) throws IOException {
            if (!isOpen()) {
                throw new ClosedChannelException();
            }
            source.get();
            Thread.currentThread().interrupt();
            close();
            throw new ClosedByInterruptException();
        }

        /// Releases no additional test resources.
        @Override
        protected void implCloseChannel() {
        }
    }

    /// Advances a read by one byte before reporting interruption.
    @NotNullByDefault
    private static final class PartiallyInterruptingReadableChannel
            extends AbstractInterruptibleChannel
            implements ReadableByteChannel {
        /// Produces one byte, closes this channel, and reports interruption.
        @Override
        public int read(ByteBuffer target) throws IOException {
            if (!isOpen()) {
                throw new ClosedChannelException();
            }
            target.put((byte) 1);
            Thread.currentThread().interrupt();
            close();
            throw new ClosedByInterruptException();
        }

        /// Releases no additional test resources.
        @Override
        protected void implCloseChannel() {
        }
    }

    /// Supplies fixed bytes immediately while advertising interruption support.
    @NotNullByDefault
    private static final class ImmediateReadableChannel
            extends AbstractInterruptibleChannel
            implements ReadableByteChannel {
        /// Fixed source bytes.
        private final ByteBuffer content;

        /// Creates a source over fixed bytes.
        private ImmediateReadableChannel(byte[] content) {
            this.content = ByteBuffer.wrap(content);
        }

        /// Copies available bytes without blocking.
        @Override
        public int read(ByteBuffer target) throws ClosedChannelException {
            if (!isOpen()) {
                throw new ClosedChannelException();
            }
            if (!content.hasRemaining()) {
                return -1;
            }
            int count = Math.min(content.remaining(), target.remaining());
            ByteBuffer chunk = content.slice();
            chunk.limit(count);
            target.put(chunk);
            content.position(content.position() + count);
            return count;
        }

        /// Releases no additional test resources.
        @Override
        protected void implCloseChannel() {
        }
    }

    /// Copies input to output while tracking finalization and resource release.
    @NotNullByDefault
    private static class TrackingEncoder implements CompressionEncoder {
        /// Number of terminal finalization calls.
        private final AtomicInteger finishCalls = new AtomicInteger();

        /// Number of engine release calls.
        private final AtomicInteger closeCalls = new AtomicInteger();

        /// Copies as much input as the target can accept.
        @Override
        public CodecOutcome encode(ByteBuffer source, ByteBuffer target) {
            int count = Math.min(source.remaining(), target.remaining());
            ByteBuffer chunk = source.slice();
            chunk.limit(count);
            target.put(chunk);
            source.position(source.position() + count);
            return source.hasRemaining() ? CodecOutcome.NEEDS_OUTPUT : CodecOutcome.NEEDS_INPUT;
        }

        /// Records terminal finalization without emitting bytes.
        @Override
        public CodecOutcome finish(ByteBuffer target) throws IOException {
            finishCalls.incrementAndGet();
            return CodecOutcome.FINISHED;
        }

        /// Resets no format state.
        @Override
        public void reset() {
        }

        /// Records release of the engine.
        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }

        /// Returns the number of terminal finalization calls.
        int finishCalls() {
            return finishCalls.get();
        }

        /// Returns the number of engine release calls.
        int closeCalls() {
            return closeCalls.get();
        }
    }

    /// Holds graceful finalization until a test permits it to complete.
    @NotNullByDefault
    private static final class BlockingFinishEncoder extends TrackingEncoder {
        /// Signals entry into graceful finalization.
        private final CountDownLatch finishEntered = new CountDownLatch(1);

        /// Releases graceful finalization.
        private final CountDownLatch finishReleased = new CountDownLatch(1);

        /// Waits for the first close to enter graceful finalization.
        private boolean awaitFinish() throws InterruptedException {
            return finishEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        /// Permits graceful finalization to complete.
        private void releaseFinish() {
            finishReleased.countDown();
        }

        /// Blocks before recording successful finalization.
        @Override
        public CodecOutcome finish(ByteBuffer target) throws IOException {
            finishEntered.countDown();
            try {
                if (!finishReleased.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IOException("timed out waiting to release finalization");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("finalization wait was interrupted", exception);
            }
            return super.finish(target);
        }
    }

    /// Fails the only possible engine-release attempt.
    @NotNullByDefault
    private static final class FailingCloseEncoder extends TrackingEncoder {
        /// Stable release failure used to verify propagation identity.
        private final IllegalStateException failure = new IllegalStateException("engine close failure");

        /// Number of release attempts.
        private final AtomicInteger closeCalls = new AtomicInteger();

        /// Reports a non-retryable engine-release failure.
        @Override
        public void close() {
            closeCalls.incrementAndGet();
            throw failure;
        }

        /// Returns the stable engine-release failure.
        private IllegalStateException failure() {
            return failure;
        }

        /// Returns the number of engine-release attempts.
        private int releaseCalls() {
            return closeCalls.get();
        }
    }

    /// Adds flush and frame capabilities to the tracking encoder.
    @NotNullByDefault
    private static final class CapabilityEncoder
            extends TrackingEncoder
            implements CompressionEncoder.FlushableFramed {
        /// Starts another synthetic frame without allocating state.
        @Override
        public void startFrame(EncodingOptions options) {
            Objects.requireNonNull(options, "options");
        }

        /// Reports a completed nonterminal flush without emitting bytes.
        @Override
        public CodecOutcome flush(ByteBuffer target) {
            return CodecOutcome.FLUSHED;
        }

        /// Reports a completed frame boundary without emitting bytes.
        @Override
        public CodecOutcome finishFrame(ByteBuffer target) {
            return CodecOutcome.BOUNDARY_REACHED;
        }
    }

    /// Copies input to output while tracking resource release.
    @NotNullByDefault
    private static class TrackingDecoder implements CompressionDecoder {
        /// Number of engine release calls.
        private final AtomicInteger closeCalls = new AtomicInteger();

        /// Copies as much input as the target can accept.
        @Override
        public CodecOutcome decode(ByteBuffer source, ByteBuffer target) {
            int count = Math.min(source.remaining(), target.remaining());
            ByteBuffer chunk = source.slice();
            chunk.limit(count);
            target.put(chunk);
            source.position(source.position() + count);
            return source.hasRemaining() ? CodecOutcome.NEEDS_OUTPUT : CodecOutcome.NEEDS_INPUT;
        }

        /// Copies final input and reports completion once it is exhausted.
        @Override
        public CodecOutcome finish(ByteBuffer source, ByteBuffer target) {
            int count = Math.min(source.remaining(), target.remaining());
            ByteBuffer chunk = source.slice();
            chunk.limit(count);
            target.put(chunk);
            source.position(source.position() + count);
            return source.hasRemaining() ? CodecOutcome.NEEDS_OUTPUT : CodecOutcome.FINISHED;
        }

        /// Resets no format state.
        @Override
        public void reset() {
        }

        /// Records release of the engine.
        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }

        /// Returns the number of engine release calls.
        private int closeCalls() {
            return closeCalls.get();
        }
    }

    /// Produces and consumes one byte before reporting an interrupt-shaped engine failure.
    @NotNullByDefault
    private static final class PartiallyInterruptingDecoder extends TrackingDecoder {
        /// Advances both buffers, preserves the interrupt flag, and reports a generic failure.
        @Override
        public CodecOutcome decode(ByteBuffer source, ByteBuffer target) {
            target.put(source.get());
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted decoder");
        }
    }

    /// Adds frame capability to the tracking decoder.
    @NotNullByDefault
    private static final class CapabilityDecoder
            extends TrackingDecoder
            implements CompressionDecoder.Framed {
    }
}
