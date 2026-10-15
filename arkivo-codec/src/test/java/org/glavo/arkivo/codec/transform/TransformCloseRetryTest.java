// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.transform;

import org.glavo.arkivo.codec.ResourceOwnership;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies byte-transform wrappers can retry failed endpoint closure.
@NotNullByDefault
final class TransformCloseRetryTest {
    /// The transform that immediately accepts every byte without modification.
    private static final ByteTransform IDENTITY = (buffer, offset, length) -> length;

    /// Flush failures remain primary when finishing and downstream cleanup report the same retained failure.
    @Test
    void preservesFlushFailureDuringAutomaticClose() throws IOException {
        for (Throwable failure : new Throwable[]{new IOException("flush"),
                new IllegalStateException("flush"), new AssertionError("flush")}) {
            var target = new FlushFailingOutput(failure);
            var output = new TransformingOutputStream(target, (bytes, offset, length) -> 0);
            output.write(new byte[]{1, 2, 3});
            assertSame(failure, assertThrows(failure.getClass(), () -> {
                try (output) {
                    output.flush();
                }
            }));
            assertEquals(1, failure.getSuppressed().length);
            assertSame(failure, failure.getSuppressed()[0].getCause());
            assertArrayEquals(new byte[]{1, 2, 3}, target.bytes.toByteArray());
            assertSame(failure, assertThrows(IOException.class, output::close).getCause());
            output.close();
            output.close();
            assertEquals(3, target.closeCalls);
        }
    }

    /// A failed flush does not disable writes, and recovery does not forget already delivered failures.
    @Test
    void retainsFlushFailureIdentityAfterRecovery() throws IOException {
        var failure = new IOException("first flush");
        var target = new FlushFailingOutput(failure);
        var output = new TransformingOutputStream(target, IDENTITY);
        assertSame(failure, assertThrows(IOException.class, () -> {
            try (output) {
                assertSame(failure, assertThrows(IOException.class, output::flush));
                target.flushFailure = new IOException("second flush");
                assertSame(target.flushFailure, assertThrows(IOException.class, output::flush));
                target.flushFailure = null;
                output.flush();
                output.write(new byte[]{4, 5});
                throw failure;
            }
        }));
        assertSame(failure, failure.getSuppressed()[0].getCause());
        assertArrayEquals(new byte[]{4, 5}, target.bytes.toByteArray());
        assertThrows(IOException.class, output::close);
        output.close();
        assertEquals(3, target.closeCalls);
    }

    /// Accepts writes while flush and the first two closes can report a retained failure.
    @NotNullByDefault
    private static final class FlushFailingOutput extends OutputStream {
        /// Collected committed bytes.
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        /// Original downstream cleanup failure.
        private final Throwable closeFailure;
        /// Flush failure, or null after recovery.
        private @Nullable Throwable flushFailure;
        /// Physical cleanup attempts.
        private int closeCalls;

        /// Shares one initial failure between flush and cleanup.
        private FlushFailingOutput(Throwable failure) {
            closeFailure = failure;
            flushFailure = failure;
        }

        @Override
        public void write(int value) { bytes.write(value); }

        @Override
        public void flush() throws IOException {
            if (flushFailure != null) fail(flushFailure);
        }

        @Override
        public void close() throws IOException {
            if (++closeCalls <= 2) fail(closeFailure);
        }

        /// Preserves the original checked or unchecked failure category.
        private static void fail(Throwable failure) throws IOException {
            if (failure instanceof IOException exception) throw exception;
            if (failure instanceof RuntimeException exception) throw exception;
            throw (Error) failure;
        }
    }

    /// Preserves a read failure when owned-source closure reports that same exception instance.
    @Test
    void sharedReadAndCloseFailurePreservesTryWithResourcesException() throws IOException {
        verifyReadableCloseFailures(true);
    }

    /// Preserves an explicit close failure when automatic cleanup repeats it.
    @Test
    void repeatedReadableCloseFailurePreservesTryWithResourcesException() throws IOException {
        verifyReadableCloseFailures(false);
    }

