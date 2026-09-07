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
    /// Supplies failures at each cleanup boundary, including unchecked exceptions and errors.
    private static Stream<Arguments> cleanupFailures() {
        return Stream.of(new IOException("cleanup failed"), new IllegalStateException("cleanup failed"),
                new AssertionError("cleanup failed")).flatMap(failure -> Stream.of(Stage.CHANNEL, Stage.CONTENT, Stage.STORAGE)
                .map(stage -> Arguments.of(stage, failure)));
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
        assertEquals(List.of(Stage.CHANNEL), storage.events);
        assertTrue(writer.isOpen());
        writer.close();
        assertEquals(List.of(Stage.CHANNEL, Stage.CHANNEL, Stage.CONTENT, Stage.STORAGE), storage.events);
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
        /// Opens a channel over stored content.
        OPEN,
        /// Closes an independently opened channel.
        CHANNEL,
        /// Releases a stored content object.
        CONTENT,
        /// Closes the backing storage.
        STORAGE
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
                return delegate.read(target);
            }

            /// Writes to the delegate.
            @Override
            public int write(ByteBuffer source) throws IOException {
                return delegate.write(source);
            }

            /// Returns the delegate position.
            @Override
            public long position() throws IOException {
                return delegate.position();
            }

            /// Changes the delegate position.
            @Override
            public SeekableByteChannel position(long position) throws IOException {
                delegate.position(position);
                return this;
            }

            /// Returns the delegate size.
            @Override
            public long size() throws IOException {
                return delegate.size();
            }

            /// Truncates the delegate.
            @Override
            public SeekableByteChannel truncate(long size) throws IOException {
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
                attempt(Stage.CHANNEL);
                delegate.close();
            }
        }
    }
}
