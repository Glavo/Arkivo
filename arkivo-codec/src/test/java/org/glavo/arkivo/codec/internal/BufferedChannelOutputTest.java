// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies buffered encoded output across staging boundaries and recoverable target failures.
@NotNullByDefault
final class BufferedChannelOutputTest {
    /// Verifies every write form preserves byte order across automatic and explicit flushes.
    @Test
    void stagesEveryWriteFormInOrder() throws IOException {
        FragmentingWritableByteChannel target = new FragmentingWritableByteChannel(3);
        BufferedChannelOutput output = new BufferedChannelOutput(target);
        byte[] bulk = sequence(9_000, 41);
        byte[] guardedRange = {(byte) 0xaa, 2, 3, (byte) 0xbb};
        ByteBuffer source = ByteBuffer.allocateDirect(4);
        source.put(new byte[]{(byte) 0xcc, 4, 5, (byte) 0xdd}).flip();
        source.position(1);
        source.limit(3);

        output.write(0x101);
        output.write(guardedRange, 1, 2);
        assertEquals(2, output.write(source));
        assertEquals(source.limit(), source.position());
        guardedRange[1] = 99;
        source.put(1, (byte) 99);
        output.write(bulk);
        output.flush();

        byte[] expected = new byte[5 + bulk.length];
        expected[0] = 1;
        expected[1] = 2;
        expected[2] = 3;
        expected[3] = 4;
        expected[4] = 5;
        System.arraycopy(bulk, 0, expected, 5, bulk.length);
        assertArrayEquals(expected, target.bytes());
        assertTrue(target.isOpen());

        int writes = target.writeCount();
        output.flush();
        assertEquals(writes, target.writeCount());
    }