    /// Retrying cleanup with an intervening different failure cannot make an older exception unsafe to suppress.
    @Test
    void alternatingCloseFailuresPreserveTryWithResourcesException() throws Exception {
        for (boolean stream : new boolean[]{false, true}) {
            IOException readFailure = new IOException("first source close failed");
            SharedFailureInput source = new SharedFailureInput(readFailure);
            source.failedCloseAttempts = 3;
            source.alternateCloseFailure = new IOException("second source close failed");
            verifyAlternatingCloseFailures(stream ? new TransformingInputStream(source, IDENTITY)
                            : new TransformingReadableByteChannel(source, IDENTITY, ResourceOwnership.OWNED),
                    readFailure, source.alternateCloseFailure);
            assertEquals(4, source.closeCount);

            FailingCloseWritableChannel target = new FailingCloseWritableChannel();
            target.failedCloseAttempts = 3;
            target.alternateCloseFailure = new IOException("second target close failed");
            verifyAlternatingCloseFailures(stream
                            ? new TransformingOutputStream(Channels.newOutputStream(target), IDENTITY)
                            : new TransformingWritableByteChannel(target, IDENTITY, ResourceOwnership.OWNED),
                    target.failure, target.alternateCloseFailure);
            assertEquals(4, target.closeCount);
        }
    }

    /// Retries once inside the scope and rethrows its first close failure before automatic cleanup.
    private static void verifyAlternatingCloseFailures(
            AutoCloseable wrapper, IOException firstFailure, IOException secondFailure
    ) throws Exception {
        assertSame(firstFailure, assertThrows(IOException.class, () -> {
            try (wrapper) {
                try {
                    wrapper.close();
                } catch (IOException first) {
                    IOException retry = assertThrows(IOException.class, wrapper::close);
                    assertSame(secondFailure, retry.getCause());
                    throw first;
                }
            }
        }));
        assertEquals(1, firstFailure.getSuppressed().length);
        assertSame(firstFailure, firstFailure.getSuppressed()[0].getCause());
        wrapper.close();
        wrapper.close();
    }

    /// Exercises checked and unchecked failures through both readable adapters, followed by successful cleanup.
    private static void verifyReadableCloseFailures(boolean readFirst) throws IOException {
        for (boolean channel : new boolean[]{false, true}) {
            for (Throwable failure : new Throwable[]{
                    new IOException("source failed"),
                    new IllegalStateException("source failed"),
                    new AssertionError("source failed")
            }) {
                SharedFailureInput source = new SharedFailureInput(failure);
                if (channel) {
                    var input = new TransformingReadableByteChannel(source, IDENTITY, ResourceOwnership.OWNED);
                    assertSame(failure, assertThrows(failure.getClass(), () -> {
                        try (input) {
                            if (readFirst) {
                                input.read(ByteBuffer.allocate(1));
                            } else {
                                input.close();
                            }
                        }
                    }));
                    assertFalse(input.isOpen());
                    if (readFirst) {
                        assertThrows(IOException.class, input::close);
                    }
                    input.close();
                    input.close();
                } else {
                    var input = new TransformingInputStream(source, IDENTITY);
                    assertSame(failure, assertThrows(failure.getClass(), () -> {
                        try (input) {
                            if (readFirst) {
                                input.read();
                            } else {
                                input.close();
                            }
                        }
                    }));
                    assertThrows(IOException.class, input::read);
                    if (readFirst) {
                        assertThrows(IOException.class, input::close);
                    }
                    input.close();
                    input.close();
                }
                assertEquals(1, failure.getSuppressed().length);
                assertSame(failure, failure.getSuppressed()[0].getCause());
                assertEquals(readFirst ? 1 : 0, source.readCount);
                assertEquals(3, source.closeCount);
                assertFalse(source.isOpen());
            }
        }
    }

    /// Reports one failure from reads and the first two close attempts, then allows cleanup to complete.
    @NotNullByDefault
    private static final class SharedFailureInput extends InputStream implements ReadableByteChannel {
        /// The checked or unchecked failure shared by reads and unsuccessful closes.
        private final Throwable failure;

        /// A different failure optionally reported by the second close attempt.
        private @Nullable IOException alternateCloseFailure;

        /// Number of initial close attempts that must fail.
        private int failedCloseAttempts = 2;

        /// The number of source reads attempted.
        private int readCount;

        /// The number of source closes attempted.
        private int closeCount;

        /// Whether source cleanup remains incomplete.
        private boolean open = true;

        /// Creates a source that reports the supplied failure.
        private SharedFailureInput(Throwable failure) {
            this.failure = failure;
        }

        /// Reports the source failure without delivering a byte.
        @Override
        public int read() throws IOException {
            readCount++;
            throw sourceFailure();
        }

        /// Reports the source failure without advancing the destination.
        @Override
        public int read(ByteBuffer target) throws IOException {
            return read();
        }

        /// Returns whether cleanup remains incomplete.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Fails the configured initial attempts, then completes source cleanup.
        @Override
        public void close() throws IOException {
            if (++closeCount <= failedCloseAttempts) {
                if (closeCount == 2 && alternateCloseFailure != null) {
                    throw alternateCloseFailure;
                }
                throw sourceFailure();
            }
            open = false;
        }

