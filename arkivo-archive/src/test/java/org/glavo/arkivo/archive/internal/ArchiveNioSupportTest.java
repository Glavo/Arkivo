// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
        assertThrows(NonWritableChannelException.class, () -> channel.write(ByteBuffer.allocate(1)));
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
        assertThrows(NonReadableChannelException.class, () -> channel.read(ByteBuffer.allocate(1)));
        assertThrows(UnsupportedOperationException.class, () -> channel.position(4L));
        assertThrows(UnsupportedOperationException.class, () -> channel.truncate(4L));

        channel.close();
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, output.toByteArray());
        assertThrows(ClosedChannelException.class, channel::position);
    }

    /// Verifies filtering, checked failure wrapping, snapshotting, and single-iterator semantics.
    @Test
    void exposesFixedDirectoryStreams() {
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

    /// Verifies staged read/write access, append positioning, change tracking, and idempotent completion.
    @Test
    void managesStagedRandomAccessChannels() throws IOException {
        Path path = Files.createTempFile("arkivo-staged-channel", ".bin");
        try {
            Files.write(path, new byte[]{1, 2, 3});
            AtomicBoolean commit = new AtomicBoolean();
            AtomicInteger completions = new AtomicInteger();
            try (SeekableByteChannel storage = Files.newByteChannel(
                    path,
                    Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
            )) {
                StagedSeekableByteChannel channel = new StagedSeekableByteChannel(
                        storage,
                        true,
                        true,
                        true,
                        false,
                        (completed, shouldCommit) -> {
                            assertFalse(completed.isOpen());
                            commit.set(shouldCommit);
                            completions.incrementAndGet();
                        }
                );
                channel.position(0L);
                assertEquals(1, channel.write(ByteBuffer.wrap(new byte[]{4})));
                assertEquals(4L, channel.position());
                channel.position(0L);
                ByteBuffer contents = ByteBuffer.allocate(4);
                assertEquals(4, channel.read(contents));
                assertArrayEquals(new byte[]{1, 2, 3, 4}, contents.array());
                channel.close();
                channel.close();
            }
            assertEquals(1, completions.get());
            assertTrue(commit.get());
        } finally {
            Files.deleteIfExists(path);
        }
    }

    /// Verifies access rejection, write validation, truncation changes, and force-commit behavior.
    @Test
    void enforcesStagedChannelPolicies() throws IOException {
        Path path = Files.createTempFile("arkivo-staged-policy", ".bin");
        try {
            Files.write(path, new byte[]{1, 2, 3});
            AtomicBoolean truncatedCommit = new AtomicBoolean();
            StagedSeekableByteChannel truncated = new StagedSeekableByteChannel(
                    Files.newByteChannel(path, Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)),
                    false,
                    true,
                    false,
                    false,
                    (position, count) -> {
                        if (position + count > 3L) {
                            throw new IOException("staged body too large");
                        }
                    },
                    (channel, commit) -> truncatedCommit.set(commit)
            );
            assertThrows(NonReadableChannelException.class, () -> truncated.read(ByteBuffer.allocate(1)));
            truncated.position(3L);
            IOException limitFailure = assertThrows(
                    IOException.class,
                    () -> truncated.write(ByteBuffer.wrap(new byte[]{4}))
            );
            assertEquals("staged body too large", limitFailure.getMessage());
            truncated.truncate(2L);
            truncated.close();
            assertTrue(truncatedCommit.get());

            AtomicBoolean forcedCommit = new AtomicBoolean();
            StagedSeekableByteChannel forced = new StagedSeekableByteChannel(
                    Files.newByteChannel(path, Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)),
                    true,
                    true,
                    false,
                    true,
                    (channel, commit) -> forcedCommit.set(commit)
            );
            forced.close();
            assertTrue(forcedCommit.get());

            AtomicBoolean readOnlyCommit = new AtomicBoolean(true);
            StagedSeekableByteChannel readOnly = new StagedSeekableByteChannel(
                    Files.newByteChannel(path, StandardOpenOption.READ),
                    true,
                    false,
                    false,
                    true,
                    (channel, commit) -> readOnlyCommit.set(commit)
            );
            assertThrows(NonWritableChannelException.class, () -> readOnly.write(ByteBuffer.allocate(1)));
            assertThrows(NonWritableChannelException.class, () -> readOnly.truncate(0L));
            readOnly.close();
            assertFalse(readOnlyCommit.get());
            assertThrows(ClosedChannelException.class, readOnly::position);
        } finally {
            Files.deleteIfExists(path);
        }
    }

    /// Verifies that storage close failures prevent commits and retain completion failures as suppressed exceptions.
    @Test
    void preservesStagedChannelCloseFailures() throws IOException {
        Path path = Files.createTempFile("arkivo-staged-close", ".bin");
        try {
            AtomicBoolean commit = new AtomicBoolean(true);
            StagedSeekableByteChannel channel = new StagedSeekableByteChannel(
                    new CloseFailingChannel(Files.newByteChannel(
                            path,
                            Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
                    )),
                    true,
                    true,
                    false,
                    true,
                    (completed, shouldCommit) -> {
                        commit.set(shouldCommit);
                        throw new IOException("completion failed");
                    }
            );

            IOException exception = assertThrows(IOException.class, channel::close);
            assertEquals("storage close failed", exception.getMessage());
            assertEquals(1, exception.getSuppressed().length);
            assertEquals("completion failed", exception.getSuppressed()[0].getMessage());
            assertFalse(commit.get());
            channel.close();
        } finally {
            Files.deleteIfExists(path);
        }
    }

    /// Collects all entries returned by one fixed directory stream.
    private static <T> List<T> streamEntries(FixedDirectoryStream<T> stream) {
        java.util.ArrayList<T> entries = new java.util.ArrayList<>();
        stream.iterator().forEachRemaining(entries::add);
        return List.copyOf(entries);
    }

    /// Delegates seekable operations and reports a deterministic failure after closing its storage.
    ///
    /// @param channel the wrapped storage channel
    @NotNullByDefault
    private record CloseFailingChannel(SeekableByteChannel channel) implements SeekableByteChannel {
        /// Reads from wrapped storage.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            return channel.read(destination);
        }

        /// Writes to wrapped storage.
        @Override
        public int write(ByteBuffer source) throws IOException {
            return channel.write(source);
        }

        /// Returns the wrapped position.
        @Override
        public long position() throws IOException {
            return channel.position();
        }

        /// Changes the wrapped position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            channel.position(newPosition);
            return this;
        }

        /// Returns the wrapped size.
        @Override
        public long size() throws IOException {
            return channel.size();
        }

        /// Truncates wrapped storage.
        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            channel.truncate(size);
            return this;
        }

        /// Returns whether wrapped storage remains open.
        @Override
        public boolean isOpen() {
            return channel.isOpen();
        }

        /// Closes wrapped storage and reports the configured failure.
        @Override
        public void close() throws IOException {
            channel.close();
            throw new IOException("storage close failed");
        }
    }
}
