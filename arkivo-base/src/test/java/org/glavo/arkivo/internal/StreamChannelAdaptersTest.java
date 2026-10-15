// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies progress-safe and close-retryable stream/channel adaptation.
@NotNullByDefault
final class StreamChannelAdaptersTest {
    /// Keeps a previously delivered endpoint failure primary when automatic closure reports the same object.
    @ParameterizedTest
    @ValueSource(strings = {"readable-channel", "writable-channel", "input-stream", "output-stream", "seekable-skip"})
    void preservesRepeatedFailureIdentity(String kind) throws IOException {
        for (boolean failOperation : new boolean[]{false, true}) {
            for (Throwable failure : List.of(new IOException("endpoint failed"),
                    new IllegalStateException("endpoint failed"), new AssertionError("endpoint failed"))) {
                var endpoint = new RepeatedFailureChannel(failure, failOperation ? 1 : 2);
                AdaptedOperation operation = adaptedOperation(kind, endpoint);
                try {
                    Throwable actual = assertThrows(failure.getClass(), () -> {
                        try (var resource = operation.resource()) {
                            if (failOperation) operation.action().run();
                            else resource.close();
                        }
                    });
                    assertSame(failure, actual);
                    assertEquals(1, actual.getSuppressed().length);
                    assertEquals(IOException.class, actual.getSuppressed()[0].getClass());
                    assertSame(failure, actual.getSuppressed()[0].getCause());
                    assertTrue(endpoint.isOpen());
                    operation.resource().close();
                    operation.resource().close();
                    assertFalse(endpoint.isOpen());
                    assertEquals(failOperation ? 2 : 3, endpoint.closeCalls);
                } finally {
                    operation.resource().close();
                }
            }
        }
    }

    /// Preserves an older exception even when another endpoint failure was reported before cleanup reuses it.
    @ParameterizedTest
    @ValueSource(strings = {"readable-channel", "writable-channel", "input-stream", "output-stream", "seekable-skip"})
    void preservesEarlierFailureAcrossInterveningFailures(String kind) throws IOException {
        for (Throwable first : List.of(new IOException("first failure"),
                new IllegalStateException("first failure"), new AssertionError("first failure"))) {
            var endpoint = new RepeatedFailureChannel(first, 1);
            AdaptedOperation operation = adaptedOperation(kind, endpoint);
            try {
                Throwable actual = assertThrows(first.getClass(), () -> {
                    try (var resource = operation.resource()) {
                        assertSame(first, assertThrows(first.getClass(), operation.action()::run));
                        IOException intervening = new IOException("intervening failure");
                        endpoint.failure = intervening;
                        assertSame(intervening, assertThrows(IOException.class, operation.action()::run));
                        endpoint.failure = first;
                        throw first;
                    }
                });
                assertSame(first, actual);
                assertEquals(1, actual.getSuppressed().length);
                assertEquals(IOException.class, actual.getSuppressed()[0].getClass());
                assertSame(first, actual.getSuppressed()[0].getCause());
                operation.resource().close();
                operation.resource().close();
                assertEquals(2, endpoint.closeCalls);
                assertFalse(endpoint.isOpen());
            } finally {
                operation.resource().close();
            }
        }
    }

