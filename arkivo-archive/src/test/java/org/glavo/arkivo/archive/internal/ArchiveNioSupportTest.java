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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
