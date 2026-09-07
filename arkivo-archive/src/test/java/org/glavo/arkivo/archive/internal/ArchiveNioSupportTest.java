// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.file.DirectoryIteratorException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies reusable NIO support shared by archive-format modules.
@NotNullByDefault
final class ArchiveNioSupportTest {
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

    /// Verifies heap and direct writes plus forward-only positioning constraints.
    @Test
    void adaptsOutputStreamsToForwardOnlyChannels() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ForwardOnlyOutputChannel channel = new ForwardOnlyOutputChannel(output);

        assertEquals(3, channel.write(ByteBuffer.wrap(new byte[]{1, 2, 3})));
        ByteBuffer direct = ByteBuffer.allocateDirect(2).put(new byte[]{4, 5}).flip();
        assertEquals(2, channel.write(direct));
        assertEquals(0, channel.write(ByteBuffer.allocate(0)));
        assertEquals(5L, channel.position());
        assertEquals(5L, channel.size());
        assertEquals(channel, channel.position(5L));
        assertEquals(channel, channel.truncate(5L));
        assertThrows(IllegalArgumentException.class, () -> channel.position(-1L));
        assertThrows(IllegalArgumentException.class, () -> channel.truncate(-1L));
        assertEquals(5L, channel.position());
        assertThrows(NonReadableChannelException.class, () -> channel.read(ByteBuffer.allocate(1)));
        assertThrows(UnsupportedOperationException.class, () -> channel.position(4L));
        assertThrows(UnsupportedOperationException.class, () -> channel.truncate(4L));

        channel.close();
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, output.toByteArray());
        assertThrows(ClosedChannelException.class, channel::position);
    }

    /// Verifies a failed forward-only output close remains retryable.
    @Test
    void retriesForwardOnlyOutputClose() throws IOException {
        FailingCloseOutputStream output = new FailingCloseOutputStream();
        ForwardOnlyOutputChannel channel = new ForwardOnlyOutputChannel(output);

        IOException failure = assertThrows(IOException.class, channel::close);

        assertEquals("close failed", failure.getMessage());
        assertEquals(1, output.closeAttempts());
        assertTrue(channel.isOpen());
        channel.write(ByteBuffer.wrap(new byte[]{1}));

        channel.close();
        channel.close();

        assertEquals(2, output.closeAttempts());
        assertFalse(channel.isOpen());
        assertThrows(ClosedChannelException.class, () -> channel.write(ByteBuffer.allocate(1)));
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
        DirectoryIteratorException exception = assertThrows(DirectoryIteratorException.class, failing::iterator);
        assertEquals("filter failed", exception.getCause().getMessage());
        failing.close();
    }

    /// Collects all entries returned by one fixed directory stream.
    private static <T> List<T> streamEntries(FixedDirectoryStream<T> stream) {
        java.util.ArrayList<T> entries = new java.util.ArrayList<>();
        stream.iterator().forEachRemaining(entries::add);
        return List.copyOf(entries);
    }

    /// Implements an output stream whose first close attempt fails without closing it.
    @NotNullByDefault
    private static final class FailingCloseOutputStream extends OutputStream {
        /// Whether the stream remains open.
        private boolean open = true;

        /// Number of close attempts made while open.
        private int closeAttempts;

        /// Accepts one byte while the stream is open.
        @Override
        public void write(int value) throws IOException {
            if (!open) {
                throw new IOException("stream closed");
            }
        }

        /// Fails the first close attempt and closes on the second.
        @Override
        public void close() throws IOException {
            if (!open) {
                return;
            }
            closeAttempts++;
            if (closeAttempts == 1) {
                throw new IOException("close failed");
            }
            open = false;
        }

        /// Returns the number of close attempts made while open.
        private int closeAttempts() {
            return closeAttempts;
        }
    }
}