    /// Invalid arguments and empty transfers do not change the identity of the first endpoint cleanup failure.
    @ParameterizedTest
    @ValueSource(strings = {"readable-channel", "writable-channel", "input-stream", "output-stream"})
    void preservesFirstCloseFailureAfterRejectedArguments(String kind) throws IOException {
        for (Throwable failure : List.of(new IOException("first close failure"),
                new IllegalStateException("first close failure"), new AssertionError("first close failure"))) {
            var endpoint = new RepeatedFailureChannel(failure, 1);
            try (var resource = adaptedOperation(kind, endpoint).resource()) {
                if (resource instanceof ReadableByteChannel channel) {
                    assertThrows(NullPointerException.class, () -> channel.read(null));
                    assertThrows(IllegalArgumentException.class, () -> channel.read(ByteBuffer.allocate(1).asReadOnlyBuffer()));
                    assertEquals(0, channel.read(ByteBuffer.allocate(0)));
                } else if (resource instanceof WritableByteChannel channel) {
                    assertThrows(NullPointerException.class, () -> channel.write(null));
                    assertEquals(0, channel.write(ByteBuffer.allocate(0)));
                } else if (resource instanceof InputStream stream) {
                    assertThrows(IndexOutOfBoundsException.class, () -> stream.read(new byte[1], -1, 1));
                    assertEquals(0, stream.read(new byte[0]));
                    assertEquals(0L, stream.skip(-1));
                } else if (resource instanceof OutputStream stream) {
                    assertThrows(IndexOutOfBoundsException.class, () -> stream.write(new byte[1], -1, 1));
                    stream.write(new byte[0]);
                } else {
                    throw new AssertionError(kind);
                }
                assertSame(failure, assertThrows(failure.getClass(), resource::close));
                assertEquals(0, failure.getSuppressed().length);
                assertTrue(endpoint.isOpen());
            }
            assertEquals(2, endpoint.closeCalls);
            assertFalse(endpoint.isOpen());
        }
    }

    /// A new cleanup failure retains its subtype and identity after an unrelated data failure.
    @ParameterizedTest
    @ValueSource(strings = {"readable-channel", "writable-channel", "input-stream", "output-stream", "seekable-skip"})
    void preservesDistinctCleanupFailure(String kind) throws IOException {
        for (Throwable cleanup : List.of(new EOFException("cleanup failure"), new ClosedChannelException(),
                new IllegalStateException("cleanup failure"), new AssertionError("cleanup failure"),
                new EqualIOException())) {
            IOException first = new EqualIOException();
            var endpoint = new RepeatedFailureChannel(first, 1);
            AdaptedOperation operation = adaptedOperation(kind, endpoint);
            try (var resource = operation.resource()) {
                assertSame(first, assertThrows(IOException.class, operation.action()::run));
                endpoint.failure = cleanup;
                assertSame(cleanup, assertThrows(cleanup.getClass(), resource::close));
                assertEquals(0, cleanup.getSuppressed().length);
            }
            assertEquals(2, endpoint.closeCalls);
            assertFalse(endpoint.isOpen());
        }
    }

    /// Cleanup also protects a previously delivered wrapper if the endpoint later reuses that wrapper itself.
    @ParameterizedTest
    @ValueSource(strings = {"readable-channel", "writable-channel", "input-stream", "output-stream", "seekable-skip"})
    void preservesReusedCleanupWrapper(String kind) throws IOException {
        IOException first = new IOException("endpoint failure");
        var endpoint = new RepeatedFailureChannel(first, 2);
        AdaptedOperation operation = adaptedOperation(kind, endpoint);
        try {
            IOException delivered = assertThrows(IOException.class, () -> {
                try (var resource = operation.resource()) {
                    assertSame(first, assertThrows(IOException.class, operation.action()::run));
                    IOException wrapper = assertThrows(IOException.class, resource::close);
                    assertSame(first, wrapper.getCause());
                    endpoint.failure = wrapper;
                    throw wrapper;
                }
            });
            assertSame(first, delivered.getCause());
            assertEquals(1, delivered.getSuppressed().length);
            assertSame(delivered, delivered.getSuppressed()[0].getCause());
            operation.resource().close();
            operation.resource().close();
            assertEquals(3, endpoint.closeCalls);
            assertFalse(endpoint.isOpen());
        } finally {
            operation.resource().close();
        }
    }

    /// Distinct instances compare equal, so exception tracking must use object identity.
    @NotNullByDefault
    private static final class EqualIOException extends IOException {
        /// Serialization identifier for this test exception.
        private static final long serialVersionUID = 1L;

        /// Creates an exception whose equality does not distinguish instances.
        private EqualIOException() {
            super("equal endpoint failure");
        }

        /// Treats every instance of this test exception as equal.
        @Override
        public boolean equals(@Nullable Object other) {
            return other instanceof EqualIOException;
        }

        /// Returns the shared hash code for equal test exceptions.
        @Override
        public int hashCode() {
            return 1;
        }
    }

