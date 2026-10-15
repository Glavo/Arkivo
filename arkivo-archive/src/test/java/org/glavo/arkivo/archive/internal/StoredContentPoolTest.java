// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoStoredContent;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies cleanup ordering, deferred ownership, and retries across content and channel lifetimes.
@NotNullByDefault
final class StoredContentPoolTest {
    /// Supplies checked, unchecked, and error failures for concurrent cleanup.
    private static Stream<Throwable> readFailures() {
        return Stream.of(new IOException("read failed"), new IllegalStateException("read failed"),
                new AssertionError("read failed"));
    }

    /// Allows close to wait for a blocked read to return through failure bookkeeping without a lock cycle.
    @ParameterizedTest
    @MethodSource("readFailures")
    void closesWhileReadReportsFailure(Throwable failure) throws Exception {
        TrackingStorage storage = new TrackingStorage();
        ReadCloseGate gate = new ReadCloseGate();
        storage.readCloseGate = gate;
        StoredContentPool pool = new StoredContentPool(storage);
        ArkivoStoredContent content = pool.createContent("entry", 0);
        SeekableByteChannel channel = pool.openReadChannel(content);
        pool.close();
        assertFalse(pool.isClosed());
        storage.fail(Stage.READ, failure);
        FutureTask<Throwable> reader = new FutureTask<>(() -> {
            try {
                return assertThrows(failure.getClass(), () -> channel.read(ByteBuffer.allocate(1)));
            } finally {
                gate.readReturned.countDown();
            }
        });
        FutureTask<@Nullable Void> closer = new FutureTask<>(() -> {
            channel.close();
            return null;
        });
        startDaemon(reader, "stored-content-reader");
        try {
            assertTrue(gate.readStarted.await(5, TimeUnit.SECONDS));
            startDaemon(closer, "stored-content-closer");
            closer.get(5, TimeUnit.SECONDS);
            assertSame(failure, reader.get(5, TimeUnit.SECONDS));
            assertTrue(pool.isClosed());
            assertEquals(List.of(Stage.CREATE, Stage.OPEN, Stage.READ, Stage.CHANNEL, Stage.CONTENT, Stage.STORAGE),
                    storage.events);
            channel.close();
            pool.close();
            assertEquals(6, storage.events.size());
        } finally {
            gate.closeStarted.countDown();
            gate.readReturned.countDown();
            reader.get(5, TimeUnit.SECONDS);
            if (closer.isDone()) closer.get(5, TimeUnit.SECONDS);
            channel.close();
            pool.close();
        }
    }

    /// Starts a daemon so a broken locking implementation cannot keep the test worker alive.
    private static void startDaemon(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }

    /// Supplies every delegate operation whose failure may be repeated by channel cleanup.
    private static Stream<Arguments> operationFailures() {
        return Stream.of(Stage.READ, Stage.WRITE, Stage.POSITION, Stage.SEEK, Stage.SIZE, Stage.TRUNCATE)
                .flatMap(stage -> Stream.of(new IOException("operation failed"),
                        new IllegalStateException("operation failed"), new AssertionError("operation failed"))
                        .map(failure -> Arguments.of(stage, failure)));
    }

