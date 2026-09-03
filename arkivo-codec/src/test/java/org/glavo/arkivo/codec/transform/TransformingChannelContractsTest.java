// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.transform;

import org.glavo.arkivo.codec.ResourceOwnership;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests channel-oriented byte-transform progress, failure, and lifecycle contracts.
@NotNullByDefault
public final class TransformingChannelContractsTest {
    /// A transform that immediately commits every supplied byte without changing it.
    private static final ByteTransform IDENTITY = (buffer, offset, length) -> length;

    /// Verifies constructors, empty buffers, finish state, and borrowed endpoint ownership.
    @Test
    public void validatesArgumentsAndLifecycle() throws IOException {
        ReadableByteChannel source = Channels.newChannel(new ByteArrayInputStream(new byte[]{1}));
        WritableByteChannel target = Channels.newChannel(new ByteArrayOutputStream());
        assertThrows(NullPointerException.class, () -> new TransformingReadableByteChannel(null, IDENTITY));
        assertThrows(NullPointerException.class, () -> new TransformingReadableByteChannel(source, null));
        assertThrows(
                NullPointerException.class,
                () -> new TransformingReadableByteChannel(source, IDENTITY, null)
        );
        assertThrows(NullPointerException.class, () -> new TransformingWritableByteChannel(null, IDENTITY));
        assertThrows(NullPointerException.class, () -> new TransformingWritableByteChannel(target, null));
        assertThrows(
                NullPointerException.class,
                () -> new TransformingWritableByteChannel(target, IDENTITY, null)
        );

        TransformingReadableByteChannel input = new TransformingReadableByteChannel(source, IDENTITY);
        assertEquals(0, input.read(ByteBuffer.allocate(0)));
        ByteBuffer oneByte = ByteBuffer.allocate(1);
        assertEquals(1, input.read(oneByte));
        input.close();
        input.close();
        assertFalse(input.isOpen());
        assertTrue(source.isOpen());
        assertThrows(ClosedChannelException.class, () -> input.read(ByteBuffer.allocate(0)));
        assertThrows(NullPointerException.class, () -> input.read(null));

        TransformingWritableByteChannel output = new TransformingWritableByteChannel(target, IDENTITY);
        assertEquals(0, output.write(ByteBuffer.allocate(0)));
        output.finish();
        output.finish();
        assertTrue(output.isOpen());
        IOException finished = assertThrows(
                IOException.class,
                () -> output.write(ByteBuffer.wrap(new byte[]{1}))
        );
        assertEquals("Byte filter channel has already finished", finished.getMessage());
        output.close();
        output.close();
        assertFalse(output.isOpen());
        assertTrue(target.isOpen());
        assertThrows(ClosedChannelException.class, output::finish);
        assertThrows(ClosedChannelException.class, () -> output.write(ByteBuffer.allocate(0)));
        assertThrows(NullPointerException.class, () -> output.write(null));

        source.close();
        target.close();
    }

    /// Verifies wrappers tolerate endpoints that transfer only one byte per operation.
    @Test
    public void handlesPartialEndpointProgress() throws IOException {
        byte[] expected = new byte[257];
        for (int index = 0; index < expected.length; index++) {
            expected[index] = (byte) (index * 17);
        }

        OneByteWritableChannel target = new OneByteWritableChannel();
        TransformingWritableByteChannel output = new TransformingWritableByteChannel(target, IDENTITY);
        ByteBuffer source = ByteBuffer.wrap(expected);
        int sourceLimit = source.limit();
        assertEquals(expected.length, output.write(source));
        assertEquals(sourceLimit, source.position());
        assertEquals(sourceLimit, source.limit());
        output.finish();
        assertArrayEquals(expected, target.bytes());

        OneByteReadableChannel encoded = new OneByteReadableChannel(expected);
        TransformingReadableByteChannel input = new TransformingReadableByteChannel(encoded, IDENTITY);
        ByteBuffer decoded = ByteBuffer.allocate(expected.length);
        assertEquals(expected.length, input.read(decoded));
        assertEquals(-1, input.read(ByteBuffer.allocate(1)));
        assertArrayEquals(expected, decoded.array());
    }

