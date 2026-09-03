// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnmodifiableView;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies archive probe-prefix replay and owned source lifecycle behavior.
@NotNullByDefault
final class PrefixReplayReadableByteChannelTest {
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
            ByteBuffer chunk = content.slice();
            chunk.limit(count);
            target.put(chunk);
            content.position(content.position() + count);
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