    /// Preserves operation failures, rejects I/O after failed close, and retains a cleanup retry handle.
    @ParameterizedTest
    @MethodSource("operationFailures")
    void preservesOperationFailureDuringAutomaticClose(Stage stage, Throwable failure) throws IOException {
        TrackingStorage storage = new TrackingStorage();
        try (StoredContentPool pool = new StoredContentPool(storage)) {
            ArkivoStoredContent content = pool.createContent("entry", 0);
            SeekableByteChannel channel = content.openChannel(Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE));
            storage.fail(stage, failure);
            storage.fail(Stage.CHANNEL, failure);
            assertSame(failure, assertThrows(failure.getClass(), () -> {
                try (channel) {
                    switch (stage) {
                        case READ -> channel.read(ByteBuffer.allocate(1));
                        case WRITE -> channel.write(ByteBuffer.wrap(new byte[]{1}));
                        case POSITION -> channel.position();
                        case SEEK -> channel.position(1);
                        case SIZE -> channel.size();
                        case TRUNCATE -> channel.truncate(0);
                        default -> throw new AssertionError(stage);
                    }
                }
            }));
            assertEquals(1, failure.getSuppressed().length);
            assertSame(failure, assertInstanceOf(IOException.class, failure.getSuppressed()[0]).getCause());
            assertFalse(channel.isOpen());
            assertThrows(ClosedChannelException.class, () -> channel.read(ByteBuffer.allocate(0)));
            channel.close();
            assertEquals(List.of(Stage.CREATE, Stage.OPEN, stage, Stage.CHANNEL, Stage.CHANNEL), storage.events);
        }
        assertEquals(List.of(Stage.CONTENT, Stage.STORAGE), storage.events.subList(5, 7));
    }

    /// Supplies allocation, open, and content-size failures for all supported exception categories.
    private static Stream<Arguments> contentFailures() {
        return Stream.of(Stage.CREATE, Stage.OPEN, Stage.CONTENT_SIZE)
                .flatMap(stage -> Stream.of(new IOException("content failed"),
                        new IllegalStateException("content failed"), new AssertionError("content failed"))
                        .map(failure -> Arguments.of(stage, failure)));
    }

    /// Preserves failures from content access when subsequent pool cleanup repeats their identity.
    @ParameterizedTest
    @MethodSource("contentFailures")
    void preservesContentFailureDuringAutomaticClose(Stage stage, Throwable failure) throws IOException {
        TrackingStorage storage = new TrackingStorage();
        StoredContentPool pool = new StoredContentPool(storage);
        ArkivoStoredContent content = pool.createContent("entry", 0);
        storage.fail(stage, failure);
        storage.fail(Stage.CONTENT, failure);
        assertSame(failure, assertThrows(failure.getClass(), () -> {
            try (pool) {
                switch (stage) {
                    case CREATE -> pool.createContent("other", 0);
                    case OPEN -> content.openChannel(Set.of(StandardOpenOption.READ));
                    case CONTENT_SIZE -> content.size();
                    default -> throw new AssertionError(stage);
                }
            }
        }));
        assertEquals(1, failure.getSuppressed().length);
        assertSame(failure, assertInstanceOf(IOException.class, failure.getSuppressed()[0]).getCause());
        assertFalse(pool.isClosed());
        pool.close();
        assertTrue(pool.isClosed());
    }

    /// Preserves transfer failures when storage cleanup reports the same checked or unchecked exception.
    @Test
    void preservesStoredInputFailureDuringCleanup() throws IOException {
        for (Throwable failure : new Throwable[]{new IOException("write failed"),
                new IllegalStateException("write failed"), new AssertionError("write failed")}) {
            TrackingStorage storage = new TrackingStorage();
            try (StoredContentPool pool = new StoredContentPool(storage)) {
                storage.fail(Stage.WRITE, failure);
                storage.fail(Stage.CHANNEL, failure);
                assertSame(failure, assertThrows(failure.getClass(), () -> StoredContentSupport.storeInput(
                        pool, "entry", 1, new ByteArrayInputStream(new byte[]{1})
                )));
                assertEquals(1, failure.getSuppressed().length);
                assertSame(failure, assertInstanceOf(IOException.class, failure.getSuppressed()[0]).getCause());
                assertEquals(List.of(Stage.CREATE, Stage.OPEN, Stage.WRITE, Stage.CHANNEL, Stage.CHANNEL, Stage.CONTENT),
                        storage.events);
            }
            assertEquals(Stage.STORAGE, storage.events.get(storage.events.size() - 1));
        }
    }

    /// Closes both copy channels after failures without losing the original exception or cleanup retry handles.
    @Test
    void preservesCopyFailureDuringCleanup() throws IOException {
        for (boolean failRead : new boolean[]{false, true}) {
            for (boolean shared : new boolean[]{false, true}) {
                for (Throwable failure : new Throwable[]{new IOException("transfer failed"),
                        new IllegalStateException("transfer failed"), new AssertionError("transfer failed")}) {
                    TrackingStorage inputStorage = new TrackingStorage();
                    TrackingStorage outputStorage = new TrackingStorage();
                    try (StoredContentPool inputPool = new StoredContentPool(inputStorage);
                         StoredContentPool outputPool = new StoredContentPool(outputStorage)) {
                        ArkivoStoredContent source = StoredContentSupport.storeInput(
                                inputPool, "source", 1, new ByteArrayInputStream(new byte[]{1})
                        );
                        ArkivoStoredContent destination = outputPool.createContent("destination", 0);
                        inputStorage.events.clear();
                        outputStorage.events.clear();
                        (failRead ? inputStorage : outputStorage).fail(failRead ? Stage.READ : Stage.WRITE, failure);
                        Throwable inputClose = shared ? failure : new IOException("input close failed");
                        Throwable outputClose = shared ? failure : new AssertionError("output close failed");
                        inputStorage.fail(Stage.CHANNEL, inputClose);
                        outputStorage.fail(Stage.CHANNEL, outputClose);

                        assertSame(failure, assertThrows(failure.getClass(), () ->
                                StoredContentSupport.copyContent(source, destination)));
                        if (shared) {
                            assertEquals(1, failure.getSuppressed().length);
                            assertSame(failure,
                                    assertInstanceOf(IOException.class, failure.getSuppressed()[0]).getCause());
                        } else {
                            assertArrayEquals(new Throwable[]{inputClose, outputClose}, failure.getSuppressed());
                        }
                        assertEquals(List.of(Stage.OPEN, Stage.READ, Stage.CHANNEL), inputStorage.events);
                        assertEquals(failRead ? List.of(Stage.OPEN, Stage.CHANNEL)
                                : List.of(Stage.OPEN, Stage.WRITE, Stage.CHANNEL), outputStorage.events);
                    }
                    assertEquals(List.of(Stage.CHANNEL, Stage.CONTENT, Stage.STORAGE),
                            inputStorage.events.subList(inputStorage.events.size() - 3, inputStorage.events.size()));
                    assertEquals(List.of(Stage.CHANNEL, Stage.CONTENT, Stage.STORAGE),
                            outputStorage.events.subList(outputStorage.events.size() - 3, outputStorage.events.size()));
                }
            }
        }
    }

    /// Closes the output when opening or closing the input fails, including repeated failure identities.
    @Test
    void preservesCopyLifecycleFailureDuringCleanup() throws IOException {
        for (boolean failOpen : new boolean[]{false, true}) {
            for (boolean shared : new boolean[]{false, true}) {
                TrackingStorage inputStorage = new TrackingStorage();
                TrackingStorage outputStorage = new TrackingStorage();
                try (StoredContentPool inputPool = new StoredContentPool(inputStorage);
                     StoredContentPool outputPool = new StoredContentPool(outputStorage)) {
                    ArkivoStoredContent source = StoredContentSupport.storeInput(
                            inputPool, "source", 1, new ByteArrayInputStream(new byte[]{1})
                    );
                    ArkivoStoredContent destination = outputPool.createContent("destination", 0);
                    inputStorage.events.clear();
                    outputStorage.events.clear();
                    IOException failure = new IOException("input lifecycle failed");
                    Throwable outputFailure = shared ? failure : new AssertionError("output close failed");
                    inputStorage.fail(failOpen ? Stage.OPEN : Stage.CHANNEL, failure);
                    outputStorage.fail(Stage.CHANNEL, outputFailure);

                    assertSame(failure, assertThrows(IOException.class, () ->
                            StoredContentSupport.copyContent(source, destination)));
                    assertArrayEquals(shared ? new Throwable[0] : new Throwable[]{outputFailure},
                            failure.getSuppressed());
                    assertEquals(failOpen ? List.of(Stage.OPEN)
                            : List.of(Stage.OPEN, Stage.READ, Stage.READ, Stage.CHANNEL), inputStorage.events);
                    assertEquals(failOpen ? List.of(Stage.OPEN, Stage.CHANNEL)
                            : List.of(Stage.OPEN, Stage.WRITE, Stage.CHANNEL), outputStorage.events);
                }
                assertEquals(Stage.STORAGE, inputStorage.events.get(inputStorage.events.size() - 1));
                assertEquals(Stage.STORAGE, outputStorage.events.get(outputStorage.events.size() - 1));
            }
        }
    }

    /// Supplies failures at each cleanup boundary, including unchecked exceptions and errors.
    private static Stream<Arguments> cleanupFailures() {
        return Stream.of(Stage.CHANNEL, Stage.CONTENT, Stage.STORAGE)
                .flatMap(stage -> Stream.of(new IOException("cleanup failed"),
                        new IllegalStateException("cleanup failed"), new AssertionError("cleanup failed"))
                        .map(failure -> Arguments.of(stage, failure)));
    }

    /// Preserves the first failure when explicit close is followed by automatic close and then a successful retry.
    @ParameterizedTest
    @MethodSource("cleanupFailures")
    void preservesRepeatedCleanupFailure(Stage stage, Throwable failure) throws IOException {
        TrackingStorage storage = new TrackingStorage();
        StoredContentPool pool = new StoredContentPool(storage);
        ArkivoStoredContent content = pool.createContent("entry", 0);
        SeekableByteChannel channel = content.openChannel(Set.of(StandardOpenOption.READ));
        if (stage != Stage.CHANNEL) channel.close();
        storage.fail(stage, failure, failure);
        if (stage == Stage.CHANNEL) {
            assertSame(failure, assertThrows(failure.getClass(), () -> {
                try (channel) {
                    channel.close();
                }
            }));
        } else if (stage == Stage.CONTENT) {
            assertSame(failure, assertThrows(failure.getClass(), () -> {
                try (content) {
                    content.close();
                }
            }));
        } else {
            assertSame(failure, assertThrows(failure.getClass(), () -> {
                try (pool) {
                    pool.close();
                }
            }));
        }
        assertEquals(1, failure.getSuppressed().length);
        assertSame(failure, assertInstanceOf(IOException.class, failure.getSuppressed()[0]).getCause());
        pool.close();
        assertTrue(pool.isClosed());
        assertEquals(3L, storage.events.stream().filter(event -> event == stage).count());
        int count = storage.events.size();
        channel.close();
        content.close();
        pool.close();
        assertEquals(count, storage.events.size());
    }

    /// Verifies a failed cleanup step is retried before resources below it can be released.
    @ParameterizedTest
    @MethodSource("cleanupFailures")
    void retriesCleanupInDependencyOrder(Stage stage, Throwable failure) throws IOException {
        TrackingStorage storage = new TrackingStorage();
        StoredContentPool pool = new StoredContentPool(storage);
        ArkivoStoredContent content = pool.createContent("entry", 3);
        try (SeekableByteChannel output = content.openChannel(Set.of(StandardOpenOption.WRITE))) {
            output.write(ByteBuffer.wrap(new byte[]{1, 2, 3}));
        }
        SeekableByteChannel channel = pool.openReadChannel(content);
        storage.events.clear();
        pool.close();
        assertFalse(pool.isClosed());
        assertEquals(List.of(), storage.events);
        storage.fail(stage, failure);
        assertSame(failure, assertThrows(failure.getClass(), channel::close));
        assertFalse(channel.isOpen());
        assertThrows(ClosedChannelException.class, channel::position);
        assertFalse(pool.isClosed());
        pool.close();
        assertTrue(pool.isClosed());
        List<Stage> expected = new ArrayList<>(List.of(Stage.CHANNEL, Stage.CONTENT, Stage.STORAGE));
        expected.add(expected.indexOf(stage), stage);
        assertEquals(expected, storage.events);
        channel.close();
        content.close();
        pool.close();
        assertEquals(expected, storage.events);
    }

    /// Verifies a failed staging-writer close remains reachable even when its caller drops the channel.
    @Test
    void retainsFailedWriteChannelForPoolCleanup() throws IOException {
        TrackingStorage storage = new TrackingStorage();
        StoredContentPool pool = new StoredContentPool(storage);
        ArkivoStoredContent content = pool.createContent("entry", 3);
        SeekableByteChannel output = content.openChannel(Set.of(StandardOpenOption.WRITE));
        output.write(ByteBuffer.wrap(new byte[]{1, 2, 3}));
        storage.events.clear();
        IOException failure = new IOException("writer close failed");
        storage.fail(Stage.CHANNEL, failure);
        assertSame(failure, assertThrows(IOException.class, output::close));
        assertEquals(List.of(Stage.CHANNEL), storage.events);
        pool.close();
        assertEquals(List.of(Stage.CHANNEL, Stage.CHANNEL, Stage.CONTENT, Stage.STORAGE), storage.events);
        assertTrue(pool.isClosed());
    }

    /// Verifies content and pool closure do not invalidate channels already handed to callers.
    @Test
    void defersReleaseUntilEveryChannelCloses() throws IOException {
        TrackingStorage storage = new TrackingStorage();
        StoredContentPool pool = new StoredContentPool(storage);
        ArkivoStoredContent content = pool.createContent("entry", 3);
        SeekableByteChannel writer = content.openChannel(Set.of(StandardOpenOption.WRITE));
        writer.write(ByteBuffer.wrap(new byte[]{1, 2, 3}));
        SeekableByteChannel reader = pool.openReadChannel(content);
        storage.events.clear();
        content.close();
        pool.close();
        assertFalse(pool.isClosed());
        assertEquals(List.of(), storage.events);
        assertThrows(ClosedChannelException.class, () -> pool.createContent("late", 0));
        assertThrows(ClosedChannelException.class, () -> content.openChannel(Set.of()));
        ByteBuffer target = ByteBuffer.allocate(3);
        assertEquals(3, reader.read(target));
        assertArrayEquals(new byte[]{1, 2, 3}, target.array());
        assertThrows(NonWritableChannelException.class, () -> reader.write(ByteBuffer.allocate(0)));
        assertThrows(IllegalArgumentException.class, () -> reader.truncate(-1));
        reader.close();
        assertEquals(List.of(Stage.READ, Stage.CHANNEL), storage.events);
        assertTrue(writer.isOpen());
        writer.close();
        assertEquals(List.of(Stage.READ, Stage.CHANNEL, Stage.CHANNEL, Stage.CONTENT, Stage.STORAGE), storage.events);
        assertTrue(pool.isClosed());
    }

    /// Verifies failed opens leave content tracked for cleanup and foreign content is never adopted.
    @Test
    void rejectsForeignContentAndRetainsFailedOpens() throws IOException {
        TrackingStorage storage = new TrackingStorage();
        try (StoredContentPool pool = new StoredContentPool(storage);
             StoredContentPool other = new StoredContentPool(ArkivoEditStorage.memory())) {
            ArkivoStoredContent foreign = other.createContent("foreign", 0);
            assertThrows(IllegalArgumentException.class, () -> pool.openReadChannel(foreign));
            ArkivoStoredContent content = pool.createContent("entry", 0);
            IOException failure = new IOException("open failed");
            storage.fail(Stage.OPEN, failure);
            assertSame(failure, assertThrows(IOException.class, () -> pool.openReadChannel(content)));
            storage.events.clear();
        }
        assertEquals(List.of(Stage.CONTENT, Stage.STORAGE), storage.events);
    }

    /// Verifies cleanup of independent contents continues after failures and avoids self-suppression.
    @Test
    void aggregatesFailuresWithoutLosingRetryHandles() throws IOException {
        for (boolean same : new boolean[]{false, true}) {
            TrackingStorage storage = new TrackingStorage();
            StoredContentPool pool = new StoredContentPool(storage);
            pool.createContent("first", 0);
            pool.createContent("second", 0);
            storage.events.clear();
            IOException first = new IOException("first cleanup");
            Throwable second = same ? first : new AssertionError("second cleanup");
            storage.fail(Stage.CONTENT, first, second);
            assertSame(first, assertThrows(IOException.class, pool::close));
            assertArrayEquals(same ? new Throwable[0] : new Throwable[]{second}, first.getSuppressed());
            assertEquals(List.of(Stage.CONTENT, Stage.CONTENT), storage.events);
            pool.close();
            assertTrue(pool.isClosed());
            assertEquals(List.of(Stage.CONTENT, Stage.CONTENT, Stage.CONTENT, Stage.CONTENT, Stage.STORAGE), storage.events);
        }
    }

    /// Verifies real temporary-file channels preserve their interruptible capability through both open paths.
    @Test
    void preservesFileChannelCapability(@TempDir Path directory) throws IOException {
        try (StoredContentPool pool = new StoredContentPool(ArkivoEditStorage.temporaryFiles(directory))) {
            ArkivoStoredContent content = pool.createContent("entry", 0);
            try (SeekableByteChannel writer = content.openChannel(Set.of(StandardOpenOption.WRITE))) {
                assertInstanceOf(InterruptibleChannel.class, writer);
            }
            try (SeekableByteChannel reader = pool.openReadChannel(content)) {
                assertInstanceOf(InterruptibleChannel.class, reader);
                assertEquals(-1, reader.read(ByteBuffer.allocate(1)));
            }
        }
    }

    /// Identifies one observable storage lifecycle operation.
    @NotNullByDefault
    private enum Stage {
        /// Allocates a stored content object.
        CREATE,
        /// Opens a channel over stored content.
        OPEN,
        /// Queries a stored content object's length.
        CONTENT_SIZE,
        /// Reads bytes from an opened channel.
        READ,
        /// Writes bytes to an opened channel.
        WRITE,
        /// Queries the channel position.
        POSITION,
        /// Changes the channel position.
        SEEK,
        /// Queries the channel length.
        SIZE,
        /// Truncates the channel content.
        TRUNCATE,
        /// Closes an independently opened channel.
        CHANNEL,
        /// Releases a stored content object.
        CONTENT,
        /// Closes the backing storage.
        STORAGE
    }

    /// Coordinates a delegate whose close waits until its blocked read has returned to its caller.
    @NotNullByDefault
    private static final class ReadCloseGate {
        /// Signals entry into the delegate read.
        private final CountDownLatch readStarted = new CountDownLatch(1);

        /// Releases the blocked delegate read once close has started.
        private final CountDownLatch closeStarted = new CountDownLatch(1);

        /// Signals that failure propagation has completed outside the pool wrapper.
        private final CountDownLatch readReturned = new CountDownLatch(1);

        /// Waits for one lifecycle transition, failing rather than hanging the test worker.
        private static void await(CountDownLatch latch) throws IOException {
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("Stored-content lifecycle transition timed out");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException(failure);
            }
        }
    }

    /// Records operations and injects failures while keeping actual bytes in memory.
    @NotNullByDefault
    private static final class TrackingStorage implements ArkivoEditStorage {
        /// The backing storage used for ordinary byte operations.
        private final ArkivoEditStorage delegate = ArkivoEditStorage.memory();

        /// Operations observed in invocation order.
        private final List<Stage> events = new ArrayList<>();

        /// Failure queues consumed independently at each lifecycle boundary.
        private final EnumMap<Stage, ArrayDeque<Throwable>> failures = new EnumMap<>(Stage.class);

        /// Optional coordination for a single read and its concurrent close.
        private @Nullable ReadCloseGate readCloseGate;

        /// Arms one or more failures for a lifecycle boundary.
        private void fail(Stage stage, Throwable... values) {
            failures.computeIfAbsent(stage, ignored -> new ArrayDeque<>()).addAll(List.of(values));
        }

        /// Records one operation and throws its next armed failure unchanged.
        private void attempt(Stage stage) throws IOException {
            events.add(stage);
            @Nullable ArrayDeque<Throwable> queue = failures.get(stage);
            @Nullable Throwable failure = queue == null ? null : queue.poll();
            if (failure instanceof IOException exception) {
                throw exception;
            }
            if (failure instanceof RuntimeException exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }

        /// Allocates tracked content in the in-memory delegate.
        @Override
        public ArkivoStoredContent createContent(String path, long expectedSize) throws IOException {
            attempt(Stage.CREATE);
            return new TrackingContent(delegate.createContent(path, expectedSize));
        }

        /// Records and closes the backing storage.
        @Override
        public void close() throws IOException {
            attempt(Stage.STORAGE);
            delegate.close();
        }

        /// Wraps content opens and deletion without altering byte access.
        @NotNullByDefault
        private final class TrackingContent implements ArkivoStoredContent {
            /// The retained byte content.
            private final ArkivoStoredContent delegate;

            /// Wraps one newly allocated content object.
            private TrackingContent(ArkivoStoredContent delegate) {
                this.delegate = delegate;
            }

            /// Opens a channel whose cleanup can be failed independently.
            @Override
            public SeekableByteChannel openChannel(Set<? extends OpenOption> options) throws IOException {
                attempt(Stage.OPEN);
                return new TrackingChannel(delegate.openChannel(options));
            }

            /// Returns the delegate size.
            @Override
            public long size() throws IOException {
                attempt(Stage.CONTENT_SIZE);
                return delegate.size();
            }

            /// Records and releases the retained content.
            @Override
            public void close() throws IOException {
                attempt(Stage.CONTENT);
                delegate.close();
            }
        }

        /// Delegates byte operations and injects channel-close failures before releasing the delegate.
        @NotNullByDefault
        private final class TrackingChannel implements SeekableByteChannel {
            /// The independently opened channel.
            private final SeekableByteChannel delegate;

            /// Wraps a caller-owned channel.
            private TrackingChannel(SeekableByteChannel delegate) {
                this.delegate = delegate;
            }

            /// Reads from the delegate.
            @Override
            public int read(ByteBuffer target) throws IOException {
                if (readCloseGate != null) {
                    readCloseGate.readStarted.countDown();
                    ReadCloseGate.await(readCloseGate.closeStarted);
                }
                attempt(Stage.READ);
                return delegate.read(target);
            }

            /// Writes to the delegate.
            @Override
            public int write(ByteBuffer source) throws IOException {
                attempt(Stage.WRITE);
                return delegate.write(source);
            }

            /// Returns the delegate position.
            @Override
            public long position() throws IOException {
                attempt(Stage.POSITION);
                return delegate.position();
            }

            /// Changes the delegate position.
            @Override
            public SeekableByteChannel position(long position) throws IOException {
                attempt(Stage.SEEK);
                delegate.position(position);
                return this;
            }

            /// Returns the delegate size.
            @Override
            public long size() throws IOException {
                attempt(Stage.SIZE);
                return delegate.size();
            }

            /// Truncates the delegate.
            @Override
            public SeekableByteChannel truncate(long size) throws IOException {
                attempt(Stage.TRUNCATE);
                delegate.truncate(size);
                return this;
            }

            /// Returns whether the delegate remains open.
            @Override
            public boolean isOpen() {
                return delegate.isOpen();
            }

            /// Records and closes the delegate.
            @Override
            public void close() throws IOException {
                if (readCloseGate != null) {
                    readCloseGate.closeStarted.countDown();
                    ReadCloseGate.await(readCloseGate.readReturned);
                }
                attempt(Stage.CHANNEL);
                delegate.close();
            }
        }
    }
}