    /// Retains both failures when cancelling an in-flight operation, without holding the history lock during I/O.
    @ParameterizedTest
    @ValueSource(strings = {"readable-channel", "writable-channel", "input-stream", "output-stream", "seekable-skip"})
    void preservesConcurrentOperationAndCloseFailures(String kind) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            for (int iteration = 0; iteration < 1024; iteration++) {
                var endpoint = new ConcurrentFailureChannel(true);
                AdaptedOperation operation = adaptedOperation(kind, endpoint);
                var pending = executor.submit(() -> assertThrows(IOException.class, operation.action()::run));
                assertSame(endpoint.closeFailure, assertThrows(IOException.class, operation.resource()::close));
                assertSame(endpoint.operationFailure, pending.get(5, TimeUnit.SECONDS));
                IOException retry = assertThrows(IOException.class, operation.resource()::close);
                assertSame(endpoint.operationFailure, retry.getCause());
                operation.resource().close();
                operation.resource().close();
                assertFalse(endpoint.isOpen());
                assertEquals(3, endpoint.closeCalls);
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// A read or write released by successful closure does not recreate the closed adapter's failure history.
    @ParameterizedTest
    @ValueSource(strings = {"readable-channel", "writable-channel", "input-stream", "output-stream", "seekable-skip"})
    void releasesHistoryWhenConcurrentOperationFailsAfterClose(String kind) throws Exception {
        var endpoint = new ConcurrentFailureChannel(false);
        AdaptedOperation operation = adaptedOperation(kind, endpoint);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var pending = executor.submit(() -> assertThrows(IOException.class, operation.action()::run));
            operation.resource().close();
            endpoint.releaseOperation.countDown();
            assertSame(endpoint.operationFailure, pending.get(5, TimeUnit.SECONDS));
            operation.resource().close();
            assertEquals(1, endpoint.closeCalls);
            assertFalse(endpoint.isOpen());
            var field = operation.resource().getClass().getDeclaredField("reportedFailures");
            field.setAccessible(true);
            assertNull(field.get(operation.resource()));
        } finally {
            endpoint.releaseOperation.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Coordinates one data operation with its first close, then permits bounded cleanup retries.
    @NotNullByDefault
    private static final class ConcurrentFailureChannel implements SeekableByteChannel {
        /// Rendezvous for the operation and the first close attempt.
        private final CyclicBarrier barrier = new CyclicBarrier(2);
        /// Releases a pending operation after the adapter has completed successful closure.
        private final CountDownLatch releaseOperation = new CountDownLatch(1);
        /// Stable failure delivered by the data operation and a failed-close retry.
        private final IOException operationFailure = new IOException("operation failed");
        /// Distinct failure from the first close attempt.
        private final IOException closeFailure = new IOException("close failed");
        /// Whether the first close fails instead of completing cancellation successfully.
        private final boolean firstCloseFails;
        /// Number of physical close attempts.
        private int closeCalls;
        /// Whether the endpoint remains open.
        private boolean open = true;

        /// Selects whether cancellation itself fails.
        private ConcurrentFailureChannel(boolean firstCloseFails) {
            this.firstCloseFails = firstCloseFails;
        }

        /// Waits for the other side of cancellation without relying on timing delays.
        private void rendezvous() throws IOException {
            try {
                barrier.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException(failure);
            } catch (BrokenBarrierException | TimeoutException failure) {
                throw new IOException(failure);
            }
        }

        /// Reports the operation failure concurrently with, or after, the first close.
        private void failOperation() throws IOException {
            rendezvous();
            if (!firstCloseFails) {
                try {
                    assertTrue(releaseOperation.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IOException(failure);
                }
            }
            throw operationFailure;
        }

        /// Fails without changing the target.
        @Override
        public int read(ByteBuffer target) throws IOException { failOperation(); return -1; }

        /// Fails without consuming input.
        @Override
        public int write(ByteBuffer source) throws IOException { failOperation(); return 0; }

        /// Fails the positioning lookup used by skips.
        @Override
        public long position() throws IOException { failOperation(); return 0; }

        /// Rejects unused positioning operations.
        @Override
        public SeekableByteChannel position(long position) { throw new AssertionError("Unexpected positioning"); }

        /// Rejects unused size lookups.
        @Override
        public long size() { throw new AssertionError("Unexpected size lookup"); }

        /// Rejects unused truncations.
        @Override
        public SeekableByteChannel truncate(long size) { throw new AssertionError("Unexpected truncation"); }

        /// Returns whether cancellation has completed successfully.
        @Override
        public boolean isOpen() { return open; }

        /// Performs cancellation outside the adapter's identity-history critical section.
        @Override
        public void close() throws IOException {
            if (++closeCalls == 1) {
                rendezvous();
                if (firstCloseFails) throw closeFailure;
            } else if (closeCalls == 2 && firstCloseFails) {
                throw operationFailure;
            }
            open = false;
        }
    }

    /// Creates an adapter and a data operation that reaches the supplied endpoint.
    private static AdaptedOperation adaptedOperation(String kind, SeekableByteChannel endpoint) {
        return switch (kind) {
            case "readable-channel" -> {
                var channel = StreamChannelAdapters.readableChannel(Channels.newInputStream(endpoint));
                yield new AdaptedOperation(channel, () -> channel.read(ByteBuffer.allocateDirect(3)));
            }
            case "writable-channel" -> {
                var channel = StreamChannelAdapters.writableChannel(Channels.newOutputStream(endpoint));
                yield new AdaptedOperation(channel, () -> channel.write(ByteBuffer.allocateDirect(3)));
            }
            case "input-stream" -> {
                var stream = StreamChannelAdapters.inputStream(endpoint);
                yield new AdaptedOperation(stream, stream::read);
            }
            case "seekable-skip" -> {
                var stream = StreamChannelAdapters.inputStream(endpoint);
                yield new AdaptedOperation(stream, () -> stream.skip(1));
            }
            case "output-stream" -> {
                var stream = StreamChannelAdapters.outputStream(endpoint);
                yield new AdaptedOperation(stream, () -> stream.write(1));
            }
            default -> throw new AssertionError(kind);
        };
    }

    /// Associates one adapter with an operation that accesses its endpoint.
    /// @param resource the adapter whose closure is tested
    /// @param action the read, write or skip operation
    @NotNullByDefault
    private record AdaptedOperation(Closeable resource, IOAction action) {}

    /// Performs one checked I/O operation.
    @NotNullByDefault
    @FunctionalInterface
    private interface IOAction {
        /// Performs the operation.
        void run() throws IOException;
    }

    /// Reuses one failure for data operations and a finite number of close attempts.
    @NotNullByDefault
    private static final class RepeatedFailureChannel implements SeekableByteChannel {
        /// The failure instance currently reported by endpoint operations.
        private Throwable failure;
        /// Remaining failed close attempts.
        private int closeFailures;
        /// Number of physical close attempts.
        private int closeCalls;
        /// Whether the channel is open.
        private boolean open = true;

        /// Configures the shared failure and number of close failures.
        private RepeatedFailureChannel(Throwable failure, int closeFailures) {
            this.failure = failure;
            this.closeFailures = closeFailures;
        }

        /// Throws the original checked or unchecked failure.
        private void fail() throws IOException {
            if (failure instanceof IOException exception) throw exception;
            if (failure instanceof RuntimeException exception) throw exception;
            throw (Error) failure;
        }

        /// Fails without consuming input.
        @Override
        public int read(ByteBuffer target) throws IOException { fail(); return -1; }

        /// Fails without consuming output.
        @Override
        public int write(ByteBuffer source) throws IOException { fail(); return 0; }

        /// Fails position lookup used by seekable skips.
        @Override
        public long position() throws IOException { fail(); return 0; }

        /// Fails position updates.
        @Override
        public SeekableByteChannel position(long position) throws IOException { fail(); return this; }

        /// Fails size lookup.
        @Override
        public long size() throws IOException { fail(); return 0; }

        /// Fails truncation.
        @Override
        public SeekableByteChannel truncate(long size) throws IOException { fail(); return this; }

        /// Returns whether a close has completed successfully.
        @Override
        public boolean isOpen() { return open; }

        /// Fails the configured number of attempts, then closes successfully.
        @Override
        public void close() throws IOException {
            closeCalls++;
            if (closeFailures-- > 0) fail();
            open = false;
        }
    }

    /// Heap and empty reads need no transfer workspace; nonempty direct reads allocate it only once.
    @Test
    void allocatesTransferStorageOnlyForDirectReads() throws Exception {
        try (var channel = StreamChannelAdapters.readableChannel(new ByteArrayInputStream(new byte[]{1, 2, 3}))) {
            var field = channel.getClass().getDeclaredField("transferBuffer");
            field.setAccessible(true);
            assertNull(field.get(channel));
            assertEquals(1, channel.read(ByteBuffer.allocate(1)));
            assertNull(field.get(channel));
            assertEquals(0, channel.read(ByteBuffer.allocateDirect(0)));
            assertNull(field.get(channel));
            assertEquals(1, channel.read(ByteBuffer.allocateDirect(1)));
            @Nullable Object storage = field.get(channel);
            assertNotNull(storage);
            assertEquals(1, channel.read(ByteBuffer.allocateDirect(1)));
            assertSame(storage, field.get(channel));
        }
    }

    /// Directory for the seekable-channel fixture.
    @TempDir
    Path temporaryDirectory;

    /// Verifies heap and direct targets receive input bytes with exact position advancement.
    @Test
    void adaptsInputStreamsToChannels() throws IOException {
        try (ReadableByteChannel heapChannel = StreamChannelAdapters.readableChannel(
                new ByteArrayInputStream(new byte[]{1, 2})
        )) {
            ByteBuffer target = ByteBuffer.allocate(4);
            assertEquals(2, heapChannel.read(target));
            assertEquals(2, target.position());
            assertEquals(-1, heapChannel.read(target));
            assertEquals(2, target.position());
        }

        try (ReadableByteChannel directChannel = StreamChannelAdapters.readableChannel(
                new ByteArrayInputStream(new byte[]{3, 4})
        )) {
            ByteBuffer target = ByteBuffer.allocateDirect(4);
            assertEquals(2, directChannel.read(target));
            assertEquals(2, target.position());
            assertEquals(-1, directChannel.read(target));
            assertEquals(2, target.position());
        }
    }

    /// Verifies input-stream zero progress is returned without internal spinning.
    @Test
    void preservesZeroProgressFromInputStreams() throws IOException {
        try (ReadableByteChannel channel =
                     StreamChannelAdapters.readableChannel(new ZeroProgressInputStream())) {
            assertEquals(0, channel.read(ByteBuffer.allocate(1)));
            assertEquals(0, channel.read(ByteBuffer.allocateDirect(1)));
        }
    }

    /// Verifies channel adapters validate nulls, accept empty buffers, and reject access after closure.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void validatesChannelArgumentsAndClosedState() throws IOException {
        ReadableByteChannel input = StreamChannelAdapters.readableChannel(
                new ByteArrayInputStream(new byte[]{1})
        );
        assertThrows(NullPointerException.class, () -> input.read(null));
        assertEquals(0, input.read(ByteBuffer.allocate(0)));
        input.close();
        assertThrows(ClosedChannelException.class, () -> input.read(ByteBuffer.allocate(1)));

        WritableByteChannel output = StreamChannelAdapters.writableChannel(new ByteArrayOutputStream());
        assertThrows(NullPointerException.class, () -> output.write(null));
        output.close();
        assertThrows(ClosedChannelException.class, () -> output.write(ByteBuffer.allocate(1)));
    }

    /// Verifies a read-only target is rejected before the input stream is consumed.
    @Test
    void rejectsReadOnlyChannelTargetBeforeReading() throws IOException {
        ByteArrayInputStream source = new ByteArrayInputStream(new byte[]{1});
        try (ReadableByteChannel channel = StreamChannelAdapters.readableChannel(source)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> channel.read(ByteBuffer.allocate(1).asReadOnlyBuffer())
            );
            assertEquals(1, source.available());
        }
    }

    /// Verifies output streams receive heap and direct buffers with exact source advancement.
    @Test
    void adaptsOutputStreamsToChannels() throws IOException {
        CountingOutputStream output = new CountingOutputStream();
        try (WritableByteChannel channel = StreamChannelAdapters.writableChannel(output)) {
            assertEquals(0, channel.write(ByteBuffer.allocate(0)));
            assertEquals(0, output.writeCount());

            ByteBuffer heap = ByteBuffer.wrap(new byte[]{1, 2});
            ByteBuffer direct = ByteBuffer.allocateDirect(2).put(new byte[]{3, 4}).flip();
            assertEquals(2, channel.write(heap));
            assertEquals(2, channel.write(direct));
            assertEquals(heap.limit(), heap.position());
            assertEquals(direct.limit(), direct.position());
        }
        assertArrayEquals(new byte[]{1, 2, 3, 4}, output.toByteArray());
    }

    /// Verifies channel-backed streams reject zero progress instead of spinning.
    @Test
    void rejectsZeroProgressChannels() throws IOException {
        try (InputStream input = StreamChannelAdapters.inputStream(new ZeroProgressReadableChannel())) {
            IOException failure = assertThrows(IOException.class, () -> input.read(new byte[1]));
            assertEquals("Readable channel made no progress", failure.getMessage());
        }

        try (OutputStream output = StreamChannelAdapters.outputStream(new ZeroProgressWritableChannel())) {
            IOException failure = assertThrows(IOException.class, () -> output.write(new byte[]{1}));
            assertEquals("Writable channel made no progress", failure.getMessage());
            assertEquals("Writable channel made no progress",
                    assertThrows(IOException.class, () -> output.write(1)).getMessage());
        }
    }

    /// Verifies channel-backed streams implement single-byte, empty-request, and closed-state behavior.
    @Test
    void adaptsSingleByteStreamOperations() throws Exception {
        ReadableByteChannel source = StreamChannelAdapters.readableChannel(
                new ByteArrayInputStream(new byte[]{(byte) 0xfe})
        );
        InputStream input = StreamChannelAdapters.inputStream(source);
        assertEquals(0, input.read(new byte[0], 0, 0));
        assertEquals(0xfe, input.read());
        assertEquals(-1, input.read());
        input.close();
        assertFalse(source.isOpen());
        assertEquals("Stream closed", assertThrows(IOException.class, input::read).getMessage());
        assertEquals(
                "Stream closed",
                assertThrows(IOException.class, () -> input.read(new byte[1])).getMessage()
        );
        assertEquals("Stream closed", assertThrows(IOException.class, () -> input.skip(1L)).getMessage());

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        WritableByteChannel target = StreamChannelAdapters.writableChannel(bytes);
        OutputStream output = StreamChannelAdapters.outputStream(target);
        var field = output.getClass().getDeclaredField("singleByte");
        field.setAccessible(true);
        assertNull(field.get(output));
        output.write(new byte[0]);
        assertNull(field.get(output));
        output.write(0x101);
        @Nullable Object storage = field.get(output);
        assertNotNull(storage);
        output.write(new byte[]{2});
        output.write(0x103);
        assertSame(storage, field.get(output));
        output.close();
        assertFalse(target.isOpen());
        assertArrayEquals(new byte[]{1, 2, 3}, bytes.toByteArray());
        assertEquals("Stream closed", assertThrows(IOException.class, () -> output.write(2)).getMessage());
        assertEquals(
                "Stream closed",
                assertThrows(IOException.class, () -> output.write(new byte[]{2})).getMessage()
        );
    }

    /// Verifies skip uses channel positioning when available and bounded reads otherwise.
    @Test
    void skipsSeekableAndStreamingSources() throws IOException {
        Path path = Files.write(temporaryDirectory.resolve("source.bin"), new byte[]{1, 2, 3, 4});
        FileChannel seekableSource = FileChannel.open(path, StandardOpenOption.READ);
        try (InputStream input = StreamChannelAdapters.inputStream(seekableSource)) {
            assertEquals(0L, input.skip(0L));
            assertEquals(0L, input.skip(-1L));
            assertEquals(1, input.read());
            assertEquals(2L, input.skip(2L));
            assertEquals(4, input.read());
            assertEquals(0L, input.skip(Long.MAX_VALUE));

            seekableSource.position(10L);
            assertEquals(0L, input.skip(3L));
            assertEquals(10L, seekableSource.position());
        }

        ReadableByteChannel streamingSource = StreamChannelAdapters.readableChannel(
                new ByteArrayInputStream(new byte[]{5, 6, 7, 8})
        );
        try (InputStream input = StreamChannelAdapters.inputStream(streamingSource)) {
            assertEquals(2L, input.skip(2L));
            assertEquals(7, input.read());
            assertEquals(1L, input.skip(Long.MAX_VALUE));
            assertEquals(-1, input.read());
        }
    }

    /// Verifies streaming skips return each successful fragment before a later physical read can fail.
    @Test
    void streamingSkipPreservesProgressBeforeFailure() throws IOException {
        for (Throwable failure : List.of(new IOException("read failed"),
                new IllegalStateException("read failed"), new AssertionError("read failed"))) {
            FragmentedFailingReadableChannel source = new FragmentedFailingReadableChannel(failure);
            try (InputStream input = StreamChannelAdapters.inputStream(source)) {
                assertEquals(0, input.skip(0));
                assertEquals(0, input.skip(-1));
                assertEquals(0, source.readCalls);
                assertEquals(1, input.skip(Long.MAX_VALUE));
                assertEquals(1, source.readCalls);
                assertSame(failure, assertThrows(failure.getClass(), () -> input.skip(Long.MAX_VALUE)));
                assertEquals(2, source.readCalls);
                assertEquals(1, input.skip(Long.MAX_VALUE));
                assertEquals(3, input.read());
                assertEquals(0, input.skip(Long.MAX_VALUE));
                assertEquals(-1, input.read());
            }
            assertFalse(source.isOpen());
        }
    }

    /// Verifies every adapter retries a failed endpoint close and then becomes idempotent.
    @Test
    void retriesFailedEndpointClosure() throws IOException {
        FailingCloseInputStream inputStream = new FailingCloseInputStream();
        ReadableByteChannel inputChannel = StreamChannelAdapters.readableChannel(inputStream);
        assertThrows(IOException.class, inputChannel::close);
        assertTrue(inputChannel.isOpen());
        inputChannel.close();
        inputChannel.close();
        assertFalse(inputChannel.isOpen());
        assertEquals(2, inputStream.closeCount());

        FailingCloseOutputStream outputStream = new FailingCloseOutputStream();
        WritableByteChannel outputChannel = StreamChannelAdapters.writableChannel(outputStream);
        assertThrows(IOException.class, outputChannel::close);
        assertTrue(outputChannel.isOpen());
        outputChannel.close();
        outputChannel.close();
        assertFalse(outputChannel.isOpen());
        assertEquals(2, outputStream.closeCount());

        FailingCloseReadableChannel readableChannel = new FailingCloseReadableChannel();
        InputStream input = StreamChannelAdapters.inputStream(readableChannel);
        assertThrows(IOException.class, input::close);
        input.close();
        input.close();
        assertEquals(2, readableChannel.closeCount());

        FailingCloseWritableChannel writableChannel = new FailingCloseWritableChannel();
        OutputStream output = StreamChannelAdapters.outputStream(writableChannel);
        assertThrows(IOException.class, output::close);
        output.close();
        output.close();
        assertEquals(2, writableChannel.closeCount());
    }

    /// Supplies three bytes one at a time and fails its second physical read.
    @NotNullByDefault
    private static final class FragmentedFailingReadableChannel implements ReadableByteChannel {
        /// Failure emitted by the second read attempt.
        private final Throwable failure;

        /// Number of physical read attempts.
        private int readCalls;

        /// Next unsigned byte value to return.
        private int nextValue = 1;

        /// Whether this source remains open.
        private boolean open = true;

        /// Creates a fragmented source with the selected failure.
        private FragmentedFailingReadableChannel(Throwable failure) {
            this.failure = failure;
        }

        /// Returns one byte or reports the injected failure without consuming that byte.
        @Override
        public int read(ByteBuffer target) throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
            readCalls++;
            if (readCalls == 2) {
                if (failure instanceof IOException exception) {
                    throw exception;
                }
                if (failure instanceof RuntimeException exception) {
                    throw exception;
                }
                throw (Error) failure;
            }
            if (nextValue > 3) {
                return -1;
            }
            target.put((byte) nextValue++);
            return 1;
        }

        /// Returns whether this source remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this source.
        @Override
        public void close() {
            open = false;
        }
    }

