// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.internal;

import org.glavo.arkivo.internal.ForceCloseable;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CodecResult;
import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.DecompressionOutputLimitException;
import org.glavo.arkivo.codec.DictionaryRequest;
import org.glavo.arkivo.codec.DictionaryRequiredException;
import org.glavo.arkivo.codec.EncodingOptions;
import org.glavo.arkivo.codec.RawCompressionDictionary;
import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.transform.TransformingReadableByteChannel;
import org.glavo.arkivo.codec.transform.TransformingWritableByteChannel;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.Channel;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.channels.spi.AbstractInterruptibleChannel;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the blocking channel adapters independently from any concrete compression algorithm.
@NotNullByDefault
final class CodecChannelAdaptersTest {
    /// Preserves read and cancellation failures when ordinary close retries an incomplete abort.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesFailureDuringCancellationRetry(boolean readFirst) throws IOException {
        for (Throwable failure : new Throwable[]{new IOException("source failure"),
                new IllegalStateException("source failure"), new AssertionError("source failure")}) {
            var source = new FailingCancellationChannel(failure);
            var decoder = new CopyDecoder();
            var channel = CodecChannelAdapters.newReadableByteChannel(source, ResourceOwnership.OWNED, () -> decoder);
            if (readFirst) {
                assertSame(failure, assertThrows(failure.getClass(), () -> channel.read(ByteBuffer.allocate(1))));
            }
            Throwable cancellation = assertThrows(Throwable.class, () -> ForceCloseable.forceClose(channel));
            if (readFirst) {
                assertInstanceOf(IOException.class, cancellation);
                assertSame(failure, cancellation.getCause());
            } else {
                assertSame(failure, cancellation);
            }
            assertFalse(channel.isOpen());
            assertEquals(0, decoder.closeCalls());
            assertSame(failure, assertThrows(failure.getClass(), () -> {
                try (channel) {
                    if (failure instanceof IOException exception) throw exception;
                    if (failure instanceof RuntimeException exception) throw exception;
                    throw (Error) failure;
                }
            }));
            assertEquals(1, failure.getSuppressed().length);
            assertSame(failure, failure.getSuppressed()[0].getCause());
            assertEquals(0, decoder.closeCalls());
            source.failure = null;
            channel.close();
            channel.close();
            assertEquals(1, decoder.closeCalls());
            assertEquals(3, source.abortCalls);
            assertEquals(0, source.closeCalls);
        }
    }

    /// Keeps source cancellation pending until the shared failure is explicitly cleared.
    @NotNullByDefault
    private static final class FailingCancellationChannel implements ReadableByteChannel, ForceCloseable {
        /// The failure repeated by reads and cancellation, or null once cancellation may succeed.
        private @Nullable Throwable failure;
        /// Number of cancellation attempts.
        private int abortCalls;
        /// Number of ordinary close attempts, which must not replace a pending cancellation.
        private int closeCalls;
        /// Whether cancellation has not yet completed.
        private boolean open = true;

        /// Creates a channel with the requested shared failure.
        private FailingCancellationChannel(Throwable failure) { this.failure = failure; }

        /// Reports the shared failure without delivering bytes.
        @Override
        public int read(ByteBuffer target) throws IOException { throwFailure(); return -1; }

        /// Returns whether cancellation has completed.
        @Override
        public boolean isOpen() { return open; }

        /// Records an unexpected ordinary close without concealing it in the cancellation count.
        @Override
        public void close() { closeCalls++; open = false; }

        /// Fails until allowed to complete, leaving the decoder's source cleanup retryable.
        @Override
        public void forceClose() throws IOException { abortCalls++; throwFailure(); open = false; }

        /// Throws the configured failure without changing its type or identity.
        private void throwFailure() throws IOException {
            if (failure instanceof IOException exception) throw exception;
            if (failure instanceof RuntimeException exception) throw exception;
            if (failure instanceof Error error) throw error;
        }
    }

    /// Forced replay closure bypasses a graceful source close that is blocked while draining input.
    @Test
    void forceClosesReplayDuringGracefulSourceClose() throws Exception {
        var endpoint = new DrainingGateChannel();
        var channel = PrefixReplayReadableByteChannel.create(ByteBuffer.allocate(0), endpoint, ResourceOwnership.OWNED);
        var close = new FutureTask<>(() -> { channel.close(); return true; });
        Thread closer = startWorker(close);
        try {
            assertTrue(endpoint.entered.await(5, TimeUnit.SECONDS));
            var force = new FutureTask<>(() -> { channel.forceClose(); return true; });
            Thread cancellation = startWorker(force);
            assertTrue(force.get(5, TimeUnit.SECONDS));
            assertInstanceOf(AsynchronousCloseException.class,
                    assertInstanceOf(java.io.UncheckedIOException.class,
                            assertThrows(ExecutionException.class,
                                    () -> close.get(5, TimeUnit.SECONDS)).getCause()).getCause());
            assertFalse(channel.isOpen());
            channel.close();
            joinWorker(cancellation);
        } finally {
            endpoint.release.countDown();
            endpoint.forceClose();
            joinWorker(closer);
        }
    }

    /// Explicit cancellation reaches the source even while ordinary decoder closure is draining it.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void forceClosesDecoderDuringGracefulSourceClose(boolean interruptible) throws Exception {
        var endpoint = interruptible ? new InterruptibleDrainingGateChannel() : new DrainingGateChannel();
        var engine = new CopyDecoder();
        var channel = CodecChannelAdapters.newReadableByteChannel(endpoint, ResourceOwnership.OWNED, () -> engine);
        var close = new FutureTask<>(() -> { channel.close(); return true; });
        var force = new FutureTask<>(() -> { ForceCloseable.forceClose(channel); return true; });
        Thread closer = startWorker(close);
        Thread cancellation = new Thread(force, "cancel-decoder-close");
        cancellation.setDaemon(true);
        try {
            assertTrue(endpoint.entered.await(5, TimeUnit.SECONDS));
            cancellation.start();
            assertTrue(force.get(5, TimeUnit.SECONDS));
            Throwable failure = assertThrows(ExecutionException.class,
                    () -> close.get(5, TimeUnit.SECONDS)).getCause();
            if (interruptible) assertInstanceOf(AsynchronousCloseException.class, failure);
            else assertInstanceOf(java.io.UncheckedIOException.class, failure);
            assertFalse(channel.isOpen());
            channel.close();
            assertEquals(1, engine.closeCalls());
        } finally {
            endpoint.forceClose();
            endpoint.release.countDown();
            joinWorker(closer);
            joinWorker(cancellation);
        }
    }

    /// Ordinary close retries cancellation after an earlier abort failed to unblock graceful source cleanup.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retriesFailedCancellationDuringGracefulSourceClose(boolean interruptible) throws Exception {
        var endpoint = interruptible ? new InterruptibleDrainingGateChannel() : new DrainingGateChannel();
        var failure = new IOException("first cancellation failed");
        endpoint.abortFailure = failure;
        var engine = new CopyDecoder();
        var channel = CodecChannelAdapters.newReadableByteChannel(endpoint, ResourceOwnership.OWNED, () -> engine);
        var close = new FutureTask<>(() -> { channel.close(); return true; });
        var retry = new FutureTask<>(() -> { channel.close(); return true; });
        Thread closer = startWorker(close);
        Thread cancellationRetry = new Thread(retry, "retry-decoder-cancellation");
        cancellationRetry.setDaemon(true);
        try {
            assertTrue(endpoint.entered.await(5, TimeUnit.SECONDS));
            assertSame(failure, assertThrows(IOException.class, () -> ForceCloseable.forceClose(channel)));
            assertFalse(channel.isOpen());
            cancellationRetry.start();
            assertTrue(retry.get(5, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> close.get(5, TimeUnit.SECONDS));
            channel.close();
            assertEquals(1, engine.closeCalls());
        } finally {
            endpoint.forceClose();
            endpoint.release.countDown();
            joinWorker(closer);
            joinWorker(cancellationRetry);
        }
    }

    /// Cancelling an idle decoder releases its engine without touching a borrowed endpoint.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void forceClosingIdleDecoderPreservesBorrowedSource(boolean interruptible) throws IOException {
        var endpoint = interruptible ? new InterruptibleDrainingGateChannel() : new DrainingGateChannel();
        var engine = new CopyDecoder();
        var channel = CodecChannelAdapters.newReadableByteChannel(endpoint, ResourceOwnership.BORROWED, () -> engine);
        try {
            ForceCloseable.forceClose(channel);
            channel.close();
            ForceCloseable.forceClose(channel);
            assertFalse(channel.isOpen());
            assertTrue(endpoint.isOpen());
            assertEquals(0, endpoint.calls.get());
            assertEquals(1, engine.closeCalls());
        } finally {
            endpoint.forceClose();
        }
    }

    /// Marks a draining source as interruptible without changing its cancellation gates.
    @NotNullByDefault
    private static final class InterruptibleDrainingGateChannel extends DrainingGateChannel
            implements InterruptibleChannel {
        /// Creates a source whose graceful close can be cancelled by another thread.
        private InterruptibleDrainingGateChannel() {
        }
    }

    /// Models a channel whose graceful close reads input and whose forced close cancels that read.
    @NotNullByDefault
    private static class DrainingGateChannel extends GateChannel implements ForceCloseable {
        /// Optional failure delivered once before cancellation can release the graceful close.
        private @Nullable IOException abortFailure;

        /// Creates an open source whose first transfer is gated.
        private DrainingGateChannel() {
        }

        @Override
        public void close() {
            try {
                read(ByteBuffer.allocate(1));
            } catch (IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        }

        /// Reports one configured failure, then releases blocked source operations on a later call.
        @Override
        public synchronized void forceClose() throws IOException {
            if (abortFailure != null) {
                IOException failure = abortFailure;
                abortFailure = null;
                throw failure;
            }
            super.close();
        }
    }

    /// Concurrent writes and composite operations retain their own progress and boundary output.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void serializesEncodingOperations(boolean interruptible) throws Exception {
        for (int mode : new int[]{0, 1, 2, 3}) {
            var target = interruptible ? new InterruptibleGateChannel() : new GateChannel();
            var engine = new CapabilityEncoder();
            var channel = CodecChannelAdapters.newFlushableFramedWritableByteChannel(
                    target, ResourceOwnership.BORROWED, () -> engine);
            var first = new FutureTask<>(() -> encodeOperation(channel, ByteBuffer.wrap(new byte[]{'a'}), mode));
            var second = new FutureTask<>(() -> encodeOperation(channel,
                    ByteBuffer.allocateDirect(2).put((byte) 'b').put((byte) 'c').flip().asReadOnlyBuffer(), mode));
            Thread firstThread = startWorker(first);
            Thread secondThread = new Thread(second, "queued-codec-write");
            secondThread.setDaemon(true);
            try {
                assertTrue(target.entered.await(5, TimeUnit.SECONDS));
                secondThread.start();
                awaitBlocked(secondThread);
                assertEquals(1, target.calls.get());
                target.release.countDown();
                CodecResult firstResult = first.get(5, TimeUnit.SECONDS);
                CodecResult secondResult = second.get(5, TimeUnit.SECONDS);
                assertEquals(1L, firstResult.inputBytes());
                assertEquals(2L, secondResult.inputBytes());
                if (mode != 0) {
                    assertEquals(mode >= 2 ? 2L : 1L, firstResult.outputBytes());
                    assertEquals(mode >= 2 ? 3L : 2L, secondResult.outputBytes());
                }
                channel.close();
                String expected = mode == 2 ? "aFbcFT" : mode == 3 ? "aBbcB" : "abcT";
                assertArrayEquals(expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII), target.output.toByteArray());
                assertEquals(1, engine.closeCalls());
            } finally {
                target.release.countDown();
                joinWorker(firstThread);
                joinWorker(secondThread);
                channel.close();
            }
        }
    }

    /// Read, decode, and frame-decode share one allowance, including the excess-byte probe.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void serializesDecodingAndOutputLimits(boolean interruptible) throws Exception {
        for (int firstMode : new int[]{0, 1, 2}) {
            for (int secondMode : new int[]{0, 1, 2}) {
                for (long limit : new long[]{-1, 0, 1}) {
                    var source = interruptible ? new InterruptibleGateChannel() : new GateChannel();
                    var engine = new FrameByteDecoder();
                    var channel = CompressionDecoderSupport.limitChannelOutput(
                            CodecChannelAdapters.newFramedReadableByteChannel(source, ResourceOwnership.BORROWED, () -> engine), limit);
                    ByteBuffer firstBuffer = ByteBuffer.allocate(1);
                    ByteBuffer secondBuffer = ByteBuffer.allocateDirect(1);
                    var first = new FutureTask<>(() -> decodeOperation(channel, firstBuffer, firstMode));
                    var second = new FutureTask<>(() -> decodeOperation(channel, secondBuffer, secondMode));
                    Thread firstThread = startWorker(first);
                    Thread secondThread = new Thread(second, "queued-codec-read");
                    secondThread.setDaemon(true);
                    try {
                        assertTrue(source.entered.await(5, TimeUnit.SECONDS));
                        secondThread.start();
                        awaitBlocked(secondThread);
                        assertEquals(1, source.calls.get());
                        source.release.countDown();
                        if (limit == 0) {
                            assertInstanceOf(DecompressionOutputLimitException.class,
                                    assertThrows(ExecutionException.class, () -> first.get(5, TimeUnit.SECONDS)).getCause());
                        } else {
                            assertEquals(1L, first.get(5, TimeUnit.SECONDS));
                            assertEquals('a', firstBuffer.get(0));
                        }
                        if (limit >= 0) {
                            assertInstanceOf(DecompressionOutputLimitException.class,
                                    assertThrows(ExecutionException.class, () -> second.get(5, TimeUnit.SECONDS)).getCause());
                            assertEquals(0, secondBuffer.position());
                            assertEquals(limit, channel.outputBytes());
                            assertEquals(limit + 1, channel.inputBytes());
                        } else {
                            assertEquals(1L, second.get(5, TimeUnit.SECONDS));
                            assertEquals('b', secondBuffer.get(0));
                            assertEquals(2L, channel.outputBytes());
                        }
                        assertEquals(1, firstBuffer.limit());
                        assertEquals(1, secondBuffer.limit());
                    } finally {
                        source.release.countDown();
                        joinWorker(firstThread);
                        joinWorker(secondThread);
                        channel.close();
                    }
                }
            }
        }
    }

    /// Cancellation reaches the endpoint while another data operation waits for serialization.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closesInterruptibleChannelWithQueuedOperation(boolean writing) throws Exception {
        var endpoint = new InterruptibleGateChannel();
        var encoder = new CopyEncoder();
        var decoder = new CopyDecoder();
        Channel channel = writing
                ? CodecChannelAdapters.newWritableByteChannel(endpoint, ResourceOwnership.BORROWED, () -> encoder)
                : CompressionDecoderSupport.limitChannelOutput(CodecChannelAdapters.newReadableByteChannel(
                        endpoint, ResourceOwnership.BORROWED, () -> decoder), 1);
        var first = new FutureTask<>(() -> writing
                ? ((WritableByteChannel) channel).write(ByteBuffer.wrap(new byte[]{1}))
                : ((ReadableByteChannel) channel).read(ByteBuffer.allocate(1)));
        var second = new FutureTask<>(() -> writing
                ? ((WritableByteChannel) channel).write(ByteBuffer.wrap(new byte[]{2}))
                : ((ReadableByteChannel) channel).read(ByteBuffer.allocate(1)));
        Thread firstThread = startWorker(first);
        Thread secondThread = new Thread(second, "queued-codec-cancellation");
        secondThread.setDaemon(true);
        try {
            assertTrue(endpoint.entered.await(5, TimeUnit.SECONDS));
            secondThread.start();
            awaitBlocked(secondThread);
            channel.close();
            assertInstanceOf(AsynchronousCloseException.class,
                    assertThrows(ExecutionException.class, () -> first.get(5, TimeUnit.SECONDS)).getCause());
            assertEquals(ClosedChannelException.class,
                    assertThrows(ExecutionException.class, () -> second.get(5, TimeUnit.SECONDS)).getCause().getClass());
            assertEquals(1, writing ? encoder.closeCalls() : decoder.closeCalls());
        } finally {
            endpoint.release.countDown();
            joinWorker(firstThread);
            joinWorker(secondThread);
            channel.close();
        }
    }

    /// Transformation shares its staging buffer across complete calls, including final tail output.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void serializesTransformOperations(boolean writing) throws Exception {
        var endpoint = new GateChannel();
        var input = new TransformingReadableByteChannel(endpoint, (bytes, offset, length) -> length);
        var output = new TransformingWritableByteChannel(endpoint, (bytes, offset, length) -> length - 1);
        ByteBuffer firstBuffer = ByteBuffer.allocate(1);
        ByteBuffer secondBuffer = ByteBuffer.allocateDirect(1);
        var first = new FutureTask<>(() -> writing
                ? output.write(ByteBuffer.wrap(new byte[]{'a', 'b'})) : input.read(firstBuffer));
        var second = new FutureTask<>(() -> {
            if (writing) {
                output.finish();
                return 0;
            }
            return input.read(secondBuffer);
        });
        Thread firstThread = startWorker(first);
        Thread secondThread = new Thread(second, "queued-transform-operation");
        secondThread.setDaemon(true);
        try {
            assertTrue(endpoint.entered.await(5, TimeUnit.SECONDS));
            secondThread.start();
            awaitBlocked(secondThread);
            assertEquals(1, endpoint.calls.get());
            endpoint.release.countDown();
            assertEquals(writing ? 2 : 1, first.get(5, TimeUnit.SECONDS));
            assertEquals(writing ? 0 : 1, second.get(5, TimeUnit.SECONDS));
            if (writing) {
                output.finish();
                assertArrayEquals(new byte[]{'a', 'b'}, endpoint.output.toByteArray());
            } else {
                assertEquals('a', firstBuffer.get(0));
                assertEquals('b', secondBuffer.get(0));
            }
        } finally {
            endpoint.release.countDown();
            joinWorker(firstThread);
            joinWorker(secondThread);
            output.close();
            input.close();
        }
    }

    /// Prefix replay serializes source access without preventing cancellation of an active read.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void serializesPrefixReplay(boolean interruptible) throws Exception {
        for (boolean cancel : new boolean[]{false, true}) {
            if (cancel && !interruptible) continue;
            var endpoint = interruptible ? new InterruptibleGateChannel() : new GateChannel();
            var channel = PrefixReplayReadableByteChannel.create(
                    ByteBuffer.wrap(new byte[]{'p'}), endpoint, ResourceOwnership.BORROWED);
            ByteBuffer prefix = ByteBuffer.allocate(1);
            assertEquals(1, channel.read(prefix));
            assertEquals('p', prefix.get(0));
            assertEquals(0, endpoint.calls.get());
            ByteBuffer firstBuffer = ByteBuffer.allocate(1);
            ByteBuffer secondBuffer = ByteBuffer.allocateDirect(1);
            var first = new FutureTask<>(() -> channel.read(firstBuffer));
            var second = new FutureTask<>(() -> channel.read(secondBuffer));
            Thread firstThread = startWorker(first);
            Thread secondThread = new Thread(second, "queued-prefix-read");
            secondThread.setDaemon(true);
            try {
                assertTrue(endpoint.entered.await(5, TimeUnit.SECONDS));
                secondThread.start();
                awaitBlocked(secondThread);
                assertEquals(1, endpoint.calls.get());
                if (cancel) {
                    channel.close();
                    assertInstanceOf(AsynchronousCloseException.class,
                            assertThrows(ExecutionException.class, () -> first.get(5, TimeUnit.SECONDS)).getCause());
                    assertEquals(ClosedChannelException.class,
                            assertThrows(ExecutionException.class, () -> second.get(5, TimeUnit.SECONDS)).getCause().getClass());
                } else {
                    endpoint.release.countDown();
                    assertEquals(1, first.get(5, TimeUnit.SECONDS));
                    assertEquals(1, second.get(5, TimeUnit.SECONDS));
                    assertEquals('a', firstBuffer.get(0));
                    assertEquals('b', secondBuffer.get(0));
                }
            } finally {
                endpoint.release.countDown();
                joinWorker(firstThread);
                joinWorker(secondThread);
                channel.close();
            }
        }
    }

    /// Closing a transforming reader does not wait for its I/O monitor before closing an owned source.
    @Test
    void closesTransformDuringRead() throws Exception {
        var source = new GateChannel();
        var channel = new TransformingReadableByteChannel(source, (bytes, offset, length) -> length, ResourceOwnership.OWNED);
        var read = new FutureTask<>(() -> channel.read(ByteBuffer.allocate(1)));
        Thread worker = startWorker(read);
        try {
            assertTrue(source.entered.await(5, TimeUnit.SECONDS));
            channel.close();
            assertInstanceOf(AsynchronousCloseException.class,
                    assertThrows(ExecutionException.class, () -> read.get(5, TimeUnit.SECONDS)).getCause());
            assertFalse(channel.isOpen());
        } finally {
            source.release.countDown();
            joinWorker(worker);
            channel.close();
        }
    }

    /// Invokes one encoding entry point for the concurrency matrix.
    private static CodecResult encodeOperation(CompressingWritableByteChannel.FlushableFramed channel,
                                               ByteBuffer source, int mode) throws IOException {
        return switch (mode) {
            case 0 -> new CodecResult(channel.write(source), 0, CodecResult.Status.ACTIVE);
            case 1 -> channel.encode(source);
            case 2 -> channel.flush(source);
            case 3 -> channel.finishFrame(source);
            default -> throw new AssertionError(mode);
        };
    }

    /// Invokes one decoding entry point for the concurrency matrix.
    private static long decodeOperation(DecompressingReadableByteChannel.Framed channel,
                                        ByteBuffer target, int mode) throws IOException {
        return switch (mode) {
            case 0 -> channel.read(target);
            case 1 -> channel.decode(target).outputBytes();
            case 2 -> channel.decodeFrame(target).outputBytes();
            default -> throw new AssertionError(mode);
        };
    }

    /// Starts a bounded test operation on a daemon worker.
    private static Thread startWorker(FutureTask<?> task) {
        Thread thread = new Thread(task, "active-codec-operation");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /// Requires an operation to wait for a monitor rather than return or enter the endpoint.
    private static void awaitBlocked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.BLOCKED) {
            assertTrue(thread.isAlive(), "operation returned instead of waiting");
            assertTrue(System.nanoTime() < deadline, "operation did not wait for serialization");
            Thread.sleep(1L);
        }
    }

    /// Joins a released worker and checks that the test leaves no active operation.
    private static void joinWorker(Thread thread) throws InterruptedException {
        thread.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(thread.isAlive(), "codec worker leaked");
    }

    /// Pauses the first endpoint transfer so competing operations can be observed deterministically.
    @NotNullByDefault
    private static class GateChannel implements ReadableByteChannel, WritableByteChannel {
        /// Signals entry into the first transfer.
        final CountDownLatch entered = new CountDownLatch(1);
        /// Releases the first transfer.
        final CountDownLatch release = new CountDownLatch(1);
        /// Counts endpoint transfers before they can block.
        final AtomicInteger calls = new AtomicInteger();
        /// Collected output, inspected after workers complete.
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        /// Input returned to the decoder or transform.
        private final ByteBuffer input = ByteBuffer.wrap(new byte[]{'a', 'b'});
        /// Whether the endpoint accepts another transfer.
        private volatile boolean open = true;

        /// Gates the first transfer and rejects asynchronous closure.
        private void enter() throws IOException {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Transfer was not released");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Transfer interrupted", exception);
                }
            }
            if (!open) throw new AsynchronousCloseException();
        }

        /// Supplies all input that fits after the gate opens.
        @Override
        public int read(ByteBuffer target) throws IOException {
            enter();
            return input.hasRemaining() ? copyBytes(input, target) : -1;
        }

        /// Collects all remaining output after the gate opens.
        @Override
        public int write(ByteBuffer source) throws IOException {
            enter();
            byte[] bytes = new byte[source.remaining()];
            source.get(bytes);
            output.writeBytes(bytes);
            return bytes.length;
        }

        /// Returns whether the endpoint remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Unblocks a pending transfer and closes the endpoint.
        @Override
        public void close() {
            open = false;
            release.countDown();
        }
    }

    /// Selects interruptible adapter behavior for the gated endpoint.
    @NotNullByDefault
    private static final class InterruptibleGateChannel extends GateChannel implements InterruptibleChannel {
    }

    /// Cleanup retries cannot rethrow a retained target failure already delivered by an earlier close.
    @Test
    void preservesTargetFailureAfterDistinctEncodingFailure() throws IOException {
        for (Throwable cleanup : new Throwable[]{new IOException("cleanup"),
                new IllegalStateException("cleanup"), new AssertionError("cleanup")}) {
            var target = new CollectingWritableChannel();
            var writeFailure = new IOException("write");
            target.writeFailure = writeFailure;
            target.closeFailures.add(cleanup);
            target.closeFailures.add(cleanup);
            target.closeFailures.add(new IOException("intervening cleanup"));
            target.closeFailures.add(cleanup);
            var encoder = new CopyEncoder();
            var channel = CodecChannelAdapters.newWritableByteChannel(target, ResourceOwnership.OWNED, () -> encoder);
            assertSame(writeFailure, assertThrows(IOException.class, () -> channel.write(ByteBuffer.wrap(new byte[]{1}))));
            assertSame(cleanup, writeFailure.getSuppressed()[0]);
            assertSame(cleanup, assertThrows(cleanup.getClass(), () -> {
                try (channel) {
                    try {
                        channel.close();
                    } catch (IOException | RuntimeException | Error first) {
                        assertEquals("intervening cleanup", assertThrows(IOException.class, channel::close).getCause().getMessage());
                        throw first;
                    }
                }
            }));
            assertSame(cleanup, cleanup.getSuppressed()[0].getCause());
            channel.close();
            channel.close();
            assertEquals(5, target.closeCalls());
            assertEquals(1, encoder.closeCalls());
            assertEquals(1, target.writeCalls());
        }
    }

    /// Automatic source cleanup cannot replace a reported read failure with self-suppression.
    @Test
    void preservesReadFailureWhenOwnedSourceCloseRepeatsIt() throws IOException {
        for (Throwable failure : new Throwable[]{new IOException("read"),
                new IllegalStateException("read"), new AssertionError("read")}) {
            var source = new ChunkedReadableChannel(new byte[]{1});
            source.readFailure = failure;
            source.closeFailures.add(failure);
            CopyDecoder decoder = new CopyDecoder();
            var channel = CodecChannelAdapters.newReadableByteChannel(source, ResourceOwnership.OWNED, () -> decoder);
            assertSame(failure, assertThrows(failure.getClass(), () -> {
                try (channel) {
                    channel.read(ByteBuffer.allocate(1));
                }
            }));
            assertEquals(1, failure.getSuppressed().length);
            assertSame(failure, failure.getSuppressed()[0].getCause());
            assertFalse(channel.isOpen());
            assertTrue(source.isOpen());
            channel.close();
            assertFalse(source.isOpen());
            assertEquals(2, source.closeCalls());
            assertEquals(1, decoder.closeCalls());
        }
    }

    /// Source cleanup retries use distinct exceptions even when the delegate alternates old failures.
    @Test
    void preservesAlternatingSourceCloseFailures() throws IOException {
        for (Throwable first : new Throwable[]{new IOException("first"),
                new IllegalStateException("first"), new AssertionError("first")}) {
            Throwable second = new IOException("second");
            var source = new ChunkedReadableChannel(new byte[0]);
            source.closeFailures.add(first);
            source.closeFailures.add(second);
            source.closeFailures.add(first);
            CopyDecoder decoder = new CopyDecoder();
            var channel = CodecChannelAdapters.newReadableByteChannel(source, ResourceOwnership.OWNED, () -> decoder);
            assertSame(first, assertThrows(first.getClass(), channel::close));
            assertSame(second, assertThrows(IOException.class, channel::close).getCause());
            assertSame(first, assertThrows(IOException.class, channel::close).getCause());
            channel.close();
            channel.close();
            assertEquals(4, source.closeCalls());
            assertEquals(1, decoder.closeCalls());
        }
    }

    /// Recording a delivered source failure does not make a recoverable read terminal.
    @Test
    void resumesReadsBeforeRetryingOwnedSourceCleanup() throws IOException {
        IOException failure = new IOException("transient source failure");
        var source = new ChunkedReadableChannel(new byte[]{1, 2});
        source.readFailure = failure;
        source.closeFailures.add(failure);
        var channel = CodecChannelAdapters.newReadableByteChannel(source, ResourceOwnership.OWNED, CopyDecoder::new);
        ByteBuffer target = ByteBuffer.allocate(2);
        assertSame(failure, assertThrows(IOException.class, () -> channel.read(target)));
        assertTrue(channel.isOpen());
        assertEquals(2, channel.read(target));
        assertArrayEquals(new byte[]{1, 2}, target.array());
        assertEquals(2, channel.inputBytes());
        assertEquals(2, channel.outputBytes());
        assertEquals(2, channel.sourceBytes());
        assertSame(failure, assertThrows(IOException.class, channel::close).getCause());
        channel.close();
    }

    /// A later recoverable read failure cannot hide an earlier exception from cleanup protection.
    @Test
    void preservesEarlierReadFailureAfterAnotherReadFails() throws IOException {
        IOException first = new IOException("first read");
        IOException second = new IOException("second read");
        var source = new ChunkedReadableChannel(new byte[0]);
        source.closeFailures.add(first);
        var channel = CodecChannelAdapters.newReadableByteChannel(source, ResourceOwnership.OWNED, CopyDecoder::new);
        assertSame(first, assertThrows(IOException.class, () -> {
            try (channel) {
                source.readFailure = first;
                assertSame(first, assertThrows(IOException.class, () -> channel.read(ByteBuffer.allocate(1))));
                source.readFailure = second;
                assertSame(second, assertThrows(IOException.class, () -> channel.read(ByteBuffer.allocate(1))));
                throw first;
            }
        }));
        assertEquals(1, first.getSuppressed().length);
        assertSame(first, first.getSuppressed()[0].getCause());
        channel.close();
    }

    /// Incomplete fields survive compacting, small staging buffers, and a transient source failure.
    @Test
    void preservesLookaheadAcrossRefillsAndFailures() throws IOException {
        for (int capacity : new int[]{1, 2, 3, 8}) {
            for (boolean direct : new boolean[]{false, true}) {
                var source = new ChunkedReadableChannel(new byte[]{1, 2, 3, 4, 5}, 1, 0, 0);
                source.failurePosition = 2;
                ByteBuffer storage = direct ? ByteBuffer.allocateDirect(capacity) : ByteBuffer.allocate(capacity);
                try (var channel = CodecChannelAdapters.newReadableByteChannel(
                        source, ResourceOwnership.BORROWED, PrefixDecoder::new, storage)) {
                    ByteBuffer target = ByteBuffer.allocate(4);
                    assertThrows(IOException.class, () -> channel.read(target));
                    assertEquals(0, channel.inputBytes());
                    assertEquals(2, channel.sourceBytes());
                    assertArrayEquals(new byte[]{1, 2}, remainingBytes(channel.unconsumedInput()));
                    assertEquals(4, channel.read(target));
                    assertArrayEquals(new byte[]{1, 2, 3, 4}, target.array());
                    assertEquals(4, channel.inputBytes());
                    assertEquals(4, channel.sourceBytes());
                    assertEquals(-1, channel.read(ByteBuffer.allocate(1)));
                    assertEquals(4, source.content.position());
                }
            }
        }
    }

    /// EOF preserves the incomplete prefix for truncation reporting instead of silently dropping it.
    @Test
    void preservesLookaheadAtEndOfInput() throws IOException {
        try (var channel = CodecChannelAdapters.newReadableByteChannel(
                new ChunkedReadableChannel(new byte[]{1, 2, 3}, 1, 0, 0),
                ResourceOwnership.BORROWED, PrefixDecoder::new)) {
            IOException failure = assertThrows(IOException.class, () -> channel.read(ByteBuffer.allocate(4)));
            assertEquals("Truncated four-byte field: 3", failure.getMessage());
            assertEquals(0, channel.inputBytes());
            assertEquals(3, channel.sourceBytes());
            assertArrayEquals(new byte[]{1, 2, 3}, remainingBytes(channel.unconsumedInput()));
        }
    }

    /// Reusing output storage preserves bytes and capabilities without reactivating a closed adapter.
    @Test
    void reusesExclusiveOutputStorageAcrossFramesAndChannels() throws IOException {
        for (ByteBuffer storage : new ByteBuffer[]{ByteBuffer.allocate(1), ByteBuffer.allocateDirect(3)}) {
            storage.position(storage.capacity());
            var firstTarget = new CollectingWritableChannel();
            var first = (CompressingWritableByteChannel.FlushableFramed) CodecChannelAdapters.newWritableByteChannel(
                    firstTarget, ResourceOwnership.BORROWED, CapabilityEncoder::new, storage);
            first.write(ByteBuffer.wrap(new byte[]{1, 2, 3, 4}));
            first.flush();
            first.finishFrame();
            first.startFrame(EncodingOptions.DEFAULT);
            first.write(ByteBuffer.wrap(new byte[]{5}));
            first.close();
            assertArrayEquals(new byte[]{1, 2, 3, 4, 'F', 'B', 5, 'T'}, firstTarget.bytes());
            assertTrue(firstTarget.isOpen());

            var secondTarget = new CollectingWritableChannel();
            try (var second = CodecChannelAdapters.newWritableByteChannel(
                    secondTarget, ResourceOwnership.OWNED, CopyEncoder::new, storage)) {
                assertEquals(0, second.inputBytes());
                assertEquals(0, second.outputBytes());
                second.write(ByteBuffer.wrap(new byte[]{6, 7}));
                assertThrows(ClosedChannelException.class, () -> first.write(ByteBuffer.wrap(new byte[]{8})));
                first.close();
                assertTrue(second.isOpen());
            }
            assertFalse(secondTarget.isOpen());
            assertArrayEquals(new byte[]{6, 7, 'T'}, secondTarget.bytes());
        }
    }

    /// Invalid output storage is rejected before creating an engine or taking target ownership.
    @Test
    void rejectsReadOnlyAndEmptyOutputStorage() {
        for (ByteBuffer storage : new ByteBuffer[]{ByteBuffer.allocate(0), ByteBuffer.allocate(8).asReadOnlyBuffer()}) {
            var target = new CollectingWritableChannel();
            assertThrows(IllegalArgumentException.class, () -> CodecChannelAdapters.newWritableByteChannel(
                    target, ResourceOwnership.OWNED, () -> { throw new AssertionError("Factory was called"); }, storage));
            assertTrue(target.isOpen());
        }
    }

    /// A caller-supplied input workspace may be reused only after its preceding adapter is closed.
    @Test
    void reusesExclusiveInputStorageWithoutReactivatingClosedAdapter() throws IOException {
        ByteBuffer storage = ByteBuffer.allocate(8);
        DecompressingReadableByteChannel first = CodecChannelAdapters.newReadableByteChannel(
                new ChunkedReadableChannel(new byte[]{10, 20, 30}), ResourceOwnership.OWNED,
                SingleByteDecoder::new, storage);
        assertEquals(1, first.read(ByteBuffer.allocate(4)));
        assertArrayEquals(new byte[]{20, 30}, remainingBytes(first.unconsumedInput()));
        assertTrue(first.unconsumedInput().isReadOnly());
        first.close();
        try (var second = CodecChannelAdapters.newReadableByteChannel(
                new ChunkedReadableChannel(new byte[]{40, 50}), ResourceOwnership.OWNED,
                SingleByteDecoder::new, storage)) {
            assertEquals(0, second.sourceBytes());
            ByteBuffer output = ByteBuffer.allocate(4);
            assertEquals(1, second.read(output));
            assertEquals(40, output.get(0));
            assertArrayEquals(new byte[]{50}, remainingBytes(second.unconsumedInput()));
            assertThrows(ClosedChannelException.class, () -> first.read(output));
            first.close();
            assertTrue(second.isOpen());
        }
    }

    /// Invalid leased storage is rejected before creating an engine or changing source ownership.
    @Test
    void rejectsReadOnlyAndEmptyInputStorage() {
        for (ByteBuffer input : new ByteBuffer[]{ByteBuffer.allocate(0), ByteBuffer.allocate(8).asReadOnlyBuffer()}) {
            var source = new ChunkedReadableChannel(new byte[]{1});
            assertThrows(IllegalArgumentException.class, () -> CodecChannelAdapters.newReadableByteChannel(
                    source, ResourceOwnership.OWNED, () -> { throw new AssertionError("Factory was called"); }, input));
            assertTrue(source.isOpen());
        }
    }

    /// Verifies factory results expose exactly the capabilities implemented by their engines.
    @Test
    void selectsDeclaredCapabilitiesForPlainEndpoints() throws IOException {
        CollectingWritableChannel target = new CollectingWritableChannel();

        CompressingWritableByteChannel plain = CodecChannelAdapters.newWritableByteChannel(
                target,
                ResourceOwnership.BORROWED,
                CopyEncoder::new
        );
        assertFalse(plain instanceof CompressingWritableByteChannel.Flushable);
        assertFalse(plain instanceof CompressingWritableByteChannel.Framed);
        plain.close();

        CompressingWritableByteChannel flushOnly = CodecChannelAdapters.newWritableByteChannel(
                target,
                ResourceOwnership.BORROWED,
                FlushOnlyEncoder::new
        );
        assertInstanceOf(CompressingWritableByteChannel.Flushable.class, flushOnly);
        assertFalse(flushOnly instanceof CompressingWritableByteChannel.Framed);
        ((CompressingWritableByteChannel.Flushable) flushOnly).flush();
        flushOnly.close();

        CompressingWritableByteChannel frameOnly = CodecChannelAdapters.newWritableByteChannel(
                target,
                ResourceOwnership.BORROWED,
                FrameOnlyEncoder::new
        );
        assertInstanceOf(CompressingWritableByteChannel.Framed.class, frameOnly);
        assertFalse(frameOnly instanceof CompressingWritableByteChannel.Flushable);
        frameOnly.close();

        CompressingWritableByteChannel both = CodecChannelAdapters.newWritableByteChannel(
                target,
                ResourceOwnership.BORROWED,
                CapabilityEncoder::new
        );
        assertInstanceOf(CompressingWritableByteChannel.FlushableFramed.class, both);
        both.close();

        CompressingWritableByteChannel.Flushable explicitFlushOnly =
                CodecChannelAdapters.newFlushableWritableByteChannel(
                        target,
                        ResourceOwnership.BORROWED,
                        FlushOnlyEncoder::new
                );
        assertFalse(explicitFlushOnly instanceof CompressingWritableByteChannel.Framed);
        explicitFlushOnly.close();

        CompressingWritableByteChannel.Flushable explicitFlushBoth =
                CodecChannelAdapters.newFlushableWritableByteChannel(
                        target,
                        ResourceOwnership.BORROWED,
                        CapabilityEncoder::new
                );
        assertInstanceOf(CompressingWritableByteChannel.FlushableFramed.class, explicitFlushBoth);
        explicitFlushBoth.close();

        CompressingWritableByteChannel.Framed explicitFrameOnly =
                CodecChannelAdapters.newFramedWritableByteChannel(
                        target,
                        ResourceOwnership.BORROWED,
                        FrameOnlyEncoder::new
                );
        assertFalse(explicitFrameOnly instanceof CompressingWritableByteChannel.Flushable);
        explicitFrameOnly.close();

        CompressingWritableByteChannel.Framed explicitFrameBoth =
                CodecChannelAdapters.newFramedWritableByteChannel(
                        target,
                        ResourceOwnership.BORROWED,
                        CapabilityEncoder::new
                );
        assertInstanceOf(CompressingWritableByteChannel.FlushableFramed.class, explicitFrameBoth);
        explicitFrameBoth.close();

        CompressingWritableByteChannel.FlushableFramed explicitBoth =
                CodecChannelAdapters.newFlushableFramedWritableByteChannel(
                        target,
                        ResourceOwnership.BORROWED,
                        CapabilityEncoder::new
                );
        explicitBoth.close();
        assertTrue(target.isOpen());

        ChunkedReadableChannel source = new ChunkedReadableChannel(new byte[0]);
        DecompressingReadableByteChannel plainDecoder = CodecChannelAdapters.newReadableByteChannel(
                source,
                ResourceOwnership.BORROWED,
                CopyDecoder::new
        );
        assertFalse(plainDecoder instanceof DecompressingReadableByteChannel.Framed);
        plainDecoder.close();

        DecompressingReadableByteChannel framedDecoder = CodecChannelAdapters.newReadableByteChannel(
                source,
                ResourceOwnership.BORROWED,
                FrameByteDecoder::new
        );
        assertInstanceOf(DecompressingReadableByteChannel.Framed.class, framedDecoder);
        framedDecoder.close();

        DecompressingReadableByteChannel.Framed explicitFramedDecoder =
                CodecChannelAdapters.newFramedReadableByteChannel(
                        source,
                        ResourceOwnership.BORROWED,
                        FrameByteDecoder::new
                );
        explicitFramedDecoder.close();
        assertTrue(source.isOpen());
    }

    /// Verifies construction failure applies endpoint ownership and preserves failure composition.
    @Test
    void appliesEndpointOwnershipWhenEngineCreationFails() throws IOException {
        IOException encoderFailure = new IOException("encoder creation failed");
        CollectingWritableChannel ownedTarget = new CollectingWritableChannel();
        IOException thrownEncoderFailure = assertThrows(
                IOException.class,
                () -> CodecChannelAdapters.newWritableByteChannel(
                        ownedTarget,
                        ResourceOwnership.OWNED,
                        () -> {
                            throw encoderFailure;
                        }
                )
        );
        assertSame(encoderFailure, thrownEncoderFailure);
        assertFalse(ownedTarget.isOpen());
        assertEquals(1, ownedTarget.closeCalls());

        CollectingWritableChannel borrowedTarget = new CollectingWritableChannel();
        assertSame(
                encoderFailure,
                assertThrows(
                        IOException.class,
                        () -> CodecChannelAdapters.newWritableByteChannel(
                                borrowedTarget,
                                ResourceOwnership.BORROWED,
                                () -> {
                                    throw encoderFailure;
                                }
                        )
                )
        );
        assertTrue(borrowedTarget.isOpen());
        assertEquals(0, borrowedTarget.closeCalls());

        CollectingWritableChannel failingTarget = new CollectingWritableChannel(3, 0, 1);
        IOException failureWithClose = assertThrows(
                IOException.class,
                () -> CodecChannelAdapters.newWritableByteChannel(
                        failingTarget,
                        ResourceOwnership.OWNED,
                        () -> {
                            throw encoderFailure;
                        }
                )
        );
        assertSame(encoderFailure, failureWithClose);
        assertEquals(1, failureWithClose.getSuppressed().length);
        assertEquals("writable close failed", failureWithClose.getSuppressed()[0].getMessage());
        assertTrue(failingTarget.isOpen());
        failingTarget.close();

        IOException sharedCreationFailure = new IOException("shared creation and close failure");
        CollectingWritableChannel sharedFailureTarget =
                new CollectingWritableChannel(3, 0, 1, sharedCreationFailure);
        IOException sharedFailure = assertThrows(
                IOException.class,
                () -> CodecChannelAdapters.newWritableByteChannel(
                        sharedFailureTarget,
                        ResourceOwnership.OWNED,
                        () -> {
                            throw sharedCreationFailure;
                        }
                )
        );
        assertSame(sharedCreationFailure, sharedFailure);
        assertEquals(0, sharedFailure.getSuppressed().length);
        assertTrue(sharedFailureTarget.isOpen());
        sharedFailureTarget.close();

        CollectingWritableChannel nullTarget = new CollectingWritableChannel();
        assertThrows(
                NullPointerException.class,
                () -> CodecChannelAdapters.newWritableByteChannel(
                        nullTarget,
                        ResourceOwnership.OWNED,
                        () -> null
                )
        );
        assertFalse(nullTarget.isOpen());

        IOException decoderFailure = new IOException("decoder creation failed");
        ChunkedReadableChannel ownedSource = new ChunkedReadableChannel(new byte[0]);
        assertSame(
                decoderFailure,
                assertThrows(
                        IOException.class,
                        () -> CodecChannelAdapters.newReadableByteChannel(
                                ownedSource,
                                ResourceOwnership.OWNED,
                                () -> {
                                    throw decoderFailure;
                                }
                        )
                )
        );
        assertFalse(ownedSource.isOpen());
        assertEquals(1, ownedSource.closeCalls());
    }

    /// Verifies large writes, partial target progress, byte counters, and terminal finalization.
    @Test
    void encodesLargeInputsAndTracksPartialTransportProgress() throws IOException {
        byte[] input = new byte[9_000];
        for (int index = 0; index < input.length; index++) {
            input[index] = (byte) index;
        }
        CopyEncoder encoder = new CopyEncoder();
        CollectingWritableChannel target = new CollectingWritableChannel(3, 0, 0);
        CompressingWritableByteChannel channel = CodecChannelAdapters.newWritableByteChannel(
                target,
                ResourceOwnership.BORROWED,
                () -> encoder
        );

        assertEquals(0, channel.write(ByteBuffer.allocate(0)));
        assertEquals(0, encoder.encodeCalls());
        ByteBuffer source = ByteBuffer.wrap(input);
        assertEquals(input.length, channel.write(source));
        assertFalse(source.hasRemaining());
        assertEquals(input.length, channel.inputBytes());
        assertEquals(input.length, channel.outputBytes());
        assertTrue(target.writeCalls() > 2_000);

        channel.finish();
        channel.finish();
        assertFalse(channel.isOpen());
        assertTrue(target.isOpen());
        assertEquals(input.length + 1L, channel.outputBytes());
        assertEquals(1, encoder.finishCalls());
        assertEquals(1, encoder.closeCalls());
        assertThrows(ClosedChannelException.class, () -> channel.write(ByteBuffer.wrap(new byte[]{1})));

        byte[] expected = Arrays.copyOf(input, input.length + 1);
        expected[input.length] = CopyEncoder.TRAILER;
        assertArrayEquals(expected, target.bytes());
    }

    /// Verifies frame and flush operations enforce their lifecycle and avoid duplicate boundaries.
    @Test
    void enforcesFramedEncoderLifecycle() throws IOException {
        CapabilityEncoder encoder = new CapabilityEncoder();
        CollectingWritableChannel target = new CollectingWritableChannel();
        CompressingWritableByteChannel.FlushableFramed channel = assertInstanceOf(
                CompressingWritableByteChannel.FlushableFramed.class,
                CodecChannelAdapters.newWritableByteChannel(
                        target,
                        ResourceOwnership.BORROWED,
                        () -> encoder
                )
        );

        assertThrows(IllegalStateException.class, () -> channel.startFrame(EncodingOptions.DEFAULT));
        channel.flush();
        channel.finishFrame();
        channel.finishFrame();
        channel.flush();
        assertEquals(1, encoder.flushCalls());
        assertEquals(1, encoder.frameFinishCalls());

        EncodingOptions options = EncodingOptions.ofSourceSize(1);
        channel.startFrame(options);
        assertSame(options, encoder.lastOptions());
        assertThrows(IllegalStateException.class, () -> channel.startFrame(EncodingOptions.DEFAULT));
        assertEquals(1, channel.write(ByteBuffer.wrap(new byte[]{7})));
        channel.finishFrame();
        channel.finish();
        assertEquals(2, encoder.frameFinishCalls());
        assertEquals(0, encoder.finishCalls());
        assertEquals(1, encoder.closeCalls());
        assertArrayEquals(new byte[]{'F', 'B', 7, 'B'}, target.bytes());
        assertThrows(ClosedChannelException.class, channel::flush);
        assertThrows(ClosedChannelException.class, channel::finishFrame);
        assertThrows(ClosedChannelException.class, channel::startFrame);

        CapabilityEncoder emptyFrameEncoder = new CapabilityEncoder();
        CollectingWritableChannel emptyFrameTarget = new CollectingWritableChannel();
        CompressingWritableByteChannel.Framed emptyFrameChannel =
                CodecChannelAdapters.newFramedWritableByteChannel(
                        emptyFrameTarget,
                        ResourceOwnership.BORROWED,
                        () -> emptyFrameEncoder
                );
        emptyFrameChannel.finishFrame();
        emptyFrameChannel.startFrame();
        emptyFrameChannel.finish();
        assertEquals(1, emptyFrameEncoder.startCalls());
        assertEquals(1, emptyFrameEncoder.finishCalls());
        assertArrayEquals(new byte[]{'B', CopyEncoder.TRAILER}, emptyFrameTarget.bytes());
    }

    /// Verifies invalid encoder outcomes and zero-progress targets fail instead of spinning.
    @Test
    void rejectsInvalidEncoderAndTargetProgress() throws IOException {
        assertEncodingFailure(
                new OutcomeEncoder(CodecOutcome.NEEDS_INPUT, false, false),
                "Compression encoder requested input before consuming its source buffer"
        );
        assertEncodingFailure(
                new OutcomeEncoder(CodecOutcome.NEEDS_OUTPUT, false, false),
                "Compression encoder made no progress"
        );
        assertEncodingFailure(
                new OutcomeEncoder(CodecOutcome.FINISHED, false, false),
                "Unexpected compression encode outcome: FINISHED"
        );

        OutcomeEncoder producingEncoder = new OutcomeEncoder(CodecOutcome.NEEDS_INPUT, true, true);
        CollectingWritableChannel zeroTarget = new CollectingWritableChannel(8, 1, 0);
        CompressingWritableByteChannel zeroProgressChannel = CodecChannelAdapters.newWritableByteChannel(
                zeroTarget,
                ResourceOwnership.BORROWED,
                () -> producingEncoder
        );
        IOException zeroProgress = assertThrows(
                IOException.class,
                () -> zeroProgressChannel.write(ByteBuffer.wrap(new byte[]{1}))
        );
        assertEquals("Compression target channel made no progress", zeroProgress.getMessage());
        assertEquals(1, zeroProgressChannel.inputBytes());
        assertEquals(0, zeroProgressChannel.outputBytes());
        zeroProgressChannel.close();

        TerminalOutcomeEncoder noTerminalOutput = new TerminalOutcomeEncoder(CodecOutcome.NEEDS_OUTPUT);
        CompressingWritableByteChannel terminalChannel = CodecChannelAdapters.newWritableByteChannel(
                new CollectingWritableChannel(),
                ResourceOwnership.BORROWED,
                () -> noTerminalOutput
        );
        IOException terminalFailure = assertThrows(IOException.class, terminalChannel::finish);
        assertEquals("Compression encoder requested output without producing bytes", terminalFailure.getMessage());
        assertFalse(terminalChannel.isOpen());
        assertEquals(1, noTerminalOutput.closeCalls());

        TerminalOutcomeEncoder unexpectedTerminal = new TerminalOutcomeEncoder(CodecOutcome.FLUSHED);
        CompressingWritableByteChannel unexpectedTerminalChannel = CodecChannelAdapters.newWritableByteChannel(
                new CollectingWritableChannel(),
                ResourceOwnership.BORROWED,
                () -> unexpectedTerminal
        );
        IOException unexpectedTerminalFailure = assertThrows(
                IOException.class,
                unexpectedTerminalChannel::finish
        );
        assertEquals("Unexpected compression finish outcome: FLUSHED", unexpectedTerminalFailure.getMessage());

        BadCapabilityEncoder badFlush = new BadCapabilityEncoder(CodecOutcome.NEEDS_INPUT, CodecOutcome.BOUNDARY_REACHED);
        CompressingWritableByteChannel.Flushable flushChannel =
                CodecChannelAdapters.newFlushableWritableByteChannel(
                        new CollectingWritableChannel(),
                        ResourceOwnership.BORROWED,
                        () -> badFlush
                );
        IOException flushFailure = assertThrows(IOException.class, flushChannel::flush);
        assertEquals("Unexpected compression flush outcome: NEEDS_INPUT", flushFailure.getMessage());
        flushChannel.close();

        BadCapabilityEncoder emptyFlush = new BadCapabilityEncoder(
                CodecOutcome.NEEDS_OUTPUT,
                CodecOutcome.BOUNDARY_REACHED
        );
        CompressingWritableByteChannel.Flushable emptyFlushChannel =
                CodecChannelAdapters.newFlushableWritableByteChannel(
                        new CollectingWritableChannel(),
                        ResourceOwnership.BORROWED,
                        () -> emptyFlush
                );
        IOException emptyFlushFailure = assertThrows(IOException.class, emptyFlushChannel::flush);
        assertEquals("Compression encoder requested output without producing bytes", emptyFlushFailure.getMessage());
        emptyFlushChannel.close();

        BadCapabilityEncoder badFrame = new BadCapabilityEncoder(CodecOutcome.FLUSHED, CodecOutcome.FINISHED);
        CompressingWritableByteChannel.Framed frameChannel = CodecChannelAdapters.newFramedWritableByteChannel(
                new CollectingWritableChannel(),
                ResourceOwnership.BORROWED,
                () -> badFrame
        );
        IOException frameFailure = assertThrows(IOException.class, frameChannel::finishFrame);
        assertEquals("Unexpected compression frame outcome: FINISHED", frameFailure.getMessage());
        frameChannel.close();

        BadCapabilityEncoder emptyFrame = new BadCapabilityEncoder(CodecOutcome.FLUSHED, CodecOutcome.NEEDS_OUTPUT);
        CompressingWritableByteChannel.Framed emptyFrameChannel = CodecChannelAdapters.newFramedWritableByteChannel(
                new CollectingWritableChannel(),
                ResourceOwnership.BORROWED,
                () -> emptyFrame
        );
        IOException emptyFrameFailure = assertThrows(IOException.class, emptyFrameChannel::finishFrame);
        assertEquals("Compression encoder requested output without producing bytes", emptyFrameFailure.getMessage());
        emptyFrameChannel.close();
    }

    /// Verifies valid multi-step output and input-free progress are drained to completion.
    @Test
    void drainsMultiStepEncoderOperationsAndInputFreePreamble() throws IOException {
        PreambleEncoder preambleEncoder = new PreambleEncoder();
        CollectingWritableChannel preambleTarget = new CollectingWritableChannel();
        CompressingWritableByteChannel preambleChannel = CodecChannelAdapters.newWritableByteChannel(
                preambleTarget,
                ResourceOwnership.BORROWED,
                () -> preambleEncoder
        );
        assertEquals(1, preambleChannel.write(ByteBuffer.wrap(new byte[]{7})));
        preambleChannel.finish();
        assertArrayEquals(new byte[]{'P', 7, CopyEncoder.TRAILER}, preambleTarget.bytes());

        MultiStepCapabilityEncoder encoder = new MultiStepCapabilityEncoder();
        CollectingWritableChannel target = new CollectingWritableChannel();
        CompressingWritableByteChannel.FlushableFramed channel =
                CodecChannelAdapters.newFlushableFramedWritableByteChannel(
                        target,
                        ResourceOwnership.BORROWED,
                        () -> encoder
                );
        channel.flush();
        channel.finishFrame();
        channel.startFrame();
        channel.finish();
        assertArrayEquals(new byte[]{'F', 'f', 'B', 'b', 'T', 't'}, target.bytes());
        assertEquals(2, encoder.flushCalls());
        assertEquals(2, encoder.frameCalls());
        assertEquals(2, encoder.terminalFinishCalls());
    }

    /// Verifies capability-specific operations execute normally through interruptible wrappers.
    @Test
    void executesCapabilityOperationsThroughInterruptibleWrappers() throws IOException {
        ImmediateInterruptibleWritableChannel flushTarget = new ImmediateInterruptibleWritableChannel();
        CompressingWritableByteChannel.Flushable flushChannel = assertInstanceOf(
                CompressingWritableByteChannel.Flushable.class,
                CodecChannelAdapters.newWritableByteChannel(
                        flushTarget,
                        ResourceOwnership.BORROWED,
                        FlushOnlyEncoder::new
                )
        );
        flushChannel.flush();
        flushChannel.close();
        assertTrue(flushTarget.isOpen());

        ImmediateInterruptibleWritableChannel explicitFlushTarget = new ImmediateInterruptibleWritableChannel();
        CompressingWritableByteChannel.Flushable explicitFlush =
                CodecChannelAdapters.newFlushableWritableByteChannel(
                        explicitFlushTarget,
                        ResourceOwnership.BORROWED,
                        FlushOnlyEncoder::new
                );
        explicitFlush.flush();
        explicitFlush.close();
        assertTrue(explicitFlushTarget.isOpen());

        ImmediateInterruptibleWritableChannel frameTarget = new ImmediateInterruptibleWritableChannel();
        CompressingWritableByteChannel.Framed frameChannel = assertInstanceOf(
                CompressingWritableByteChannel.Framed.class,
                CodecChannelAdapters.newWritableByteChannel(
                        frameTarget,
                        ResourceOwnership.BORROWED,
                        FrameOnlyEncoder::new
                )
        );
        frameChannel.finishFrame();
        frameChannel.startFrame();
        frameChannel.finishFrame();
        frameChannel.close();
        assertTrue(frameTarget.isOpen());

        ImmediateInterruptibleWritableChannel explicitFrameTarget = new ImmediateInterruptibleWritableChannel();
        CompressingWritableByteChannel.Framed explicitFrame =
                CodecChannelAdapters.newFramedWritableByteChannel(
                        explicitFrameTarget,
                        ResourceOwnership.BORROWED,
                        FrameOnlyEncoder::new
                );
        explicitFrame.finishFrame();
        explicitFrame.startFrame();
        explicitFrame.finishFrame();
        explicitFrame.close();
        assertTrue(explicitFrameTarget.isOpen());

        ImmediateInterruptibleReadableChannel source = new ImmediateInterruptibleReadableChannel(new byte[]{1});
        DecompressingReadableByteChannel.Framed decoder =
                CodecChannelAdapters.newFramedReadableByteChannel(
                        source,
                        ResourceOwnership.BORROWED,
                        FrameByteDecoder::new
                );
        CodecResult boundary = decoder.decodeFrame(ByteBuffer.allocate(1));
        assertEquals(CodecResult.Status.FRAME_FINISHED, boundary.status());
        decoder.close();
        assertTrue(source.isOpen());
    }

    /// Rejects read-only targets without consuming input or preventing a subsequent writable read.
    @Test
    void rejectsReadOnlyDecodingTargetsWithoutProgress() throws IOException {
        for (int size : new int[]{0, 8}) {
            for (boolean direct : new boolean[]{false, true}) {
                ChunkedReadableChannel source = new ChunkedReadableChannel(new byte[]{10, 20});
                FrameByteDecoder engine = new FrameByteDecoder();
                var channel = CodecChannelAdapters.newFramedReadableByteChannel(
                        source, ResourceOwnership.BORROWED, () -> engine);
                ByteBuffer target = (direct ? ByteBuffer.allocateDirect(size) : ByteBuffer.allocate(size))
                        .asReadOnlyBuffer();
                assertThrows(IllegalArgumentException.class, () -> channel.read(target));
                assertThrows(IllegalArgumentException.class, () -> channel.decode(target));
                assertThrows(IllegalArgumentException.class, () -> channel.decodeFrame(target));
                assertEquals(0, target.position());
                assertEquals(size, target.limit());
                assertEquals(0, source.readCalls());
                assertEquals(0, engine.decodeCalls());
                assertEquals(0L, channel.inputBytes());
                assertEquals(0L, channel.sourceBytes());
                assertEquals(0L, channel.outputBytes());
                ByteBuffer writable = ByteBuffer.allocate(8);
                assertEquals(2, channel.read(writable));
                writable.flip();
                assertArrayEquals(new byte[]{10, 20}, remainingBytes(writable));
                assertEquals(-1, channel.read(writable.clear()));
                assertThrows(IllegalArgumentException.class, () -> channel.read(target));
                assertThrows(IllegalArgumentException.class, () -> channel.decode(target));
                assertThrows(IllegalArgumentException.class, () -> channel.decodeFrame(target));
                channel.close();
                assertThrows(ClosedChannelException.class, () -> channel.read(target));
                assertThrows(ClosedChannelException.class, () -> channel.decode(target));
                assertThrows(ClosedChannelException.class, () -> channel.decodeFrame(target));
            }
        }
    }

    /// Verifies read-ahead, cumulative counters, empty targets, and stable single-frame completion.
    @Test
    void decodesWithReadAheadCountersAndStableCompletion() throws IOException {
        byte[] encoded = {1, 2, 3, 4, 5};
        ChunkedReadableChannel source = new ChunkedReadableChannel(encoded, 2, 0, 0);
        CopyDecoder decoder = new CopyDecoder();
        DecompressingReadableByteChannel channel = CodecChannelAdapters.newReadableByteChannel(
                source,
                ResourceOwnership.BORROWED,
                () -> decoder
        );

        assertEquals(0, channel.read(ByteBuffer.allocate(0)));
        assertEquals(0, source.readCalls());
        assertEquals(CodecResult.Status.ACTIVE, channel.decode(ByteBuffer.allocate(0)).status());
        assertEquals(0, source.readCalls());
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        ByteBuffer firstTarget = ByteBuffer.allocate(1);
        CodecResult first = channel.decode(firstTarget);
        assertEquals(CodecResult.Status.ACTIVE, first.status());
        assertEquals(1, first.inputBytes());
        assertEquals(1, first.outputBytes());
        assertEquals(1, channel.inputBytes());
        assertEquals(2, channel.sourceBytes());
        ByteBuffer unconsumed = channel.unconsumedInput();
        assertTrue(unconsumed.isReadOnly());
        assertArrayEquals(new byte[]{2}, remainingBytes(unconsumed));
        decoded.writeBytes(firstTarget.array());

        while (true) {
            ByteBuffer target = ByteBuffer.allocate(1);
            CodecResult result = channel.decode(target);
            target.flip();
            decoded.writeBytes(remainingBytes(target));
            if (result.status() == CodecResult.Status.END_OF_INPUT) {
                break;
            }
        }
        assertArrayEquals(encoded, decoded.toByteArray());
        assertEquals(encoded.length, channel.inputBytes());
        assertEquals(encoded.length, channel.sourceBytes());
        assertEquals(encoded.length, channel.outputBytes());
        assertEquals(-1, channel.read(ByteBuffer.allocate(1)));
        channel.close();
        assertEquals(1, decoder.closeCalls());
        assertTrue(source.isOpen());
        assertThrows(ClosedChannelException.class, () -> channel.read(ByteBuffer.allocate(0)));

        ChunkedReadableChannel trailingSource = new ChunkedReadableChannel(new byte[]{10, 20, 30});
        SingleByteDecoder singleDecoder = new SingleByteDecoder();
        DecompressingReadableByteChannel single = CodecChannelAdapters.newReadableByteChannel(
                trailingSource,
                ResourceOwnership.BORROWED,
                () -> singleDecoder
        );
        ByteBuffer target = ByteBuffer.allocate(4);
        assertEquals(1, single.read(target));
        assertEquals(-1, single.read(target));
        assertEquals(1, single.inputBytes());
        assertEquals(3, single.sourceBytes());
        assertArrayEquals(new byte[]{20, 30}, remainingBytes(single.unconsumedInput()));
        single.close();
    }

    /// Verifies framed decoding reports each boundary and resets only before a following frame.
    @Test
    void reportsFramedBoundariesAndResetsBetweenFrames() throws IOException {
        ChunkedReadableChannel source = new ChunkedReadableChannel(new byte[]{10, 20});
        FrameByteDecoder decoder = new FrameByteDecoder();
        DecompressingReadableByteChannel.Framed channel =
                CodecChannelAdapters.newFramedReadableByteChannel(
                        source,
                        ResourceOwnership.BORROWED,
                        () -> decoder
                );
        ByteBuffer target = ByteBuffer.allocate(4);

        CodecResult first = channel.decodeFrame(target);
        assertEquals(CodecResult.Status.FRAME_FINISHED, first.status());
        assertEquals(1, first.inputBytes());
        assertEquals(1, first.outputBytes());
        assertArrayEquals(new byte[]{20}, remainingBytes(channel.unconsumedInput()));

        ByteBuffer readOnly = ByteBuffer.allocate(1).asReadOnlyBuffer();
        int readsAtBoundary = source.readCalls();
        assertThrows(IllegalArgumentException.class, () -> channel.read(readOnly));
        assertThrows(IllegalArgumentException.class, () -> channel.decode(readOnly));
        assertThrows(IllegalArgumentException.class, () -> channel.decodeFrame(readOnly));
        assertEquals(readsAtBoundary, source.readCalls());
        assertEquals(0, decoder.resetCalls());
        assertEquals(1, channel.inputBytes());
        assertEquals(1, channel.outputBytes());

        CodecResult second = channel.decodeFrame(target);
        assertEquals(CodecResult.Status.FRAME_FINISHED, second.status());
        assertEquals(1, decoder.resetCalls());
        CodecResult end = channel.decodeFrame(target);
        assertEquals(CodecResult.Status.END_OF_INPUT, end.status());
        assertEquals(1, decoder.resetCalls());
        assertEquals(
                CodecResult.Status.END_OF_INPUT,
                channel.decodeFrame(ByteBuffer.allocate(1)).status()
        );
        target.flip();
        assertArrayEquals(new byte[]{10, 20}, remainingBytes(target));
        channel.close();

        FrameByteDecoder concatenatingDecoder = new FrameByteDecoder();
        DecompressingReadableByteChannel.Framed concatenating =
                CodecChannelAdapters.newFramedReadableByteChannel(
                        new ChunkedReadableChannel(new byte[]{3, 4}),
                        ResourceOwnership.BORROWED,
                        () -> concatenatingDecoder
                );
        ByteBuffer firstConcatenatedTarget = ByteBuffer.allocate(1);
        CodecResult firstConcatenated = concatenating.decode(firstConcatenatedTarget);
        assertEquals(CodecResult.Status.ACTIVE, firstConcatenated.status());
        ByteBuffer secondConcatenatedTarget = ByteBuffer.allocate(1);
        CodecResult secondConcatenated = concatenating.decode(secondConcatenatedTarget);
        assertEquals(CodecResult.Status.ACTIVE, secondConcatenated.status());
        CodecResult concatenatedEnd = concatenating.decode(ByteBuffer.allocate(1));
        assertEquals(CodecResult.Status.END_OF_INPUT, concatenatedEnd.status());
        assertArrayEquals(new byte[]{3}, firstConcatenatedTarget.array());
        assertArrayEquals(new byte[]{4}, secondConcatenatedTarget.array());
        assertEquals(1, concatenatingDecoder.resetCalls());
        concatenating.close();

        FrameByteDecoder emptyDecoder = new FrameByteDecoder();
        DecompressingReadableByteChannel.Framed empty = CodecChannelAdapters.newFramedReadableByteChannel(
                new ChunkedReadableChannel(new byte[0]),
                ResourceOwnership.BORROWED,
                () -> emptyDecoder
        );
        assertEquals(
                CodecResult.Status.END_OF_INPUT,
                empty.decodeFrame(ByteBuffer.allocate(1)).status()
        );
        assertEquals(0, emptyDecoder.decodeCalls());
        empty.close();
    }

    /// Verifies invalid decoder outcomes, dictionary requests, and zero-progress sources fail deterministically.
    @Test
    void rejectsInvalidDecoderAndSourceProgress() throws IOException {
        assertDecodingFailure(
                new OutcomeDecoder(CodecOutcome.NEEDS_INPUT, false, false),
                "Compression decoder requested input after end of input"
        );
        assertDecodingFailure(
                new OutcomeDecoder(CodecOutcome.NEEDS_OUTPUT, false, false),
                "Compression decoder requested output without filling its target buffer"
        );
        assertDecodingFailure(
                new OutcomeDecoder(CodecOutcome.FLUSHED, false, false),
                "Unexpected compression decode outcome: FLUSHED"
        );
        assertDecodingFailure(
                new OutcomeDecoder(CodecOutcome.NEEDS_DICTIONARY, false, false),
                "Compression decoder requested a dictionary without exposing its request"
        );

        DictionaryOutcomeDecoder dictionaryDecoder = new DictionaryOutcomeDecoder();
        DecompressingReadableByteChannel dictionaryChannel = CodecChannelAdapters.newReadableByteChannel(
                new ChunkedReadableChannel(new byte[]{1}),
                ResourceOwnership.BORROWED,
                () -> dictionaryDecoder
        );
        DictionaryRequiredException dictionaryFailure = assertThrows(
                DictionaryRequiredException.class,
                () -> dictionaryChannel.decode(ByteBuffer.allocate(1))
        );
        assertSame(dictionaryDecoder.request(), dictionaryFailure.request());
        dictionaryChannel.close();

        OutcomeDecoder untouchedDecoder = new OutcomeDecoder(CodecOutcome.NEEDS_INPUT, true, true);
        ChunkedReadableChannel zeroSource = new ChunkedReadableChannel(new byte[]{1}, 1, 1, 0);
        DecompressingReadableByteChannel zeroChannel = CodecChannelAdapters.newReadableByteChannel(
                zeroSource,
                ResourceOwnership.BORROWED,
                () -> untouchedDecoder
        );
        IOException zeroFailure = assertThrows(
                IOException.class,
                () -> zeroChannel.decode(ByteBuffer.allocate(1))
        );
        assertEquals("Compression source channel made no progress", zeroFailure.getMessage());
        assertEquals(0, untouchedDecoder.decodeCalls());
        zeroChannel.close();
    }

    /// Zero-progress reads leave no staged bytes and do not prevent a later retry.
    @Test
    void resumesDecodingAfterZeroProgressRead() throws IOException {
        for (boolean partial : new boolean[]{false, true}) {
            ChunkedReadableChannel source = new ChunkedReadableChannel(new byte[]{1, 2}, 1, 0, 0);
            try (var channel = CodecChannelAdapters.newReadableByteChannel(
                    source, ResourceOwnership.BORROWED, CopyDecoder::new)) {
                ByteBuffer target = ByteBuffer.allocate(4);
                if (partial) {
                    assertEquals(1, channel.read(target));
                }
                source.zeroReadsRemaining = 1;
                assertThrows(IOException.class, () -> channel.read(target));
                assertEquals(partial ? 1 : 0, channel.sourceBytes());
                assertEquals(channel.sourceBytes(), channel.inputBytes());
                assertEquals(0, channel.unconsumedInput().remaining());
                int count;
                while ((count = channel.read(target)) >= 0) {
                    assertTrue(count > 0, "Decoding must make progress after the transient failure");
                }
                target.flip();
                assertArrayEquals(new byte[]{1, 2}, remainingBytes(target));
                assertEquals(2, channel.sourceBytes());
                assertEquals(2, channel.inputBytes());
                assertEquals(2, channel.outputBytes());
            }
        }
    }

    /// A failed next-frame probe retains the verified boundary until data or physical EOF is observed.
    @Test
    void retriesInputProbeAtFrameBoundary() throws IOException {
        for (boolean nextFrame : new boolean[]{false, true}) {
            for (boolean zeroRead : new boolean[]{false, true}) {
                byte[] bytes = nextFrame ? new byte[]{1, 2} : new byte[]{1};
                ChunkedReadableChannel source = new ChunkedReadableChannel(bytes, 1, 0, 0);
                FrameByteDecoder decoder = new FrameByteDecoder();
                try (var channel = CodecChannelAdapters.newFramedReadableByteChannel(
                        source, ResourceOwnership.BORROWED, () -> decoder)) {
                    ByteBuffer target = ByteBuffer.allocate(4);
                    assertEquals(CodecResult.Status.FRAME_FINISHED, channel.decodeFrame(target).status());
                    if (zeroRead) {
                        source.zeroReadsRemaining = 1;
                    } else {
                        source.readFailure = new IOException("frame probe failed");
                    }
                    assertThrows(IOException.class, () -> channel.decodeFrame(target));
                    assertEquals(0, channel.unconsumedInput().remaining());
                    assertEquals(0, decoder.resetCalls());
                    assertEquals(nextFrame ? CodecResult.Status.FRAME_FINISHED : CodecResult.Status.END_OF_INPUT,
                            channel.decodeFrame(target).status());
                    assertEquals(CodecResult.Status.END_OF_INPUT, channel.decodeFrame(target).status());
                    assertEquals(nextFrame ? 1 : 0, decoder.resetCalls());
                    target.flip();
                    assertArrayEquals(bytes, remainingBytes(target));
                }
            }
        }
    }

    /// Verifies decoder input obtained before a source exception remains observable and can be resumed.
    @Test
    void preservesPartiallyReadInputAfterSourceFailure() throws IOException {
        PartiallyFailingReadableChannel source = new PartiallyFailingReadableChannel(new byte[]{1, 2});
        CopyDecoder decoder = new CopyDecoder();
        DecompressingReadableByteChannel channel = CodecChannelAdapters.newReadableByteChannel(
                source,
                ResourceOwnership.BORROWED,
                () -> decoder
        );

        IOException failure = assertThrows(IOException.class, () -> channel.decode(ByteBuffer.allocate(4)));
        assertEquals("source read failed", failure.getMessage());
        assertEquals(2, channel.sourceBytes());
        assertEquals(0, channel.inputBytes());
        assertArrayEquals(new byte[]{1, 2}, remainingBytes(channel.unconsumedInput()));

        ByteBuffer target = ByteBuffer.allocate(4);
        CodecResult resumed = channel.decode(target);
        assertEquals(CodecResult.Status.ACTIVE, resumed.status());
        CodecResult end = channel.decode(target);
        assertEquals(CodecResult.Status.END_OF_INPUT, end.status());
        target.flip();
        assertArrayEquals(new byte[]{1, 2}, remainingBytes(target));
        channel.close();
    }

    /// Verifies a decoder may consume a complete header without producing output before requesting more input.
    @Test
    void continuesAfterInputOnlyDecoderProgress() throws IOException {
        HeaderSkippingDecoder decoder = new HeaderSkippingDecoder();
        DecompressingReadableByteChannel channel = CodecChannelAdapters.newReadableByteChannel(
                new ChunkedReadableChannel(new byte[]{99, 7}, 1, 0, 0),
                ResourceOwnership.BORROWED,
                () -> decoder
        );
        ByteBuffer target = ByteBuffer.allocate(1);
        CodecResult result = channel.decode(target);
        assertEquals(CodecResult.Status.END_OF_INPUT, result.status());
        assertEquals(2, result.inputBytes());
        assertEquals(1, result.outputBytes());
        assertArrayEquals(new byte[]{7}, target.array());
        assertEquals(2, decoder.decodeCalls());
        channel.close();
    }

    /// Verifies lifecycle failures retain priority and owned endpoint closure can be retried independently.
    @Test
    void aggregatesLifecycleFailuresAndRetriesOwnedEndpointClosure() throws IOException {
        CloseOnlyFailingEncoder closeOnlyEncoder = new CloseOnlyFailingEncoder();
        CompressingWritableByteChannel closeOnlyChannel = CodecChannelAdapters.newWritableByteChannel(
                new CollectingWritableChannel(),
                ResourceOwnership.BORROWED,
                () -> closeOnlyEncoder
        );
        assertSame(
                closeOnlyEncoder.failure(),
                assertThrows(IllegalStateException.class, closeOnlyChannel::finish)
        );
        closeOnlyChannel.close();
        assertEquals(1, closeOnlyEncoder.closeCalls());

        FailingLifecycleEncoder encoder = new FailingLifecycleEncoder();
        CollectingWritableChannel target = new CollectingWritableChannel(8, 0, 1);
        CompressingWritableByteChannel encodingChannel = CodecChannelAdapters.newWritableByteChannel(
                target,
                ResourceOwnership.OWNED,
                () -> encoder
        );

        IOException encodingFailure = assertThrows(IOException.class, encodingChannel::finish);
        assertSame(encoder.finishFailure(), encodingFailure);
        assertEquals(2, encodingFailure.getSuppressed().length);
        assertSame(encoder.closeFailure(), encodingFailure.getSuppressed()[0]);
        assertEquals("writable close failed", encodingFailure.getSuppressed()[1].getMessage());
        assertEquals(1, encoder.finishCalls());
        assertEquals(1, encoder.closeCalls());
        assertEquals(1, target.closeCalls());

        encodingChannel.close();
        assertFalse(target.isOpen());
        assertEquals(2, target.closeCalls());
        assertEquals(1, encoder.finishCalls());
        assertEquals(1, encoder.closeCalls());

        FailingCloseDecoder decoder = new FailingCloseDecoder();
        ChunkedReadableChannel source = new ChunkedReadableChannel(new byte[0], 8, 0, 1);
        DecompressingReadableByteChannel decodingChannel = CodecChannelAdapters.newReadableByteChannel(
                source,
                ResourceOwnership.OWNED,
                () -> decoder
        );
        IllegalStateException decodingFailure = assertThrows(
                IllegalStateException.class,
                decodingChannel::close
        );
        assertSame(decoder.failure(), decodingFailure);
        assertEquals(1, decodingFailure.getSuppressed().length);
        assertEquals("readable close failed", decodingFailure.getSuppressed()[0].getMessage());
        assertEquals(1, decoder.closeCalls());
        assertEquals(1, source.closeCalls());

        decodingChannel.close();
        assertFalse(source.isOpen());
        assertEquals(2, source.closeCalls());
        assertEquals(1, decoder.closeCalls());
    }

    /// Creates a channel, performs one invalid write, and verifies the expected diagnostic.
    private static void assertEncodingFailure(CompressionEncoder encoder, String expectedMessage) throws IOException {
        CompressingWritableByteChannel channel = CodecChannelAdapters.newWritableByteChannel(
                new CollectingWritableChannel(),
                ResourceOwnership.BORROWED,
                () -> encoder
        );
        IOException failure = assertThrows(
                IOException.class,
                () -> channel.write(ByteBuffer.wrap(new byte[]{1}))
        );
        assertEquals(expectedMessage, failure.getMessage());
        channel.close();
    }

    /// Creates a channel, performs one invalid decode, and verifies the expected diagnostic.
    private static void assertDecodingFailure(CompressionDecoder decoder, String expectedMessage) throws IOException {
        DecompressingReadableByteChannel channel = CodecChannelAdapters.newReadableByteChannel(
                new ChunkedReadableChannel(new byte[]{1}),
                ResourceOwnership.BORROWED,
                () -> decoder
        );
        IOException failure = assertThrows(
                IOException.class,
                () -> channel.decode(ByteBuffer.allocate(1))
        );
        assertEquals(expectedMessage, failure.getMessage());
        channel.close();
    }

    /// Copies a buffer's remaining bytes without changing its state.
    private static byte[] remainingBytes(ByteBuffer buffer) {
        ByteBuffer copy = buffer.slice();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }

    /// Copies bytes between two buffers and returns the number transferred.
    private static int copyBytes(ByteBuffer source, ByteBuffer target) {
        int count = Math.min(source.remaining(), target.remaining());
        ByteBuffer chunk = source.slice();
        chunk.limit(count);
        target.put(chunk);
        source.position(source.position() + count);
        return count;
    }

    /// Implements a collecting writable channel with bounded and failure-injectable progress.
    @NotNullByDefault
    private static final class CollectingWritableChannel implements WritableByteChannel {
        /// Optional initial output failure.
        private @Nullable Throwable writeFailure;

        /// Explicit cleanup outcomes, including retained and alternating exception identities.
        private final ArrayDeque<Throwable> closeFailures = new ArrayDeque<>();

        /// Collected bytes.
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        /// Maximum bytes consumed by one write.
        private final int maximumWrite;

        /// Stable close failure, or `null` to create a distinct failure for each attempt.
        private final @Nullable IOException closeFailure;

        /// Number of initial writes that return zero.
        private int zeroWritesRemaining;

        /// Number of initial close attempts that fail.
        private int closeFailuresRemaining;

        /// Number of write calls.
        private int writeCalls;

        /// Number of close calls made while open.
        private int closeCalls;

        /// Whether the channel remains open.
        private boolean open = true;

        /// Creates an unbounded channel whose operations succeed.
        private CollectingWritableChannel() {
            this(Integer.MAX_VALUE, 0, 0, null);
        }

        /// Creates a channel with configurable progress and close failures.
        private CollectingWritableChannel(int maximumWrite, int zeroWrites, int closeFailures) {
            this(maximumWrite, zeroWrites, closeFailures, null);
        }

        /// Creates a channel with configurable progress and an optional stable close failure.
        ///
        /// @param maximumWrite the maximum bytes consumed by one write
        /// @param zeroWrites the number of initial writes that return zero
        /// @param closeFailures the number of initial close attempts that fail
        /// @param closeFailure the stable close failure, or `null` to create a distinct failure for each attempt
        private CollectingWritableChannel(
                int maximumWrite,
                int zeroWrites,
                int closeFailures,
                @Nullable IOException closeFailure
        ) {
            this.maximumWrite = maximumWrite;
            this.zeroWritesRemaining = zeroWrites;
            this.closeFailuresRemaining = closeFailures;
            this.closeFailure = closeFailure;
        }

        /// Consumes at most the configured number of bytes.
        @Override
        public int write(ByteBuffer source) throws IOException {
            Objects.requireNonNull(source, "source");
            if (!open) {
                throw new ClosedChannelException();
            }
            writeCalls++;
            if (writeFailure != null) ChunkedReadableChannel.throwFailure(writeFailure);
            if (source.hasRemaining() && zeroWritesRemaining > 0) {
                zeroWritesRemaining--;
                return 0;
            }
            int count = Math.min(source.remaining(), maximumWrite);
            byte[] bytes = new byte[count];
            source.get(bytes);
            output.writeBytes(bytes);
            return count;
        }

        /// Returns whether the channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Fails configured initial attempts and otherwise closes the channel.
        @Override
        public void close() throws IOException {
            if (!open) {
                return;
            }
            closeCalls++;
            if (!closeFailures.isEmpty()) ChunkedReadableChannel.throwFailure(closeFailures.removeFirst());
            if (closeFailuresRemaining > 0) {
                closeFailuresRemaining--;
                if (closeFailure != null) {
                    throw closeFailure;
                }
                throw new IOException("writable close failed");
            }
            open = false;
        }

        /// Returns a copy of all collected bytes.
        private byte[] bytes() {
            return output.toByteArray();
        }

        /// Returns the number of write calls.
        private int writeCalls() {
            return writeCalls;
        }

        /// Returns the number of close calls made while open.
        private int closeCalls() {
            return closeCalls;
        }
    }

    /// Implements a chunked readable channel with bounded and failure-injectable progress.
    @NotNullByDefault
    private static final class ChunkedReadableChannel implements ReadableByteChannel {
        /// Remaining source bytes.
        private final ByteBuffer content;

        /// Maximum bytes produced by one read.
        private final int maximumRead;

        /// Number of initial reads that return zero.
        private int zeroReadsRemaining;

        /// A transient failure consumed by the next read, or `null` when reads may proceed.
        private @Nullable Throwable readFailure;

        /// Explicit close failures consumed before count-based close failures.
        private final ArrayDeque<Throwable> closeFailures = new ArrayDeque<>();

        /// Source position at which one read fails, or `-1` when no position-triggered failure is pending.
        private int failurePosition = -1;

        /// Number of initial close attempts that fail.
        private int closeFailuresRemaining;

        /// Number of read calls.
        private int readCalls;

        /// Number of close calls made while open.
        private int closeCalls;

        /// Whether the channel remains open.
        private boolean open = true;

        /// Creates an unbounded source over copied bytes.
        private ChunkedReadableChannel(byte[] content) {
            this(content, Integer.MAX_VALUE, 0, 0);
        }

        /// Creates a source with configurable progress and close failures.
        private ChunkedReadableChannel(byte[] content, int maximumRead, int zeroReads, int closeFailures) {
            this.content = ByteBuffer.wrap(Objects.requireNonNull(content, "content").clone());
            this.maximumRead = maximumRead;
            this.zeroReadsRemaining = zeroReads;
            this.closeFailuresRemaining = closeFailures;
        }

        /// Produces at most the configured number of bytes.
        @Override
        public int read(ByteBuffer target) throws IOException {
            Objects.requireNonNull(target, "target");
            if (!open) {
                throw new ClosedChannelException();
            }
            readCalls++;
            if (content.position() == failurePosition) {
                failurePosition = -1;
                throw new IOException("Source read failed at a pending prefix");
            }
            if (readFailure != null) {
                Throwable failure = readFailure;
                readFailure = null;
                throwFailure(failure);
            }
            if (!target.hasRemaining()) {
                return 0;
            }
            if (zeroReadsRemaining > 0) {
                zeroReadsRemaining--;
                return 0;
            }
            if (!content.hasRemaining()) {
                return -1;
            }
            int count = Math.min(Math.min(content.remaining(), target.remaining()), maximumRead);
            ByteBuffer chunk = content.slice();
            chunk.limit(count);
            target.put(chunk);
            content.position(content.position() + count);
            return count;
        }

        /// Returns whether the channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Fails configured initial attempts and otherwise closes the channel.
        @Override
        public void close() throws IOException {
            if (!open) {
                return;
            }
            closeCalls++;
            if (!closeFailures.isEmpty()) {
                throwFailure(closeFailures.removeFirst());
            }
            if (closeFailuresRemaining > 0) {
                closeFailuresRemaining--;
                throw new IOException("readable close failed");
            }
            open = false;
        }

        /// Reports an injected checked or unchecked failure without changing its identity.
        private static void throwFailure(Throwable failure) throws IOException {
            if (failure instanceof IOException exception) throw exception;
            if (failure instanceof RuntimeException exception) throw exception;
            if (failure instanceof Error error) throw error;
            throw new AssertionError(failure);
        }

        /// Returns the number of read calls.
        private int readCalls() {
            return readCalls;
        }

        /// Returns the number of close calls made while open.
        private int closeCalls() {
            return closeCalls;
        }
    }

    /// Produces bytes and then fails its first read after partial progress.
    @NotNullByDefault
    private static final class PartiallyFailingReadableChannel implements ReadableByteChannel {
        /// Remaining source bytes.
        private final ByteBuffer content;

        /// Whether the partial failure remains pending.
        private boolean fail = true;

        /// Whether this channel remains open.
        private boolean open = true;

        /// Creates a source over copied bytes.
        private PartiallyFailingReadableChannel(byte[] content) {
            this.content = ByteBuffer.wrap(Objects.requireNonNull(content, "content").clone());
        }

        /// Produces all remaining bytes and fails once before reporting EOF.
        @Override
        public int read(ByteBuffer target) throws IOException {
            Objects.requireNonNull(target, "target");
            if (!open) {
                throw new ClosedChannelException();
            }
            if (fail) {
                fail = false;
                copyBytes(content, target);
                throw new IOException("source read failed");
            }
            return -1;
        }

        /// Returns whether this channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this channel.
        @Override
        public void close() {
            open = false;
        }
    }

    /// Implements an immediately progressing interruptible writable channel.
    @NotNullByDefault
    private static final class ImmediateInterruptibleWritableChannel
            extends AbstractInterruptibleChannel
            implements WritableByteChannel {
        /// Collected target bytes.
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        /// Consumes every remaining source byte while open.
        @Override
        public int write(ByteBuffer source) throws IOException {
            Objects.requireNonNull(source, "source");
            if (!isOpen()) {
                throw new ClosedChannelException();
            }
            byte[] bytes = new byte[source.remaining()];
            source.get(bytes);
            output.writeBytes(bytes);
            return bytes.length;
        }

        /// Releases no additional test resources.
        @Override
        protected void implCloseChannel() {
        }
    }

    /// Implements an immediately progressing interruptible readable channel.
    @NotNullByDefault
    private static final class ImmediateInterruptibleReadableChannel
            extends AbstractInterruptibleChannel
            implements ReadableByteChannel {
        /// Remaining copied source bytes.
        private final ByteBuffer content;

        /// Creates a source over copied bytes.
        private ImmediateInterruptibleReadableChannel(byte[] content) {
            this.content = ByteBuffer.wrap(Objects.requireNonNull(content, "content").clone());
        }

        /// Copies all available bytes that fit in the target.
        @Override
        public int read(ByteBuffer target) throws IOException {
            Objects.requireNonNull(target, "target");
            if (!isOpen()) {
                throw new ClosedChannelException();
            }
            if (!content.hasRemaining()) {
                return -1;
            }
            return copyBytes(content, target);
        }

        /// Releases no additional test resources.
        @Override
        protected void implCloseChannel() {
        }
    }

    /// Implements a copying encoder with one terminal trailer byte.
    @NotNullByDefault
    private static class CopyEncoder implements CompressionEncoder {
        /// Stable trailer emitted by terminal finalization.
        private static final byte TRAILER = 'T';

        /// Number of encode calls.
        private int encodeCalls;

        /// Number of terminal finish calls.
        private int finishCalls;

        /// Number of close calls.
        private int closeCalls;

        /// Whether the trailer was emitted.
        private boolean trailerWritten;

        /// Copies source bytes into the target.
        @Override
        public CodecOutcome encode(ByteBuffer source, ByteBuffer target) {
            encodeCalls++;
            copyBytes(source, target);
            return source.hasRemaining() ? CodecOutcome.NEEDS_OUTPUT : CodecOutcome.NEEDS_INPUT;
        }

        /// Emits one trailer byte and reports terminal completion.
        @Override
        public CodecOutcome finish(ByteBuffer target) {
            finishCalls++;
            if (!trailerWritten) {
                if (!target.hasRemaining()) {
                    return CodecOutcome.NEEDS_OUTPUT;
                }
                target.put(TRAILER);
                trailerWritten = true;
            }
            return CodecOutcome.FINISHED;
        }

        /// Restores initial test-engine state.
        @Override
        public void reset() {
            trailerWritten = false;
        }

        /// Records engine release.
        @Override
        public void close() {
            closeCalls++;
        }

        /// Returns the number of encode calls.
        private int encodeCalls() {
            return encodeCalls;
        }

        /// Returns the number of terminal finish calls.
        protected final int finishCalls() {
            return finishCalls;
        }

        /// Returns the number of close calls.
        protected final int closeCalls() {
            return closeCalls;
        }
    }

    /// Adds only flush capability to a copying encoder.
    @NotNullByDefault
    private static final class FlushOnlyEncoder extends CopyEncoder implements CompressionEncoder.Flushable {
        /// Completes a no-output flush.
        @Override
        public CodecOutcome flush(ByteBuffer target) {
            return CodecOutcome.FLUSHED;
        }
    }

    /// Adds only frame capability to a copying encoder.
    @NotNullByDefault
    private static final class FrameOnlyEncoder extends CopyEncoder implements CompressionEncoder.Framed {
        /// Starts a synthetic frame.
        @Override
        public void startFrame(EncodingOptions options) {
            Objects.requireNonNull(options, "options");
        }

        /// Completes a synthetic frame without output.
        @Override
        public CodecOutcome finishFrame(ByteBuffer target) {
            return CodecOutcome.BOUNDARY_REACHED;
        }
    }

    /// Adds observable flush and frame capabilities to a copying encoder.
    @NotNullByDefault
    private static class CapabilityEncoder extends CopyEncoder implements CompressionEncoder.FlushableFramed {
        /// Number of flush calls.
        private int flushCalls;

        /// Number of frame-finish calls.
        private int frameFinishCalls;

        /// Number of explicit frame starts.
        private int startCalls;

        /// Options supplied to the most recent explicit frame start.
        private @Nullable EncodingOptions lastOptions;

        /// Records an explicit frame start.
        @Override
        public void startFrame(EncodingOptions options) {
            startCalls++;
            lastOptions = Objects.requireNonNull(options, "options");
        }

        /// Emits one visible flush marker.
        @Override
        public CodecOutcome flush(ByteBuffer target) {
            flushCalls++;
            target.put((byte) 'F');
            return CodecOutcome.FLUSHED;
        }

        /// Emits one visible frame-boundary marker.
        @Override
        public CodecOutcome finishFrame(ByteBuffer target) {
            frameFinishCalls++;
            target.put((byte) 'B');
            return CodecOutcome.BOUNDARY_REACHED;
        }

        /// Returns the number of flush calls.
        private int flushCalls() {
            return flushCalls;
        }

        /// Returns the number of frame-finish calls.
        private int frameFinishCalls() {
            return frameFinishCalls;
        }

        /// Returns the number of explicit frame starts.
        private int startCalls() {
            return startCalls;
        }

        /// Returns the most recently supplied frame options.
        private EncodingOptions lastOptions() {
            return Objects.requireNonNull(lastOptions, "No frame options were recorded");
        }
    }

    /// Returns one configured outcome from every encode call.
    @NotNullByDefault
    private static final class OutcomeEncoder extends CopyEncoder {
        /// Outcome returned from encoding.
        private final CodecOutcome outcome;

        /// Whether one source byte is consumed.
        private final boolean consume;

        /// Whether one output byte is produced.
        private final boolean produce;

        /// Creates a configured outcome encoder.
        private OutcomeEncoder(CodecOutcome outcome, boolean consume, boolean produce) {
            this.outcome = outcome;
            this.consume = consume;
            this.produce = produce;
        }

        /// Applies configured buffer progress and returns the configured outcome.
        @Override
        public CodecOutcome encode(ByteBuffer source, ByteBuffer target) {
            if (consume) {
                source.get();
            }
            if (produce) {
                target.put((byte) 1);
            }
            return outcome;
        }
    }

    /// Emits one preamble before consuming its first source byte.
    @NotNullByDefault
    private static final class PreambleEncoder extends CopyEncoder {
        /// Whether the preamble remains pending.
        private boolean preamblePending = true;

        /// Emits the preamble once, then delegates ordinary copying.
        @Override
        public CodecOutcome encode(ByteBuffer source, ByteBuffer target) {
            if (preamblePending) {
                preamblePending = false;
                target.put((byte) 'P');
                return CodecOutcome.NEEDS_OUTPUT;
            }
            return super.encode(source, target);
        }
    }

    /// Completes flush, frame, and terminal operations over two output-producing calls each.
    @NotNullByDefault
    private static final class MultiStepCapabilityEncoder extends CopyEncoder
            implements CompressionEncoder.FlushableFramed {
        /// Number of flush calls.
        private int flushCalls;

        /// Number of frame-finish calls.
        private int frameCalls;

        /// Number of terminal finish calls.
        private int terminalCalls;

        /// Accepts an explicit frame start.
        @Override
        public void startFrame(EncodingOptions options) {
            Objects.requireNonNull(options, "options");
        }

        /// Emits two flush markers before reporting completion.
        @Override
        public CodecOutcome flush(ByteBuffer target) {
            flushCalls++;
            target.put((byte) (flushCalls == 1 ? 'F' : 'f'));
            return flushCalls == 1 ? CodecOutcome.NEEDS_OUTPUT : CodecOutcome.FLUSHED;
        }

        /// Emits two frame markers before reporting a boundary.
        @Override
        public CodecOutcome finishFrame(ByteBuffer target) {
            frameCalls++;
            target.put((byte) (frameCalls == 1 ? 'B' : 'b'));
            return frameCalls == 1 ? CodecOutcome.NEEDS_OUTPUT : CodecOutcome.BOUNDARY_REACHED;
        }

        /// Emits two terminal markers before reporting completion.
        @Override
        public CodecOutcome finish(ByteBuffer target) {
            terminalCalls++;
            target.put((byte) (terminalCalls == 1 ? 'T' : 't'));
            return terminalCalls == 1 ? CodecOutcome.NEEDS_OUTPUT : CodecOutcome.FINISHED;
        }

        /// Returns the number of flush calls.
        private int flushCalls() {
            return flushCalls;
        }

        /// Returns the number of frame-finish calls.
        private int frameCalls() {
            return frameCalls;
        }

        /// Returns the number of terminal finish calls.
        private int terminalFinishCalls() {
            return terminalCalls;
        }
    }

    /// Returns one configured terminal outcome without output.
    @NotNullByDefault
    private static final class TerminalOutcomeEncoder extends CopyEncoder {
        /// Terminal outcome to return.
        private final CodecOutcome outcome;

        /// Creates a terminal outcome encoder.
        private TerminalOutcomeEncoder(CodecOutcome outcome) {
            this.outcome = outcome;
        }

        /// Returns the configured terminal outcome without progress.
        @Override
        public CodecOutcome finish(ByteBuffer target) {
            return outcome;
        }
    }

    /// Returns independently configured invalid flush and frame outcomes.
    @NotNullByDefault
    private static final class BadCapabilityEncoder extends CopyEncoder
            implements CompressionEncoder.FlushableFramed {
        /// Flush outcome to return.
        private final CodecOutcome flushOutcome;

        /// Frame outcome to return.
        private final CodecOutcome frameOutcome;

        /// Creates an encoder with configured capability outcomes.
        private BadCapabilityEncoder(CodecOutcome flushOutcome, CodecOutcome frameOutcome) {
            this.flushOutcome = flushOutcome;
            this.frameOutcome = frameOutcome;
        }

        /// Accepts an explicit frame start.
        @Override
        public void startFrame(EncodingOptions options) {
            Objects.requireNonNull(options, "options");
        }

        /// Returns the configured flush outcome.
        @Override
        public CodecOutcome flush(ByteBuffer target) {
            return flushOutcome;
        }

        /// Returns the configured frame outcome.
        @Override
        public CodecOutcome finishFrame(ByteBuffer target) {
            return frameOutcome;
        }
    }

    /// Fails finalization and release with distinct stable failures.
    @NotNullByDefault
    private static final class FailingLifecycleEncoder implements CompressionEncoder {
        /// Stable finalization failure.
        private final IOException finishFailure = new IOException("encoder finish failed");

        /// Stable release failure.
        private final IllegalStateException closeFailure = new IllegalStateException("encoder close failed");

        /// Number of terminal finish calls.
        private int finishCalls;

        /// Number of close calls.
        private int closeCalls;

        /// Consumes all source bytes without output.
        @Override
        public CodecOutcome encode(ByteBuffer source, ByteBuffer target) {
            source.position(source.limit());
            return CodecOutcome.NEEDS_INPUT;
        }

        /// Throws the stable finalization failure.
        @Override
        public CodecOutcome finish(ByteBuffer target) throws IOException {
            finishCalls++;
            throw finishFailure;
        }

        /// Resets no test state.
        @Override
        public void reset() {
        }

        /// Throws the stable release failure.
        @Override
        public void close() {
            closeCalls++;
            throw closeFailure;
        }

        /// Returns the stable finalization failure.
        private IOException finishFailure() {
            return finishFailure;
        }

        /// Returns the stable release failure.
        private IllegalStateException closeFailure() {
            return closeFailure;
        }

        /// Returns the number of finish calls.
        private int finishCalls() {
            return finishCalls;
        }

        /// Returns the number of close calls.
        private int closeCalls() {
            return closeCalls;
        }
    }

    /// Completes finalization but throws a stable unchecked release failure.
    @NotNullByDefault
    private static final class CloseOnlyFailingEncoder implements CompressionEncoder {
        /// Stable release failure.
        private final IllegalStateException failure = new IllegalStateException("encoder close failed");

        /// Number of release attempts.
        private int closeCalls;

        /// Consumes all supplied source bytes.
        @Override
        public CodecOutcome encode(ByteBuffer source, ByteBuffer target) {
            source.position(source.limit());
            return CodecOutcome.NEEDS_INPUT;
        }

        /// Reports successful terminal finalization.
        @Override
        public CodecOutcome finish(ByteBuffer target) {
            return CodecOutcome.FINISHED;
        }

        /// Resets no test state.
        @Override
        public void reset() {
        }

        /// Throws the stable release failure.
        @Override
        public void close() {
            closeCalls++;
            throw failure;
        }

        /// Returns the stable release failure.
        private IllegalStateException failure() {
            return failure;
        }

        /// Returns the number of release attempts.
        private int closeCalls() {
            return closeCalls;
        }
    }

    /// Decodes a single four-byte field without consuming an incomplete prefix.
    @NotNullByDefault
    private static final class PrefixDecoder implements CompressionDecoder {
        /// Copies one complete field and leaves trailing input untouched.
        @Override
        public CodecOutcome decode(ByteBuffer source, ByteBuffer target) {
            if (source.remaining() < 4) {
                return CodecOutcome.NEEDS_INPUT;
            }
            target.putInt(source.getInt());
            return CodecOutcome.FINISHED;
        }

        /// Rejects a truncated field with its retained length.
        @Override
        public CodecOutcome finish(ByteBuffer source, ByteBuffer target) throws IOException {
            if (source.remaining() < 4) {
                throw new IOException("Truncated four-byte field: " + source.remaining());
            }
            return decode(source, target);
        }

        /// Holds no state to reset.
        @Override
        public void reset() {
        }

        /// Holds no resources to release.
        @Override
        public void close() {
        }
    }

    /// Copies bytes until physical EOF completes decoding.
    @NotNullByDefault
    private static class CopyDecoder implements CompressionDecoder {
        /// Number of decode calls.
        private int decodeCalls;

        /// Number of reset calls.
        private int resetCalls;

        /// Number of close calls.
        private int closeCalls;

        /// Copies available bytes and requests input or output.
        @Override
        public CodecOutcome decode(ByteBuffer source, ByteBuffer target) {
            decodeCalls++;
            copyBytes(source, target);
            return source.hasRemaining() ? CodecOutcome.NEEDS_OUTPUT : CodecOutcome.NEEDS_INPUT;
        }

        /// Copies final bytes and reports completion when exhausted.
        @Override
        public CodecOutcome finish(ByteBuffer source, ByteBuffer target) {
            copyBytes(source, target);
            return source.hasRemaining() ? CodecOutcome.NEEDS_OUTPUT : CodecOutcome.FINISHED;
        }

        /// Records engine reset.
        @Override
        public void reset() {
            resetCalls++;
        }

        /// Records engine release.
        @Override
        public void close() {
            closeCalls++;
        }

        /// Returns the number of decode calls.
        protected final int decodeCalls() {
            return decodeCalls;
        }

        /// Records one decode call made by a specialized test decoder.
        protected final void recordDecodeCall() {
            decodeCalls++;
        }

        /// Returns the number of reset calls.
        protected final int resetCalls() {
            return resetCalls;
        }

        /// Returns the number of close calls.
        protected int closeCalls() {
            return closeCalls;
        }
    }

    /// Completes one non-framed stream after copying one byte.
    @NotNullByDefault
    private static final class SingleByteDecoder extends CopyDecoder {
        /// Copies one byte and reports completion.
        @Override
        public CodecOutcome decode(ByteBuffer source, ByteBuffer target) {
            target.put(source.get());
            return CodecOutcome.FINISHED;
        }
    }

    /// Treats every source byte as one independently terminated frame.
    @NotNullByDefault
    private static final class FrameByteDecoder extends CopyDecoder implements CompressionDecoder.Framed {
        /// Copies one byte and reports its frame boundary.
        @Override
        public CodecOutcome decode(ByteBuffer source, ByteBuffer target) {
            recordDecodeCall();
            target.put(source.get());
            return CodecOutcome.FINISHED;
        }

        /// Copies one final byte when present and reports its frame boundary.
        @Override
        public CodecOutcome finish(ByteBuffer source, ByteBuffer target) {
            if (source.hasRemaining()) {
                target.put(source.get());
            }
            return CodecOutcome.FINISHED;
        }
    }

    /// Consumes one header byte without output before decoding one payload byte.
    @NotNullByDefault
    private static final class HeaderSkippingDecoder extends CopyDecoder {
        /// Whether the header still needs to be consumed.
        private boolean headerPending = true;

        /// Consumes the header or emits the following payload byte.
        @Override
        public CodecOutcome decode(ByteBuffer source, ByteBuffer target) {
            recordDecodeCall();
            if (headerPending) {
                headerPending = false;
                source.get();
                return CodecOutcome.NEEDS_INPUT;
            }
            target.put(source.get());
            return CodecOutcome.FINISHED;
        }
    }

    /// Returns one configured outcome from every decode call.
    @NotNullByDefault
    private static class OutcomeDecoder implements CompressionDecoder {
        /// Outcome returned from decoding.
        private final CodecOutcome outcome;

        /// Whether one source byte is consumed.
        private final boolean consume;

        /// Whether one output byte is produced.
        private final boolean produce;

        /// Number of decode calls.
        private int decodeCalls;

        /// Creates a configured outcome decoder.
        private OutcomeDecoder(CodecOutcome outcome, boolean consume, boolean produce) {
            this.outcome = outcome;
            this.consume = consume;
            this.produce = produce;
        }

        /// Applies configured buffer progress and returns the configured outcome.
        @Override
        public CodecOutcome decode(ByteBuffer source, ByteBuffer target) {
            decodeCalls++;
            if (consume) {
                source.get();
            }
            if (produce) {
                target.put((byte) 1);
            }
            return outcome;
        }

        /// Applies the same configured behavior at physical EOF.
        @Override
        public CodecOutcome finish(ByteBuffer source, ByteBuffer target) {
            return decode(source, target);
        }

        /// Resets no test state.
        @Override
        public void reset() {
        }

        /// Releases no test resources.
        @Override
        public void close() {
        }

        /// Returns the number of decode calls.
        private int decodeCalls() {
            return decodeCalls;
        }
    }

    /// Exposes a stable dictionary request after reporting a dictionary outcome.
    @NotNullByDefault
    private static final class DictionaryOutcomeDecoder extends OutcomeDecoder
            implements CompressionDecoder.DictionaryAware<
            RawCompressionDictionary,
            DictionaryRequest<RawCompressionDictionary>
            > {
        /// Stable request exposed by this decoder.
        private final DictionaryRequest<RawCompressionDictionary> request = dictionary -> true;

        /// Creates a decoder that immediately requests a dictionary.
        private DictionaryOutcomeDecoder() {
            super(CodecOutcome.NEEDS_DICTIONARY, false, false);
        }

        /// Returns the stable dictionary request.
        @Override
        public DictionaryRequest<RawCompressionDictionary> dictionaryRequest() {
            return request;
        }

        /// Accepts any test dictionary.
        @Override
        public void provideDictionary(RawCompressionDictionary dictionary) {
            Objects.requireNonNull(dictionary, "dictionary");
        }

        /// Returns the stable dictionary request for assertions.
        private DictionaryRequest<RawCompressionDictionary> request() {
            return request;
        }
    }

    /// Throws a stable unchecked failure when released.
    @NotNullByDefault
    private static final class FailingCloseDecoder extends CopyDecoder {
        /// Stable release failure.
        private final IllegalStateException failure = new IllegalStateException("decoder close failed");

        /// Number of release attempts.
        private int failingCloseCalls;

        /// Throws the stable release failure.
        @Override
        public void close() {
            failingCloseCalls++;
            throw failure;
        }

        /// Returns the stable release failure.
        private IllegalStateException failure() {
            return failure;
        }

        /// Returns the number of release attempts.
        @Override
        protected int closeCalls() {
            return failingCloseCalls;
        }
    }
}