    /// Verifies a short suffix retained by the transform is returned unchanged before end of input.
    @Test
    public void inputReturnsUncommittedSuffixBeforeEndOfInput() throws IOException {
        TransformingReadableByteChannel input = new TransformingReadableByteChannel(
                Channels.newChannel(new ByteArrayInputStream(new byte[]{1, 2})),
                (buffer, offset, length) -> 0
        );
        ByteBuffer target = ByteBuffer.allocate(3);
        assertEquals(2, input.read(target));
        assertArrayEquals(new byte[]{1, 2, 0}, target.array());
        assertEquals(-1, input.read(ByteBuffer.allocate(1)));
    }

    /// Verifies zero-progress endpoint failures are stable and retained by later operations.
    @Test
    @Timeout(5)
    public void rejectsAndRetainsZeroProgressEndpointFailures() {
        TransformingReadableByteChannel input = new TransformingReadableByteChannel(
                new ZeroProgressReadableChannel(),
                IDENTITY
        );
        IOException readFailure = assertThrows(IOException.class, () -> input.read(ByteBuffer.allocate(1)));
        assertEquals("Byte filter source channel made no progress", readFailure.getMessage());
        assertSame(readFailure, assertThrows(IOException.class, () -> input.read(ByteBuffer.allocate(1))));

        TransformingWritableByteChannel output = new TransformingWritableByteChannel(
                new ZeroProgressWritableChannel(),
                IDENTITY
        );
        ByteBuffer source = ByteBuffer.wrap(new byte[]{1});
        IOException writeFailure = assertThrows(IOException.class, () -> output.write(source));
        assertEquals("Byte filter target channel made no progress", writeFailure.getMessage());
        assertEquals(source.limit(), source.position());
        assertSame(
                writeFailure,
                assertThrows(IOException.class, () -> output.write(ByteBuffer.wrap(new byte[]{2})))
        );
        assertSame(writeFailure, assertThrows(IOException.class, output::finish));
    }

    /// Verifies invalid result counts from transforms are rejected in both channel directions.
    @Test
    public void rejectsInvalidTransformCounts() {
        for (ByteTransform transform : new ByteTransform[]{
                (buffer, offset, length) -> -1,
                (buffer, offset, length) -> length + 1
        }) {
            TransformingReadableByteChannel input = new TransformingReadableByteChannel(
                    Channels.newChannel(new ByteArrayInputStream(new byte[]{1})),
                    transform
            );
            IOException readFailure = assertThrows(
                    IOException.class,
                    () -> input.read(ByteBuffer.allocate(1))
            );
            assertEquals("Byte filter returned an invalid transformed byte count", readFailure.getMessage());
            assertSame(readFailure, assertThrows(IOException.class, () -> input.read(ByteBuffer.allocate(1))));

            TransformingWritableByteChannel output = new TransformingWritableByteChannel(
                    Channels.newChannel(new ByteArrayOutputStream()),
                    transform
            );
            IOException writeFailure = assertThrows(
                    IOException.class,
                    () -> output.write(ByteBuffer.wrap(new byte[]{1}))
            );
            assertEquals("Byte filter returned an invalid transformed byte count", writeFailure.getMessage());
        }
    }

    /// Verifies transforms that retain a complete bounded buffer fail promptly in both directions.
    @Test
    @Timeout(5)
    public void rejectsTransformsThatNeverCommit() {
        ByteTransform noProgress = (buffer, offset, length) -> 0;
        byte[] fullBuffer = new byte[8_192];
        TransformingReadableByteChannel input = new TransformingReadableByteChannel(
                Channels.newChannel(new ByteArrayInputStream(fullBuffer)),
                noProgress
        );
        IOException readFailure = assertThrows(IOException.class, () -> input.read(ByteBuffer.allocate(1)));
        assertEquals("Byte filter made no progress with a full buffer", readFailure.getMessage());

        TransformingWritableByteChannel output = new TransformingWritableByteChannel(
                Channels.newChannel(new ByteArrayOutputStream()),
                noProgress
        );
        IOException writeFailure = assertThrows(
                IOException.class,
                () -> output.write(ByteBuffer.wrap(fullBuffer))
        );
        assertEquals("Byte filter made no progress with a full buffer", writeFailure.getMessage());
    }