        /// Returns the checked failure or throws the original unchecked failure.
        private IOException sourceFailure() {
            if (failure instanceof RuntimeException exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            return (IOException) failure;
        }
    }

    /// An explicit close followed by automatic cleanup cannot suppress a repeated endpoint failure onto itself.
    @Test
    void repeatedCloseFailurePreservesTryWithResourcesException() throws IOException {
        FailingCloseWritableChannel channel = new FailingCloseWritableChannel();
        channel.failedCloseAttempts = 2;
        IOException channelFailure = assertThrows(IOException.class, () -> {
            try (var output = new TransformingWritableByteChannel(channel, IDENTITY, ResourceOwnership.OWNED)) {
                output.close();
            }
        });
        assertSame(channel.failure, channelFailure);
        assertEquals(1, channelFailure.getSuppressed().length);
        assertSame(channelFailure, channelFailure.getSuppressed()[0].getCause());
        assertEquals(2, channel.closeCount());
        channel.close();

        IOException streamFailure = new IOException("close failed");
        SharedFailureOutputStream stream = new SharedFailureOutputStream(streamFailure);
        assertSame(streamFailure, assertThrows(IOException.class, () -> {
            try (var output = new TransformingOutputStream(stream, IDENTITY)) {
                output.close();
            }
        }));
        assertEquals(1, streamFailure.getSuppressed().length);
        assertSame(streamFailure, streamFailure.getSuppressed()[0].getCause());
        assertEquals(2, stream.closeCount());
    }

    /// Verifies readable and writable channel wrappers retry owned endpoint closure.
    @Test
    void retriesChannelEndpoints() throws IOException {
        FailingCloseWritableChannel target = new FailingCloseWritableChannel();
        TransformingWritableByteChannel output = new TransformingWritableByteChannel(
                target,
                IDENTITY,
                ResourceOwnership.OWNED
        );
        output.write(ByteBuffer.wrap(new byte[]{1, 2, 3}));
        assertThrows(IOException.class, output::close);
        assertFalse(output.isOpen());
        assertEquals(1, target.closeCount());
        output.close();
        output.close();
        assertEquals(2, target.closeCount());
        assertArrayEquals(new byte[]{1, 2, 3}, target.bytes());

        FailingCloseReadableChannel source = new FailingCloseReadableChannel(new byte[]{4, 5, 6});
        TransformingReadableByteChannel input = new TransformingReadableByteChannel(
                source,
                IDENTITY,
                ResourceOwnership.OWNED
        );
        assertThrows(IOException.class, input::close);
        assertFalse(input.isOpen());
        assertEquals(1, source.closeCount());
        input.close();
        input.close();
        assertEquals(2, source.closeCount());
    }

    /// Verifies stream wrappers retry endpoints without repeating transform finalization.
    @Test
    void retriesStreamEndpoints() throws IOException {
        FailingCloseOutputStream target = new FailingCloseOutputStream();
        TransformingOutputStream output = new TransformingOutputStream(target, IDENTITY);
        output.write(new byte[]{1, 2, 3});
        assertThrows(IOException.class, output::close);
        assertEquals(1, target.closeCount());
        output.close();
        output.close();
        assertEquals(2, target.closeCount());
        assertArrayEquals(new byte[]{1, 2, 3}, target.bytes());

        FailingCloseInputStream source = new FailingCloseInputStream(new byte[]{4, 5, 6});
        TransformingInputStream input = new TransformingInputStream(source, IDENTITY);
        assertThrows(IOException.class, input::close);
        assertEquals(1, source.closeCount());
        input.close();
        input.close();
        assertEquals(2, source.closeCount());
    }

    /// Verifies one failure repeated by final output and close remains the reported stream failure.
    @Test
    void preservesSharedFinishAndCloseFailure() throws IOException {
        IOException failure = new IOException("shared failure");
        SharedFailureOutputStream target = new SharedFailureOutputStream(failure);
        TransformingOutputStream output = new TransformingOutputStream(target, (buffer, offset, length) -> 0);
        output.write(1);

        IOException exception = assertThrows(IOException.class, output::close);

        assertSame(failure, exception);
        assertEquals(0, exception.getSuppressed().length);
        assertEquals(1, target.writeCount());
        assertEquals(1, target.closeCount());
    }

    /// Implements an output stream that reports one shared failure from writes and closure.
    @NotNullByDefault
    private static final class SharedFailureOutputStream extends OutputStream {
        /// The shared failure reported by every operation.
        private final IOException failure;

        /// Number of bulk write attempts.
        private int writeCount;

        /// Number of close attempts.
        private int closeCount;

