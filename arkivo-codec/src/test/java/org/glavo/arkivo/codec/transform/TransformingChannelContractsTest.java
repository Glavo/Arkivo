// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.transform;

import org.glavo.arkivo.codec.ResourceOwnership;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.List;

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
    public void rejectsInvalidTransformCounts() throws IOException {
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

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            TransformingWritableByteChannel output = new TransformingWritableByteChannel(
                    Channels.newChannel(bytes), transform
            );
            IOException writeFailure = assertThrows(
                    IOException.class,
                    () -> output.write(ByteBuffer.wrap(new byte[]{1}))
            );
            assertEquals("Byte filter returned an invalid transformed byte count", writeFailure.getMessage());
            assertSame(writeFailure, assertThrows(IOException.class, output::finish));
            assertSame(writeFailure, assertThrows(IOException.class, output::close));
            output.close();
            assertEquals(0, bytes.size());
        }
    }

    /// Verifies transforms that retain a complete bounded buffer fail promptly in both directions.
    @Test
    @Timeout(5)
    public void rejectsTransformsThatNeverCommit() throws IOException {
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
        assertSame(writeFailure, assertThrows(IOException.class, output::finish));
        assertSame(writeFailure, assertThrows(IOException.class, output::close));
        output.close();
    }

    /// Verifies partial target failures are terminal and neither finish nor close replays accepted output.
    @Test
    public void partialTargetFailuresDoNotReplayOutput() throws IOException {
        for (Throwable failure : List.of(new IOException("partial target failure"),
                new IllegalStateException("partial target failure"), new AssertionError("partial target failure"))) {
            for (boolean finishing : new boolean[]{false, true}) {
                for (ResourceOwnership ownership : ResourceOwnership.values()) {
                    OneByteWritableChannel target = new OneByteWritableChannel();
                    target.writeFailure = failure;
                    TransformingWritableByteChannel output = new TransformingWritableByteChannel(
                            target, finishing ? (buffer, offset, length) -> 0 : IDENTITY, ownership
                    );
                    ByteBuffer source = ByteBuffer.wrap(new byte[]{9, 1, 2, 3, 8}).position(1).limit(4);
                    if (finishing) {
                        assertEquals(3, output.write(source));
                        assertSame(failure, assertThrows(failure.getClass(), output::finish));
                    } else {
                        assertSame(failure, assertThrows(failure.getClass(), () -> output.write(source)));
                    }
                    assertEquals(4, source.position());
                    assertEquals(4, source.limit());
                    ByteBuffer later = ByteBuffer.wrap(new byte[]{7});
                    assertSame(failure, assertThrows(failure.getClass(), () -> output.write(later)));
                    assertEquals(0, later.position());
                    assertSame(failure, assertThrows(failure.getClass(), output::finish));
                    assertSame(failure, assertThrows(failure.getClass(), output::close));
                    output.close();
                    assertArrayEquals(new byte[]{1}, target.bytes());
                    assertEquals(1, target.writeCount);
                    assertEquals(ownership == ResourceOwnership.BORROWED, target.isOpen());
                    assertFalse(output.isOpen());
                    target.close();
                }
            }
        }
    }

    /// Verifies a transform failure cannot cause the same mutable transform state to be invoked again.
    @Test
    public void retainsUncheckedTransformFailures() throws IOException {
        for (Throwable failure : List.of(new IllegalStateException("transform failed"), new AssertionError("transform failed"))) {
            int[] calls = {0};
            ByteTransform transform = (buffer, offset, length) -> {
                calls[0]++;
                buffer[offset] ^= 0x5a;
                if (failure instanceof RuntimeException exception) {
                    throw exception;
                }
                throw (Error) failure;
            };
            OneByteWritableChannel target = new OneByteWritableChannel();
            TransformingWritableByteChannel output = new TransformingWritableByteChannel(target, transform);
            assertSame(failure, assertThrows(failure.getClass(), () -> output.write(ByteBuffer.wrap(new byte[]{1}))));
            assertSame(failure, assertThrows(failure.getClass(), () -> output.write(ByteBuffer.wrap(new byte[]{2}))));
            assertSame(failure, assertThrows(failure.getClass(), output::close));
            output.close();
            assertEquals(1, calls[0]);
            assertEquals(0, target.bytes().length);
            target.close();
        }
    }

    /// Verifies unchecked input-transform failures cannot expose mutated bytes or invoke the transform again.
    @Test
    public void inputRetainsUncheckedTransformFailures() throws IOException {
        for (Throwable failure : List.of(new IllegalStateException("transform failed"), new AssertionError("transform failed"))) {
            for (ResourceOwnership ownership : ResourceOwnership.values()) {
                int[] calls = {0};
                OneByteReadableChannel source = new OneByteReadableChannel(new byte[]{1, 2});
                TransformingReadableByteChannel input = new TransformingReadableByteChannel(source, (buffer, offset, length) -> {
                    calls[0]++;
                    buffer[offset] ^= 0x5a;
                    if (failure instanceof RuntimeException exception) {
                        throw exception;
                    }
                    throw (Error) failure;
                }, ownership);
                ByteBuffer target = ByteBuffer.allocate(2);
                assertSame(failure, assertThrows(failure.getClass(), () -> input.read(target)));
                assertSame(failure, assertThrows(failure.getClass(), () -> input.read(target)));
                assertEquals(0, target.position());
                assertArrayEquals(new byte[2], target.array());
                assertEquals(0, input.read(ByteBuffer.allocate(0)));
                assertEquals(1, source.readCount);
                assertEquals(1, calls[0]);
                input.close();
                input.close();
                assertEquals(ownership == ResourceOwnership.BORROWED, source.isOpen());
                assertFalse(input.isOpen());
                source.close();
            }
        }
    }

    /// Verifies a failed physical read preserves the delivered prefix without publishing uncommitted source bytes.
    @Test
    public void partialSourceFailuresAreTerminal() throws IOException {
        for (Throwable failure : List.of(new IOException("partial source failure"),
                new IllegalStateException("partial source failure"), new AssertionError("partial source failure"))) {
            for (boolean direct : new boolean[]{false, true}) {
                OneByteReadableChannel source = new OneByteReadableChannel(new byte[]{1, 2, 3});
                source.readFailure = failure;
                source.failingRead = 2;
                try (TransformingReadableByteChannel input = new TransformingReadableByteChannel(
                        source, IDENTITY, ResourceOwnership.OWNED)) {
                    ByteBuffer target = direct ? ByteBuffer.allocateDirect(5) : ByteBuffer.allocate(5);
                    target.put(new byte[]{9, 8, 7, 6, 5}).position(1).limit(4).mark();
                    assertSame(failure, assertThrows(failure.getClass(), () -> input.read(target)));
                    assertEquals(2, target.position());
                    assertEquals(4, target.limit());
                    assertSame(failure, assertThrows(failure.getClass(), () -> input.read(target)));
                    assertEquals(2, target.position());
                    assertEquals(2, source.readCount);
                    assertEquals(1, target.reset().position());
                    byte[] actual = new byte[5];
                    target.clear().get(actual);
                    assertArrayEquals(new byte[]{9, 1, 7, 6, 5}, actual);
                }
                assertFalse(source.isOpen());
            }
        }
    }

    /// Verifies rejected read-only targets do not consume input or poison otherwise usable buffered data.
    @Test
    public void readOnlyTargetsDoNotPoisonInput() throws IOException {
        for (boolean direct : new boolean[]{false, true}) {
            try (TransformingReadableByteChannel input = new TransformingReadableByteChannel(
                    Channels.newChannel(new ByteArrayInputStream(new byte[]{1, 2, 3})), IDENTITY, ResourceOwnership.OWNED)) {
                ByteBuffer storage = direct ? ByteBuffer.allocateDirect(3) : ByteBuffer.allocate(3);
                ByteBuffer rejected = storage.asReadOnlyBuffer().position(1).limit(2).mark();
                assertThrows(ReadOnlyBufferException.class, () -> input.read(rejected));
                ByteBuffer accepted = ByteBuffer.allocate(1);
                assertEquals(1, input.read(accepted));
                assertEquals(1, accepted.get(0));
                assertThrows(ReadOnlyBufferException.class, () -> input.read(rejected));
                assertEquals(1, rejected.position());
                assertEquals(2, rejected.limit());
                assertEquals(1, rejected.reset().position());
                assertEquals(0, input.read(rejected.position(2)));
                accepted.clear();
                assertEquals(1, input.read(accepted));
                assertEquals(2, accepted.get(0));
                accepted.clear();
                assertEquals(1, input.read(accepted));
                assertEquals(3, accepted.get(0));
                assertEquals(-1, input.read(accepted.clear()));
            }
        }
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

        /// Failure thrown once after transferring a byte on the selected read.
        private @Nullable Throwable readFailure;

        /// One-based physical read on which to inject the failure.
        private int failingRead = 1;

        /// Number of physical read attempts.
        private int readCount;

        /// Whether this channel remains open.
        private boolean open = true;

        /// Creates a source over the supplied bytes.
        private OneByteReadableChannel(byte[] bytes) {
            this.bytes = ByteBuffer.wrap(bytes);
        }

        /// Returns one byte, zero for an empty target, or minus one at end of input.
        @Override
        public int read(ByteBuffer target) throws IOException {
            readCount++;
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
            @Nullable Throwable failure = readFailure;
            if (failure != null && readCount == failingRead) {
                readFailure = null;
                if (failure instanceof IOException exception) {
                    throw exception;
                }
                if (failure instanceof RuntimeException exception) {
                    throw exception;
                }
                throw (Error) failure;
            }
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

        /// Failure thrown once after accepting one byte, or `null` when disabled.
        private @Nullable Throwable writeFailure;

        /// Number of physical write attempts.
        private int writeCount;

        /// Whether this channel remains open.
        private boolean open = true;

        /// Consumes one byte, or zero when the source is empty.
        @Override
        public int write(ByteBuffer source) throws IOException {
            writeCount++;
            if (!open) {
                throw new ClosedChannelException();
            }
            if (!source.hasRemaining()) {
                return 0;
            }
            bytes.write(Byte.toUnsignedInt(source.get()));
            @Nullable Throwable failure = writeFailure;
            writeFailure = null;
            if (failure instanceof IOException exception) {
                throw exception;
            }
            if (failure instanceof RuntimeException exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
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