    /// Counts target write attempts.
    @NotNullByDefault
    private static final class CountingOutputStream extends ByteArrayOutputStream {
        /// Number of write attempts.
        private int writeCount;

        /// Writes and counts one byte range.
        @Override
        public void write(byte[] bytes, int offset, int length) {
            writeCount++;
            super.write(bytes, offset, length);
        }

        /// Returns the number of write attempts.
        private int writeCount() {
            return writeCount;
        }
    }

    /// Returns zero progress for every stream read.
    @NotNullByDefault
    private static final class ZeroProgressInputStream extends InputStream {
        /// Returns zero progress.
        @Override
        public int read() {
            return 0;
        }

        /// Returns zero progress.
        @Override
        public int read(byte[] bytes, int offset, int length) {
            return 0;
        }
    }

    /// Returns zero progress for every channel read.
    @NotNullByDefault
    private static final class ZeroProgressReadableChannel implements ReadableByteChannel {
        /// Returns zero progress.
        @Override
        public int read(ByteBuffer target) {
            return 0;
        }

        /// Returns whether this channel remains open.
        @Override
        public boolean isOpen() {
            return true;
        }

        /// Closes this channel.
        @Override
        public void close() {
        }
    }

    /// Returns zero progress for every channel write.
    @NotNullByDefault
    private static final class ZeroProgressWritableChannel implements WritableByteChannel {
        /// Returns zero progress.
        @Override
        public int write(ByteBuffer source) {
            return 0;
        }