    /// Verifies close preserves a finish failure and suppresses a simultaneous owned-target close failure.
    @Test
    public void closeCombinesFinishAndEndpointFailures() throws IOException {
        FinishAndCloseFailingWritableChannel target = new FinishAndCloseFailingWritableChannel();
        TransformingWritableByteChannel output = new TransformingWritableByteChannel(
                target,
                (buffer, offset, length) -> 0,
                ResourceOwnership.OWNED
        );
        output.write(ByteBuffer.wrap(new byte[]{1}));

        IOException failure = assertThrows(IOException.class, output::close);
        assertSame(target.writeFailure(), failure);
        assertEquals(1, failure.getSuppressed().length);
        assertSame(target.closeFailure(), failure.getSuppressed()[0]);
        assertEquals(1, target.closeCount());
        assertFalse(output.isOpen());

        output.close();
        assertEquals(2, target.closeCount());
        assertFalse(target.isOpen());
    }

    /// A source channel that remains open while reporting no progress.
    @NotNullByDefault
    private static final class ZeroProgressReadableChannel implements ReadableByteChannel {
        /// Whether this channel remains open.
        private boolean open = true;

        /// Reports zero bytes without modifying the target.
        @Override
        public int read(ByteBuffer target) {
            return 0;
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

    /// A target channel that remains open while reporting no progress.
    @NotNullByDefault
    private static final class ZeroProgressWritableChannel implements WritableByteChannel {
        /// Whether this channel remains open.
        private boolean open = true;

        /// Reports zero bytes without consuming the source.
        @Override
        public int write(ByteBuffer source) {
            return 0;
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

    /// A source channel that returns at most one byte per read.
    @NotNullByDefault
    private static final class OneByteReadableChannel implements ReadableByteChannel {
        /// Remaining source bytes.
        private final ByteBuffer bytes;

        /// Whether this channel remains open.
        private boolean open = true;

        /// Creates a source over the supplied bytes.
        private OneByteReadableChannel(byte[] bytes) {
            this.bytes = ByteBuffer.wrap(bytes);
        }

        /// Returns one byte, zero for an empty target, or minus one at end of input.
        @Override
        public int read(ByteBuffer target) throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
            if (!target.hasRemaining()) {
                return 0;
            }
            if (!bytes.hasRemaining()) {
                return -1;
            }
            target.put(bytes.get());
            return 1;
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

    /// A target channel that consumes at most one byte per write.
    @NotNullByDefault
    private static final class OneByteWritableChannel implements WritableByteChannel {
        /// Collected bytes.
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        /// Whether this channel remains open.
        private boolean open = true;

        /// Consumes one byte, or zero when the source is empty.
        @Override
        public int write(ByteBuffer source) throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
            if (!source.hasRemaining()) {
                return 0;
            }
            bytes.write(Byte.toUnsignedInt(source.get()));
            return 1;
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

        /// Returns a copy of collected bytes.
        private byte[] bytes() {
            return bytes.toByteArray();
        }
    }

    /// Fails transform-tail output and the first close attempt, then closes successfully.
    @NotNullByDefault
    private static final class FinishAndCloseFailingWritableChannel implements WritableByteChannel {
        /// Failure thrown while writing the transform tail.
        private final IOException writeFailure = new IOException("tail write failed");

        /// Failure thrown by the first close attempt.
        private final IOException closeFailure = new IOException("target close failed");

        /// Number of close attempts.
        private int closeCount;

        /// Whether this channel remains open.
        private boolean open = true;

        /// Throws the stable tail-write failure.
        @Override
        public int write(ByteBuffer source) throws IOException {
            throw writeFailure;
        }

        /// Returns whether this channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Fails once and records successful closure on retry.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                throw closeFailure;
            }
            open = false;
        }

        /// Returns the tail-write failure.
        private IOException writeFailure() {
            return writeFailure;
        }

        /// Returns the first-close failure.
        private IOException closeFailure() {
            return closeFailure;
        }

        /// Returns the number of close attempts.
        private int closeCount() {
            return closeCount;
        }
    }
}
