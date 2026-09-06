// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies archive probe-prefix replay and owned source lifecycle behavior.
@NotNullByDefault
final class PrefixReplayReadableByteChannelTest {
    /// Verifies sliced prefixes and target windows survive fragmented replay without reading the source early.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replaysLargeSlicedPrefixIntoGuardedWindows(boolean direct) throws IOException {
        byte[] expected = new byte[8193];
        for (int index = 0; index < expected.length; index++) {
            expected[index] = (byte) (index * 31 + (index >>> 8));
        }
        ByteBuffer storage = direct ? ByteBuffer.allocateDirect(expected.length + 8)
                : ByteBuffer.allocate(expected.length + 8);
        storage.position(5).put(expected).limit(expected.length + 5).position(3);
        ByteBuffer prefix = storage.slice().position(2).asReadOnlyBuffer().mark();
        TrackingReadableByteChannel source = new TrackingReadableByteChannel(new byte[]{4, 5});
        try (PrefixReplayReadableByteChannel replay = new PrefixReplayReadableByteChannel(prefix, source)) {
            assertEquals(2, prefix.position());
            assertEquals(expected.length + 2, prefix.limit());
            assertEquals(2, prefix.reset().position());
            prefix.clear();
            ByteBuffer target = direct ? ByteBuffer.allocateDirect(17) : ByteBuffer.allocate(17);
            ByteArrayOutputStream actual = new ByteArrayOutputStream();
            while (actual.size() < expected.length) {
                assertEquals(0, replay.read(ByteBuffer.allocate(0).asReadOnlyBuffer()));
                target.clear().put(0, (byte) 0x55).put(16, (byte) 0x66).position(2).limit(15).mark();
                int read = replay.read(target);
                assertEquals(Math.min(13, expected.length - actual.size()), read);
                assertEquals(2 + read, target.position());
                assertEquals(15, target.limit());
                assertEquals(2, target.reset().position());
                assertEquals(0x55, target.get(0));
                assertEquals(0x66, target.duplicate().clear().get(16));
                for (int index = 0; index < read; index++) {
                    actual.write(target.get());
                }
                assertEquals(0, source.readCalls());
            }
            assertArrayEquals(expected, actual.toByteArray());
            target.clear();
            assertEquals(2, replay.read(target));
            assertEquals(4, target.get(0));
            assertEquals(5, target.get(1));
            assertEquals(-1, replay.read(target));
            assertEquals(3, storage.position());
            assertEquals(expected.length + 5, storage.limit());
        }
        assertEquals(1, source.closeCalls());
    }

    /// Supplies fresh checked and unchecked read failures for each test invocation.
    private static Stream<Throwable> failures() {
        return Stream.of(new IOException("I/O failure"), new IllegalStateException("runtime failure"),
                new AssertionError("error failure"));
    }

    /// Verifies delegate failures preserve partial progress and later reads do not duplicate the replay prefix.
    @ParameterizedTest
    @MethodSource("failures")
    void preservesPartialDelegateProgressWithoutReplayingPrefix(Throwable failure) throws IOException {
        for (int transferred : new int[]{0, 1, 2}) {
            TrackingReadableByteChannel source = new TrackingReadableByteChannel(new byte[]{3, 4});
            source.nextReadFailure = failure;
            source.bytesBeforeFailure = transferred;
            try (PrefixReplayReadableByteChannel replay = new PrefixReplayReadableByteChannel(
                    ByteBuffer.wrap(new byte[]{1, 2}), source
            )) {
                ByteBuffer target = ByteBuffer.allocateDirect(7);
                target.put(new byte[]{9, 6, 6, 6, 6, 8, 7}).position(1).limit(6).mark();
                assertEquals(2, replay.read(target));
                assertEquals(0, source.readCalls());
                assertSame(failure, assertThrows(failure.getClass(), () -> replay.read(target)));
                assertEquals(3 + transferred, target.position());
                assertEquals(6, target.limit());
                assertTrue(replay.isOpen());
                assertEquals(transferred == 2 ? -1 : 2 - transferred, replay.read(target));
                assertEquals(-1, replay.read(target));
                assertEquals(5, target.position());
                assertEquals(1, target.reset().position());
                byte[] actual = new byte[7];
                target.clear().get(actual);
                assertArrayEquals(new byte[]{9, 1, 2, 3, 4, 8, 7}, actual);
            }
            assertEquals(1, source.closeCalls());
        }
    }

    /// Verifies the constructor snapshots prefix bounds and exhausts the prefix before reading the source.
    @Test
    void replaysCapturedPrefixBeforeSource() throws IOException {
        ByteBuffer prefix = ByteBuffer.wrap(new byte[]{9, 1, 2, 8});
        prefix.position(1).limit(3);
        TrackingReadableByteChannel source = new TrackingReadableByteChannel(new byte[]{3});
        PrefixReplayReadableByteChannel replay = new PrefixReplayReadableByteChannel(prefix, source);
        prefix.clear();

        ByteBuffer target = ByteBuffer.allocate(5);
        target.position(1);
        assertEquals(0, replay.read(ByteBuffer.allocate(0)));
        assertEquals(2, replay.read(target));
        assertEquals(0, source.readCalls());
        assertEquals(1, replay.read(target));
        assertEquals(1, source.readCalls());
        assertEquals(-1, replay.read(target));

        assertEquals(4, target.position());
        assertArrayEquals(new byte[]{1, 2, 3}, new byte[]{target.get(1), target.get(2), target.get(3)});
        replay.close();
        assertFalse(source.isOpen());
    }

    /// Verifies a failed destination write does not consume replay bytes.
    @Test
    void retainsPrefixAfterReadOnlyDestinationFailure() throws IOException {
        TrackingReadableByteChannel source = new TrackingReadableByteChannel(new byte[]{3});
        try (PrefixReplayReadableByteChannel replay = new PrefixReplayReadableByteChannel(
                ByteBuffer.wrap(new byte[]{1, 2}),
                source
        )) {
            assertThrows(ReadOnlyBufferException.class, () -> replay.read(
                    ByteBuffer.allocate(2).asReadOnlyBuffer()
            ));

            ByteBuffer target = ByteBuffer.allocate(2);
            assertEquals(2, replay.read(target));
            assertArrayEquals(new byte[]{1, 2}, target.array());
            assertEquals(0, source.readCalls());
        }
    }

    /// Verifies externally closing the owned source makes even buffered replay bytes unavailable.
    @Test
    void observesExternalSourceClosure() throws IOException {
        TrackingReadableByteChannel source = new TrackingReadableByteChannel(new byte[]{3});
        PrefixReplayReadableByteChannel replay = new PrefixReplayReadableByteChannel(
                ByteBuffer.wrap(new byte[]{1, 2}),
                source
        );
        source.close();

        assertFalse(replay.isOpen());
        assertThrows(ClosedChannelException.class, () -> replay.read(ByteBuffer.allocate(1)));
        assertThrows(ClosedChannelException.class, () -> replay.read(ByteBuffer.allocate(0)));
        replay.close();
        assertFalse(replay.isOpen());
    }

    /// Verifies failed source cleanup leaves ordinary closure retryable.
    @Test
    void retriesSourceCloseFailure() throws IOException {
        TrackingReadableByteChannel source = new TrackingReadableByteChannel(new byte[0]);
        source.failFirstClose = true;
        PrefixReplayReadableByteChannel replay = new PrefixReplayReadableByteChannel(
                ByteBuffer.allocate(0),
                source
        );

        IOException failure = assertThrows(IOException.class, replay::close);
        assertEquals("close failure", failure.getMessage());
        assertTrue(replay.isOpen());
        assertEquals(1, source.closeCalls());

        replay.close();
        replay.close();
        assertFalse(replay.isOpen());
        assertFalse(source.isOpen());
        assertEquals(2, source.closeCalls());
        assertThrows(ClosedChannelException.class, () -> replay.read(ByteBuffer.allocate(1)));
    }

    /// Supplies fixed bytes while recording source reads and close attempts.
    @NotNullByDefault
    private static final class TrackingReadableByteChannel implements ReadableByteChannel {
        /// Remaining source content.
        private final @UnmodifiableView ByteBuffer content;

        /// Number of source read calls.
        private int readCalls;

        /// Number of source close calls.
        private int closeCalls;

        /// Whether the first close call should fail before completing.
        private boolean failFirstClose;

        /// Failure reported after the configured partial read, or `null` for ordinary reads.
        private @Nullable Throwable nextReadFailure;

        /// Number of bytes copied before the next read failure.
        private int bytesBeforeFailure;

        /// Whether this source remains open.
        private boolean open = true;

        /// Creates a source over copied bytes.
        private TrackingReadableByteChannel(byte[] content) {
            this.content = ByteBuffer.wrap(content.clone()).asReadOnlyBuffer();
        }

        /// Copies source bytes into the target.
        @Override
        public int read(ByteBuffer target) throws IOException {
            ensureOpen();
            readCalls++;
            if (!target.hasRemaining()) {
                return 0;
            }
            if (!content.hasRemaining()) {
                return -1;
            }
            int count = Math.min(target.remaining(), content.remaining());
            @Nullable Throwable failure = nextReadFailure;
            if (failure != null) {
                count = Math.min(count, bytesBeforeFailure);
            }
            ByteBuffer chunk = content.slice();
            chunk.limit(count);
            target.put(chunk);
            content.position(content.position() + count);
            nextReadFailure = null;
            if (failure instanceof IOException exception) {
                throw exception;
            }
            if (failure instanceof RuntimeException exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            return count;
        }

        /// Returns whether this source remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this source, optionally failing the first attempt.
        @Override
        public void close() throws IOException {
            closeCalls++;
            if (failFirstClose && closeCalls == 1) {
                throw new IOException("close failure");
            }
            open = false;
        }

        /// Returns the number of source read calls.
        private int readCalls() {
            return readCalls;
        }

        /// Returns the number of source close calls.
        private int closeCalls() {
            return closeCalls;
        }

        /// Requires this source to remain open.
        private void ensureOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }
}