        /// Returns whether this channel remains open.
        @Override
        public boolean isOpen() {
            return true;
        }

        /// Closes this channel.
        @Override
        public void close() {
        }
    }

    /// Fails its first close attempt.
    @NotNullByDefault
    private static final class FailingCloseInputStream extends InputStream {
        /// Number of close attempts.
        private int closeCount;

        /// Reports end of input.
        @Override
        public int read() {
            return -1;
        }

        /// Fails the first close attempt.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                throw new IOException("close failed");
            }
        }

        /// Returns the number of close attempts.
        private int closeCount() {
            return closeCount;
        }
    }

    /// Fails its first close attempt.
    @NotNullByDefault
    private static final class FailingCloseOutputStream extends OutputStream {
        /// Number of close attempts.
        private int closeCount;

        /// Accepts one byte.
        @Override
        public void write(int value) {
        }

        /// Fails the first close attempt.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                throw new IOException("close failed");
            }
        }

        /// Returns the number of close attempts.
        private int closeCount() {
            return closeCount;
        }
    }

    /// Fails its first close attempt.
    @NotNullByDefault
    private static final class FailingCloseReadableChannel implements ReadableByteChannel {
        /// Number of close attempts.
        private int closeCount;

        /// Reports end of input.
        @Override
        public int read(ByteBuffer target) {
            return -1;
        }

        /// Returns whether closure has not succeeded.
        @Override
        public boolean isOpen() {
            return closeCount < 2;
        }

        /// Fails the first close attempt.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                throw new IOException("close failed");
            }
        }

        /// Returns the number of close attempts.
        private int closeCount() {
            return closeCount;
        }
    }

    /// Fails its first close attempt.
    @NotNullByDefault
    private static final class FailingCloseWritableChannel implements WritableByteChannel {
        /// Number of close attempts.
        private int closeCount;

        /// Consumes all source bytes.
        @Override
        public int write(ByteBuffer source) {
            int count = source.remaining();
            source.position(source.limit());
            return count;
        }

        /// Returns whether closure has not succeeded.
        @Override
        public boolean isOpen() {
            return closeCount < 2;
        }

        /// Fails the first close attempt.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                throw new IOException("close failed");
            }
        }

        /// Returns the number of close attempts.
        private int closeCount() {
            return closeCount;
        }
    }
}