    /// Verifies unwritten bytes survive a zero-progress flush and can be retried without duplication.
    @Test
    void retriesAfterPartialZeroProgressFlush() throws IOException {
        FragmentingWritableByteChannel target = new FragmentingWritableByteChannel(2);
        target.returnZeroOnWrite(2);
        BufferedChannelOutput output = new BufferedChannelOutput(target);
        output.write(new byte[]{1, 2, 3, 4, 5});

        IOException failure = assertThrows(IOException.class, output::flush);
        assertTrue(failure.getMessage().contains("made no progress"));
        assertArrayEquals(new byte[]{1, 2}, target.bytes());

        output.flush();
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, target.bytes());
    }

    /// Verifies unwritten bytes survive an exception after partial target progress.
    @Test
    void retriesAfterPartialExceptionalFlush() throws IOException {
        for (int acceptedBeforeFailure : new int[]{0, 1, 2}) {
            for (boolean discard : new boolean[]{false, true}) {
                for (Throwable failure : List.of(new IOException("write failed"),
                        new IllegalStateException("write failed"), new AssertionError("write failed"))) {
                    FragmentingWritableByteChannel target = new FragmentingWritableByteChannel(2);
                    target.throwOnWrite(2, acceptedBeforeFailure, failure);
                    BufferedChannelOutput output = new BufferedChannelOutput(target);
                    byte[] bytes = {6, 7, 8, 9};
                    output.write(bytes);

                    assertSame(failure, assertThrows(failure.getClass(), output::flush));
                    assertArrayEquals(Arrays.copyOf(bytes, 2 + acceptedBeforeFailure), target.bytes());
                    if (discard) {
                        output.clear();
                    }
                    output.write(10);
                    output.flush();
                    byte[] expected = Arrays.copyOf(bytes, (discard ? 2 + acceptedBeforeFailure : 4) + 1);
                    expected[expected.length - 1] = 10;
                    assertArrayEquals(expected, target.bytes());
                    int writes = target.writeCount();
                    output.flush();
                    assertEquals(writes, target.writeCount());
                    assertTrue(target.isOpen());
                }
            }
        }
    }

    /// Verifies a failed automatic flush preserves the source position for resuming a buffer write exactly once.
    @Test
    void resumesBufferWritesAfterAutomaticFlushFailure() throws IOException {
        for (boolean direct : new boolean[]{false, true}) {
            for (boolean readOnly : new boolean[]{false, true}) {
                for (int acceptedBeforeFailure : new int[]{0, 1, 8_192}) {
                    for (Throwable failure : List.of(new IOException("automatic flush failed"),
                            new IllegalStateException("automatic flush failed"), new AssertionError("automatic flush failed"))) {
                        FragmentingWritableByteChannel target = new FragmentingWritableByteChannel(8_192);
                        target.throwOnWrite(1, acceptedBeforeFailure, failure);
                        BufferedChannelOutput output = new BufferedChannelOutput(target);
                        byte[] payload = sequence(8_195, 43);
                        ByteBuffer storage = direct ? ByteBuffer.allocateDirect(payload.length + 2)
                                : ByteBuffer.allocate(payload.length + 2);
                        storage.position(1).put(payload).position(1).limit(payload.length + 1);
                        ByteBuffer source = (readOnly ? storage.asReadOnlyBuffer() : storage.duplicate()).mark();
                        output.write(new byte[]{11, 12, 13});

                        assertSame(failure, assertThrows(failure.getClass(), () -> output.write(source)));
                        assertEquals(1 + 8_192 - 3, source.position());
                        assertEquals(payload.length + 1, source.limit());
                        assertEquals(1, storage.position());
                        int remaining = source.remaining();
                        assertEquals(remaining, output.write(source));
                        assertEquals(source.limit(), source.position());
                        assertEquals(1, source.reset().position());
                        output.flush();

                        byte[] expected = new byte[payload.length + 3];
                        expected[0] = 11;
                        expected[1] = 12;
                        expected[2] = 13;
                        System.arraycopy(payload, 0, expected, 3, payload.length);
                        assertArrayEquals(expected, target.bytes());
                        assertTrue(target.isOpen());
                    }
                }
            }
        }
    }

    /// Verifies clearing after a partial failure discards only bytes still staged locally.
    @Test
    void clearsUnwrittenSuffixAfterPartialFailure() throws IOException {
        FragmentingWritableByteChannel target = new FragmentingWritableByteChannel(2);
        target.returnZeroOnWrite(2);
        BufferedChannelOutput output = new BufferedChannelOutput(target);
        output.write(new byte[]{10, 11, 12, 13});

        assertThrows(IOException.class, output::flush);
        output.clear();
        output.write(14);
        output.flush();

        assertArrayEquals(new byte[]{10, 11, 14}, target.bytes());
    }

    /// Verifies invalid array ranges and null inputs are rejected before staged bytes are disturbed.
    @Test
    void validatesInputsWithoutDisturbingStagedBytes() throws IOException {
        assertThrows(NullPointerException.class, () -> new BufferedChannelOutput(null));

        FragmentingWritableByteChannel target = new FragmentingWritableByteChannel(8);
        BufferedChannelOutput output = new BufferedChannelOutput(target);
        output.write(new byte[]{21, 22});

        assertThrows(NullPointerException.class, () -> output.write((byte[]) null));
        assertThrows(NullPointerException.class, () -> output.write((ByteBuffer) null));
        assertThrows(IndexOutOfBoundsException.class, () -> output.write(new byte[2], 1, 2));
        output.write(new byte[2], 2, 0);
        output.flush();

        assertArrayEquals(new byte[]{21, 22}, target.bytes());
    }

    /// Verifies empty or rejected writes do not trigger I/O even when the staging buffer is completely full.
    @Test
    void emptyAndRejectedWritesDoNotFlushFullBuffer() throws IOException {
        FragmentingWritableByteChannel target = new FragmentingWritableByteChannel(1_024);
        BufferedChannelOutput output = new BufferedChannelOutput(target);
        byte[] expected = sequence(8_192, 29);
        output.write(expected);
        output.write(new byte[0]);
        output.write(expected, expected.length, 0);
        assertEquals(0, output.write(ByteBuffer.allocate(0).asReadOnlyBuffer()));
        assertThrows(IndexOutOfBoundsException.class, () -> output.write(expected, expected.length, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> output.write(expected, 1, Integer.MAX_VALUE));
        assertThrows(NullPointerException.class, () -> output.write((ByteBuffer) null));
        assertEquals(0, target.writeCount());
        output.flush();
        assertArrayEquals(expected, target.bytes());
    }

    /// Returns deterministic bytes of the requested length.
    private static byte @Unmodifiable [] sequence(int length, int seed) {
        byte[] bytes = new byte[length];
        for (int index = 0; index < length; index++) {
            bytes[index] = (byte) (seed + index * 17);
        }
        return bytes;
    }

    /// Collects writes while optionally fragmenting or failing a selected call.
    @NotNullByDefault
    private static final class FragmentingWritableByteChannel implements WritableByteChannel {
        /// Collected bytes accepted by successful writes or before an injected failure.
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        /// Maximum bytes accepted by one positive write.
        private final int maximumWriteSize;

        /// One-based write call that returns zero, or zero when disabled.
        private int zeroWrite;

        /// One-based write call that throws, or zero when disabled.
        private int failingWrite;

        /// Number of source bytes accepted inside the failing write before its exception is thrown.
        private int bytesBeforeFailure;

        /// Exception thrown on the selected write call.
        private Throwable failure = new IOException("write failed");

        /// Number of attempted writes.
        private int writeCount;

        /// Whether this target remains open.
        private boolean open = true;

        /// Creates a collecting target with the requested fragment size.
        private FragmentingWritableByteChannel(int maximumWriteSize) {
            this.maximumWriteSize = maximumWriteSize;
        }

        /// Configures one write call to return zero.
        private void returnZeroOnWrite(int write) {
            zeroWrite = write;
        }

        /// Configures a write to consume a prefix before throwing the supplied exception.
        private void throwOnWrite(int write, int bytesBeforeFailure, Throwable failure) {
            failingWrite = write;
            this.bytesBeforeFailure = bytesBeforeFailure;
            this.failure = failure;
        }

        /// Accepts one fragment or performs the configured failure.
        @Override
        public int write(ByteBuffer source) throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
            writeCount++;
            if (writeCount == zeroWrite) {
                return 0;
            }
            if (writeCount == failingWrite) {
                byte[] prefix = new byte[Math.min(bytesBeforeFailure, source.remaining())];
                source.get(prefix);
                bytes.writeBytes(prefix);
                if (failure instanceof IOException exception) {
                    throw exception;
                }
                if (failure instanceof RuntimeException exception) {
                    throw exception;
                }
                throw (Error) failure;
            }
            int length = Math.min(source.remaining(), maximumWriteSize);
            byte[] chunk = new byte[length];
            source.get(chunk);
            bytes.writeBytes(chunk);
            return length;
        }

        /// Returns whether this target remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this target.
        @Override
        public void close() {
            open = false;
        }

        /// Returns a copy of all accepted bytes.
        private byte @Unmodifiable [] bytes() {
            return bytes.toByteArray();
        }

        /// Returns the number of attempted writes.
        private int writeCount() {
            return writeCount;
        }
    }
}
