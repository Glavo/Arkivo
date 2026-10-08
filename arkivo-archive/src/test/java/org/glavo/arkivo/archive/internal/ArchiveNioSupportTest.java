// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.file.DirectoryIteratorException;
import java.util.List;
import java.util.ArrayList;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

/// Verifies reusable NIO support shared by archive-format modules.
@NotNullByDefault
final class ArchiveNioSupportTest {
    /// Concurrent traversal consumes each snapshot entry once and serializes filter evaluation.
    @Test
    void sharesDirectoryIteratorBetweenThreads() throws Exception {
        var entries = IntStream.range(0, 512).boxed().toList();
        var calls = new AtomicIntegerArray(entries.size());
        var seen = ConcurrentHashMap.<Integer>newKeySet();
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(4);
        try (var stream = new FixedDirectoryStream<>(entries, entry -> {
            assertEquals(1, calls.incrementAndGet(entry));
            return true;
        })) {
            var iterator = stream.iterator();
            var futures = new ArrayList<Future<?>>();
            for (int worker = 0; worker < 4; worker++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    while (true) {
                        final int entry;
                        try {
                            entry = iterator.next();
                        } catch (NoSuchElementException end) {
                            return true;
                        }
                        assertTrue(seen.add(entry), "Duplicate directory entry");
                    }
                }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get(10, TimeUnit.SECONDS);
            assertEquals(entries.size(), seen.size());
            for (int entry : entries) assertEquals(1, calls.get(entry));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    /// Filtering is deferred until traversal, and repeated read-ahead does not evaluate entries twice.
    @Test
    void filtersDirectorySnapshotIncrementally() {
        var entries = new ArrayList<>(List.of("skip", "first", "second"));
        var calls = new AtomicInteger();
        try (var stream = new FixedDirectoryStream<>(entries, entry -> {
            calls.incrementAndGet();
            return !entry.equals("skip");
        })) {
            entries.clear();
            var iterator = stream.iterator();
            assertEquals(0, calls.get());
            assertTrue(iterator.hasNext());
            assertTrue(iterator.hasNext());
            assertEquals(2, calls.get());
            assertEquals("first", iterator.next());
            assertThrows(UnsupportedOperationException.class, iterator::remove);
            assertEquals("second", iterator.next());
            assertFalse(iterator.hasNext());
            assertThrows(NoSuchElementException.class, iterator::next);
            assertEquals(3, calls.get());
        }
    }

    /// Closing preserves the one accepted lookahead without evaluating further entries.
    @Test
    void retainsDirectoryLookaheadAfterClose() {
        var calls = new AtomicInteger();
        var stream = new FixedDirectoryStream<>(List.of("first", "second"), entry -> {
            calls.incrementAndGet();
            return true;
        });
        var iterator = stream.iterator();
        assertTrue(iterator.hasNext());
        stream.close();
        stream.close();
        assertThrows(IllegalStateException.class, stream::iterator);
        assertEquals("first", iterator.next());
        assertFalse(iterator.hasNext());
        assertThrows(NoSuchElementException.class, iterator::next);
        assertEquals(1, calls.get());
    }

    /// An I/O failure is reported at traversal with its original cause after earlier accepted entries.
    @Test
    void reportsDirectoryFilterFailureDuringTraversal() {
        var failure = new IOException("filter failed");
        try (var stream = new FixedDirectoryStream<>(List.of("first", "broken"), entry -> {
            if (entry.equals("broken")) throw failure;
            return true;
        })) {
            var iterator = stream.iterator();
            assertEquals("first", iterator.next());
            assertSame(failure, assertThrows(DirectoryIteratorException.class, iterator::hasNext).getCause());
        }
    }

    /// Verifies immutable snapshot, positioning, empty-read, and closed-channel behavior.
    @Test
    void exposesReadOnlyByteArraySnapshots() throws IOException {
        byte[] source = {1, 2, 3};
        ReadOnlyByteArrayChannel channel = new ReadOnlyByteArrayChannel(source);
        source[0] = 9;

        ByteBuffer first = ByteBuffer.allocate(2);
        assertEquals(2, channel.read(first));
        assertArrayEquals(new byte[]{1, 2}, first.array());
        channel.position(3L);
        assertEquals(0, channel.read(ByteBuffer.allocate(0)));
        assertEquals(-1, channel.read(ByteBuffer.allocate(1)));
        channel.position(8L);
        assertEquals(8L, channel.position());
        channel.position(Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, channel.position());
        assertEquals(-1, channel.read(ByteBuffer.allocate(1)));
        assertThrows(IllegalArgumentException.class, () -> channel.position(-1L));
        assertEquals(Long.MAX_VALUE, channel.position());
        ByteBuffer writeSource = ByteBuffer.wrap(new byte[]{4});
        assertThrows(NonWritableChannelException.class, () -> channel.write(writeSource));
        assertEquals(0, writeSource.position());
        assertThrows(IllegalArgumentException.class, () -> channel.truncate(-1L));
        assertThrows(NonWritableChannelException.class, () -> channel.truncate(0L));

        channel.close();
        assertFalse(channel.isOpen());
        assertThrows(ClosedChannelException.class, channel::position);
        assertThrows(ClosedChannelException.class, () -> channel.read(ByteBuffer.allocate(1)));
    }

    /// Verifies filtering, checked failure wrapping, snapshotting, and single-iterator semantics.
    @Test
    void exposesFixedDirectoryStreams() {
        FixedDirectoryStream<String> unfiltered = new FixedDirectoryStream<>(List.of("one", "two"));
        assertEquals(List.of("one", "two"), streamEntries(unfiltered));
        unfiltered.close();

        FixedDirectoryStream<String> stream = new FixedDirectoryStream<>(
                List.of("one", "two", "three"),
                entry -> entry.length() == 3
        );
        assertEquals(List.of("one", "two"), streamEntries(stream));
        assertThrows(IllegalStateException.class, stream::iterator);
        stream.close();
        assertThrows(IllegalStateException.class, stream::iterator);

        FixedDirectoryStream<String> failing = new FixedDirectoryStream<>(
                List.of("entry"),
                entry -> {
                    throw new IOException("filter failed");
                }
        );
        DirectoryIteratorException exception = assertThrows(DirectoryIteratorException.class, failing.iterator()::hasNext);
        assertEquals("filter failed", exception.getCause().getMessage());
        failing.close();
    }

    /// Collects all entries returned by one fixed directory stream.
    private static <T> List<T> streamEntries(FixedDirectoryStream<T> stream) {
        java.util.ArrayList<T> entries = new java.util.ArrayList<>();
        stream.iterator().forEachRemaining(entries::add);
        return List.copyOf(entries);
    }
}