        /// Creates a stream that reports the supplied failure.
        private SharedFailureOutputStream(IOException failure) {
            this.failure = failure;
        }

        /// Reports the shared failure for a single-byte write.
        @Override
        public void write(int value) throws IOException {
            throw failure;
        }

        /// Accepts an empty write or records a nonempty bulk write attempt and reports the shared failure.
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return;
            }
            writeCount++;
            throw failure;
        }

        /// Records a close attempt and reports the shared failure.
        @Override
        public void close() throws IOException {
            closeCount++;
            throw failure;
        }

        /// Returns the number of nonempty bulk write attempts.
        private int writeCount() {
            return writeCount;
        }

        /// Returns the number of close attempts.
        private int closeCount() {
            return closeCount;
        }
    }

    /// Implements a writable channel that fails a configurable number of close attempts.
    @NotNullByDefault
    private static final class FailingCloseWritableChannel implements WritableByteChannel {
        /// Number of initial close attempts that must fail.
        private int failedCloseAttempts = 1;

        /// The stable failure reported by each unsuccessful close.
        private final IOException failure = new IOException("close failed");

        /// A different failure optionally reported by the second close attempt.
        private @Nullable IOException alternateCloseFailure;
        /// Collected bytes.
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        /// Writable delegate.
        private final WritableByteChannel delegate = Channels.newChannel(bytes);

        /// Number of close attempts.
        private int closeCount;

        /// Writes bytes to the delegate.
        @Override
        public int write(ByteBuffer source) throws IOException {
            return delegate.write(source);
        }

        /// Returns whether the delegate remains open.
        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        /// Fails the configured initial attempts and then closes the delegate.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount <= failedCloseAttempts) {
                if (closeCount == 2 && alternateCloseFailure != null) {
                    throw alternateCloseFailure;
                }
                throw failure;
            }
            delegate.close();
        }

        /// Returns the close-attempt count.
        private int closeCount() {
            return closeCount;
        }

        /// Returns collected bytes.
        private byte[] bytes() {
            return bytes.toByteArray();
        }
    }

    /// Implements a readable channel that fails its first close attempt.
    @NotNullByDefault
    private static final class FailingCloseReadableChannel implements ReadableByteChannel {
        /// Readable delegate.
        private final ReadableByteChannel delegate;

        /// Number of close attempts.
        private int closeCount;

        /// Creates a channel over bytes.
        private FailingCloseReadableChannel(byte[] bytes) {
            delegate = Channels.newChannel(new ByteArrayInputStream(bytes));
        }

        /// Reads bytes from the delegate.
        @Override
        public int read(ByteBuffer target) throws IOException {
            return delegate.read(target);
        }

        /// Returns whether the delegate remains open.
        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        /// Fails once and closes the delegate on retry.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                throw new IOException("close failed");
            }
            delegate.close();
        }

        /// Returns the close-attempt count.
        private int closeCount() {
            return closeCount;
        }
    }

    /// Implements an output stream that fails its first close attempt.
    @NotNullByDefault
    private static final class FailingCloseOutputStream extends OutputStream {
        /// Collected bytes.
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();

        /// Number of close attempts.
        private int closeCount;

        /// Writes one byte.
        @Override
        public void write(int value) {
            delegate.write(value);
        }

        /// Writes a byte range.
        @Override
        public void write(byte[] bytes, int offset, int length) {
            delegate.write(bytes, offset, length);
        }

        /// Fails once and closes the delegate on retry.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                throw new IOException("close failed");
            }
            delegate.close();
        }

        /// Returns the close-attempt count.
        private int closeCount() {
            return closeCount;
        }

        /// Returns collected bytes.
        private byte[] bytes() {
            return delegate.toByteArray();
        }
    }

    /// Implements an input stream that fails its first close attempt.
    @NotNullByDefault
    private static final class FailingCloseInputStream extends InputStream {
        /// Readable delegate.
        private final ByteArrayInputStream delegate;

        /// Number of close attempts.
        private int closeCount;

        /// Creates a stream over bytes.
        private FailingCloseInputStream(byte[] bytes) {
            delegate = new ByteArrayInputStream(bytes);
        }

        /// Reads one byte.
        @Override
        public int read() {
            return delegate.read();
        }

        /// Reads a byte range.
        @Override
        public int read(byte[] bytes, int offset, int length) {
            return delegate.read(bytes, offset, length);
        }

        /// Fails once and closes the delegate on retry.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                throw new IOException("close failed");
            }
            delegate.close();
        }

        /// Returns the close-attempt count.
        private int closeCount() {
            return closeCount;
        }
    }
}
