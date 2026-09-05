// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies sequential RAR volume transitions, continuation signatures, and cleanup failures.
@NotNullByDefault
final class RarVolumeInputStreamTest {
    /// The RAR4 signature stripped from every continuation volume.
    private static final byte @Unmodifiable [] RAR4_SIGNATURE =
            new byte[]{'R', 'a', 'r', '!', 0x1a, 0x07, 0x00};

    /// The RAR5 signature stripped from every continuation volume.
    private static final byte @Unmodifiable [] RAR5_SIGNATURE =
            new byte[]{'R', 'a', 'r', '!', 0x1a, 0x07, 0x01, 0x00};

    /// Verifies automatic transitions concatenate payloads while stripping both supported continuation signatures.
    @Test
    void concatenatesVolumesAndStripsContinuationSignatures() throws IOException {
        TestSeekableByteChannel first = channel(new byte[]{1, 2});
        TestSeekableByteChannel second = channel(concatenate(RAR4_SIGNATURE, new byte[]{3, 4}));
        TestSeekableByteChannel third = channel(concatenate(RAR5_SIGNATURE, new byte[]{5, 6}));
        TestVolumeSource volumes = new TestVolumeSource(List.of(first, second, third), false);

        try (RarVolumeInputStream input = new RarVolumeInputStream(volumes)) {
            assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6}, input.readAllBytes());
        }

        assertEquals(4, volumes.openCount());
        assertFalse(first.isOpen());
        assertFalse(second.isOpen());
        assertFalse(third.isOpen());
        assertTrue(volumes.isOpen());
        volumes.close();
    }

    /// Verifies explicit header-boundary and end-header transitions select exactly the next volume.
    @Test
    void advancesVolumesAtExplicitParserBoundaries() throws IOException {
        TestVolumeSource boundaryVolumes = new TestVolumeSource(List.of(
                channel(new byte[]{10, 11}),
                channel(concatenate(RAR5_SIGNATURE, new byte[]{12}))
        ), false);
        try (RarVolumeInputStream input = new RarVolumeInputStream(boundaryVolumes)) {
            assertFalse(input.advanceAtHeaderBoundary());
            assertEquals(10, input.read());
            assertFalse(input.advanceAtHeaderBoundary());
            assertEquals(11, input.read());
            assertTrue(input.advanceAtHeaderBoundary());
            assertEquals(12, input.read());
            assertFalse(input.advanceAtHeaderBoundary());
            assertEquals(-1, input.read());
        }

        TestVolumeSource endHeaderVolumes = new TestVolumeSource(List.of(
                channel(new byte[]{20, 99, 98}),
                channel(concatenate(RAR4_SIGNATURE, new byte[]{21}))
        ), false);
        try (RarVolumeInputStream input = new RarVolumeInputStream(endHeaderVolumes)) {
            assertFalse(input.advanceAfterEndHeader());
            assertEquals(20, input.read());
            assertTrue(input.advanceAfterEndHeader());
            assertEquals(21, input.read());
        }
    }

    /// Verifies retrying a failed volume open neither skips its payload nor treats a continuation signature as data.
    @ParameterizedTest
    @ValueSource(strings = {"read", "header-boundary", "end-header"})
    void retriesTheSameVolumeAfterOpenFailure(String transition) throws IOException {
        for (int failingIndex = 0; failingIndex < 3; failingIndex++) {
            if (failingIndex == 0 && !transition.equals("read")) {
                continue;
            }
            for (Throwable failure : new Throwable[]{
                    new IOException("volume open failed"),
                    new IllegalStateException("volume open failed"),
                    new AssertionError("volume open failed")
            }) {
                TestSeekableByteChannel first = channel(new byte[]{40});
                TestSeekableByteChannel second = channel(concatenate(RAR4_SIGNATURE, new byte[]{41}));
                TestSeekableByteChannel third = channel(concatenate(RAR5_SIGNATURE, new byte[]{42}));
                TestVolumeSource volumes = new TestVolumeSource(List.of(first, second, third), false);
                volumes.failOpening(failingIndex, failure);
                try (RarVolumeInputStream input = new RarVolumeInputStream(volumes, true)) {
                    assertArrayEquals(Arrays.copyOf(new byte[]{40, 41, 42}, failingIndex), input.readNBytes(failingIndex));
                    Throwable observed = assertThrows(failure.getClass(), () -> {
                        switch (transition) {
                            case "read" -> input.read();
                            case "header-boundary" -> input.advanceAtHeaderBoundary();
                            case "end-header" -> input.advanceAfterEndHeader();
                            default -> throw new AssertionError("Unknown volume transition: " + transition);
                        }
                    });
                    assertSame(failure, observed);
                    assertEquals(failingIndex + 1, volumes.openCount());
                    assertEquals(0, volumes.channels.get(failingIndex).position());
                    assertTrue(volumes.channels.get(failingIndex).isOpen());

                    assertArrayEquals(Arrays.copyOfRange(new byte[]{40, 41, 42}, failingIndex, 3), input.readAllBytes());
                    List<Long> expectedRequests = new ArrayList<>(List.of(0L, 1L, 2L, 3L));
                    expectedRequests.add(failingIndex, (long) failingIndex);
                    assertEquals(expectedRequests, volumes.requestedIndices);
                    assertEquals(-1, input.read());
                    assertEquals(expectedRequests, volumes.requestedIndices);
                }
                assertFalse(first.isOpen());
                assertFalse(second.isOpen());
                assertFalse(third.isOpen());
                assertEquals(1, volumes.closeCount());
            }
        }
    }

    /// Verifies ordinary zero-progress reads are observable without causing a premature volume transition.
    @Test
    void preservesCurrentVolumeAfterZeroProgressRead() throws IOException {
        TestSeekableByteChannel first = channel(new byte[]{30, 31});
        first.returnZeroOnRead(1);
        TestVolumeSource volumes = new TestVolumeSource(List.of(first), false);
        try (RarVolumeInputStream input = new RarVolumeInputStream(volumes)) {
            byte[] target = new byte[4];
            assertEquals(0, input.read(target, target.length, 0));
            assertEquals(0, volumes.openCount());
            assertThrows(IndexOutOfBoundsException.class, () -> input.read(target, 3, 2));
            assertThrows(NullPointerException.class, () -> input.read(null, 0, 0));
            assertEquals(0, input.read(target));
            assertEquals(0L, first.position());
            assertEquals(2, input.read(target));
            assertArrayEquals(new byte[]{30, 31, 0, 0}, target);
        }
    }

    /// Verifies truncated, stalled, and invalid continuation signatures fail and close their channels.
    @Test
    void rejectsMalformedContinuationSignatures() throws IOException {
        assertContinuationFailure(
                new byte[]{'R', 'a', 'r'},
                0,
                "missing a signature"
        );
        assertContinuationFailure(
                RAR5_SIGNATURE,
                1,
                "signature could not be read"
        );
        byte[] invalid = RAR5_SIGNATURE.clone();
        invalid[invalid.length - 1] = 1;
        assertContinuationFailure(invalid, 0, "Invalid RAR continuation volume signature");
    }

    /// Verifies close retries both a retained current channel and an owned volume source after combined failures.
    @Test
    void retriesCombinedChannelAndSourceCloseFailures() throws IOException {
        TestSeekableByteChannel channel = channel(new byte[]{40, 41});
        channel.failFirstClose();
        TestVolumeSource volumes = new TestVolumeSource(List.of(channel), true);
        RarVolumeInputStream input = new RarVolumeInputStream(volumes, true);
        assertEquals(40, input.read());

        IOException failure = assertThrows(IOException.class, input::close);
        assertEquals("channel close failed", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("source close failed", failure.getSuppressed()[0].getMessage());
        assertTrue(channel.isOpen());
        assertTrue(volumes.isOpen());
        assertThrows(ClosedChannelException.class, input::read);

        input.close();
        input.close();
        assertFalse(channel.isOpen());
        assertFalse(volumes.isOpen());
        assertEquals(2, channel.closeCount());
        assertEquals(2, volumes.closeCount());
    }

    /// Verifies close preserves one shared channel and source failure while retaining both cleanup retries.
    @Test
    void preservesSharedChannelAndSourceCloseFailure() throws IOException {
        IOException sharedFailure = new IOException("shared close failure");
        TestSeekableByteChannel channel = channel(new byte[]{42});
        channel.failFirstClose(sharedFailure);
        TestVolumeSource volumes = new TestVolumeSource(List.of(channel), sharedFailure);
        RarVolumeInputStream input = new RarVolumeInputStream(volumes, true);
        assertEquals(42, input.read());

        IOException failure = assertThrows(IOException.class, input::close);

        assertSame(sharedFailure, failure);
        assertEquals(0, failure.getSuppressed().length);
        assertTrue(channel.isOpen());
        assertTrue(volumes.isOpen());
        assertEquals(1, channel.closeCount());
        assertEquals(1, volumes.closeCount());

        input.close();
        assertFalse(channel.isOpen());
        assertFalse(volumes.isOpen());
        assertEquals(2, channel.closeCount());
        assertEquals(2, volumes.closeCount());
    }

    /// Verifies a failed cleanup after signature validation remains reachable for a later close retry.
    @Test
    void retriesContinuationCleanupAfterValidationFailure() throws IOException {
        TestSeekableByteChannel first = channel(new byte[0]);
        byte[] invalidSignature = RAR5_SIGNATURE.clone();
        invalidSignature[0] = 0;
        TestSeekableByteChannel continuation = channel(invalidSignature);
        continuation.failFirstClose();
        TestVolumeSource volumes = new TestVolumeSource(List.of(first, continuation), false);
        RarVolumeInputStream input = new RarVolumeInputStream(volumes, true);

        IOException failure = assertThrows(IOException.class, input::read);
        assertEquals("Invalid RAR continuation volume signature", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("channel close failed", failure.getSuppressed()[0].getMessage());
        assertTrue(continuation.isOpen());

        input.close();
        assertFalse(continuation.isOpen());
        assertFalse(volumes.isOpen());
        assertEquals(2, continuation.closeCount());
        assertEquals(1, volumes.closeCount());
    }

    /// Verifies a shared continuation-read and channel-close failure is preserved without self-suppression.
    @Test
    void preservesSharedContinuationReadAndCloseFailure() throws IOException {
        IOException sharedFailure = new IOException("shared continuation failure");
        TestSeekableByteChannel first = channel(new byte[0]);
        TestSeekableByteChannel continuation = channel(RAR5_SIGNATURE);
        continuation.failReadsAndFirstClose(sharedFailure, sharedFailure);
        TestVolumeSource volumes = new TestVolumeSource(List.of(first, continuation), false);
        RarVolumeInputStream input = new RarVolumeInputStream(volumes, true);

        IOException failure = assertThrows(IOException.class, input::read);

        assertSame(sharedFailure, failure);
        assertEquals(0, failure.getSuppressed().length);
        assertTrue(continuation.isOpen());
        assertEquals(1, continuation.closeCount());

        input.close();
        assertFalse(continuation.isOpen());
        assertFalse(volumes.isOpen());
        assertEquals(2, continuation.closeCount());
        assertEquals(1, volumes.closeCount());
    }

    /// Asserts one malformed continuation reports the requested diagnostic and closes its channel.
    private static void assertContinuationFailure(
            byte @Unmodifiable [] continuationBytes,
            int zeroRead,
            String expectedMessage
    ) throws IOException {
        TestSeekableByteChannel first = channel(new byte[0]);
        TestSeekableByteChannel continuation = channel(continuationBytes);
        if (zeroRead > 0) {
            continuation.returnZeroOnRead(zeroRead);
        }
        TestVolumeSource volumes = new TestVolumeSource(List.of(first, continuation), false);
        try (RarVolumeInputStream input = new RarVolumeInputStream(volumes)) {
            IOException failure = assertThrows(IOException.class, input::read);
            assertTrue(failure.getMessage().contains(expectedMessage));
        }
        assertFalse(first.isOpen());
        assertFalse(continuation.isOpen());
    }

    /// Creates one in-memory volume channel.
    private static TestSeekableByteChannel channel(byte[] bytes) {
        return new TestSeekableByteChannel(bytes);
    }

    /// Concatenates two byte arrays.
    private static byte @Unmodifiable [] concatenate(
            byte @Unmodifiable [] first,
            byte @Unmodifiable [] second
    ) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    /// Supplies a fixed sequence of test volume channels and tracks owned-source closure.
    @NotNullByDefault
    private static final class TestVolumeSource implements ArkivoVolumeSource {
        /// Volume channels returned in index order.
        private final @Unmodifiable List<TestSeekableByteChannel> channels;

        /// Failure reported by the first source close attempt, or `null` for successful close.
        private final @Nullable IOException closeFailure;

        /// Number of volume-open requests.
        private int openCount;

        /// The requested volume indices, including failed attempts and the final end-of-volumes lookup.
        private final List<Long> requestedIndices = new ArrayList<>();

        /// The volume index whose next opening fails, or a negative value when unconfigured.
        private long failingIndex = -1;

        /// The configured one-shot volume-open failure.
        private @Nullable Throwable openFailure;

        /// Number of source close attempts.
        private int closeCount;

        /// Whether this source remains open.
        private boolean open = true;

        /// Creates a source over the supplied channels.
        private TestVolumeSource(
                @Unmodifiable List<TestSeekableByteChannel> channels,
                boolean failFirstClose
        ) {
            this(channels, failFirstClose ? new IOException("source close failed") : null);
        }

        /// Creates a source over the supplied channels with an optional first-close failure.
        private TestVolumeSource(
                @Unmodifiable List<TestSeekableByteChannel> channels,
                @Nullable IOException closeFailure
        ) {
            this.channels = List.copyOf(channels);
            this.closeFailure = closeFailure;
        }

        /// Configures one volume-opening attempt to fail before transferring channel ownership.
        private void failOpening(long index, Throwable failure) {
            failingIndex = index;
            openFailure = failure;
        }

        /// Returns the requested channel or reports the end of the fixed sequence.
        @Override
        public @Nullable SeekableByteChannel openVolume(long index) throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
            openCount++;
            requestedIndices.add(index);
            if (index == failingIndex && openFailure != null) {
                Throwable failure = openFailure;
                openFailure = null;
                if (failure instanceof IOException exception) {
                    throw exception;
                }
                if (failure instanceof RuntimeException exception) {
                    throw exception;
                }
                throw (Error) failure;
            }
            return index >= 0L && index < channels.size() ? channels.get((int) index) : null;
        }

        /// Closes this source, optionally failing its first close attempt.
        @Override
        public void close() throws IOException {
            if (!open) {
                return;
            }
            closeCount++;
            if (closeFailure != null && closeCount == 1) {
                throw closeFailure;
            }
            open = false;
        }

        /// Returns the number of volume-open requests.
        private int openCount() {
            return openCount;
        }

        /// Returns the number of source close attempts.
        private int closeCount() {
            return closeCount;
        }

        /// Returns whether the source remains open.
        private boolean isOpen() {
            return open;
        }
    }

    /// Implements one read-only in-memory volume with controllable read and close failures.
    @NotNullByDefault
    private static final class TestSeekableByteChannel implements SeekableByteChannel {
        /// Immutable volume bytes.
        private final byte @Unmodifiable [] bytes;

        /// Current channel position.
        private long position;

        /// One-based read call that returns zero, or zero when disabled.
        private int zeroRead;

        /// Number of read calls.
        private int readCount;

        /// Failure reported by reads, or `null` for ordinary reads.
        private @Nullable IOException readFailure;

        /// Whether the first close attempt fails.
        private boolean failFirstClose;

        /// Failure reported by the first close attempt, or `null` for the default failure.
        private @Nullable IOException closeFailure;

        /// Number of close attempts.
        private int closeCount;

        /// Whether this channel remains open.
        private boolean open = true;

        /// Creates a channel over a defensive byte-array copy.
        private TestSeekableByteChannel(byte[] bytes) {
            this.bytes = bytes.clone();
        }

        /// Configures one read call to return zero.
        private void returnZeroOnRead(int read) {
            zeroRead = read;
        }

        /// Configures the first close attempt to fail.
        private void failFirstClose() {
            failFirstClose(new IOException("channel close failed"));
        }

        /// Configures the first close attempt to report the supplied failure.
        private void failFirstClose(IOException failure) {
            closeFailure = failure;
            failFirstClose = true;
        }

        /// Configures all reads and the first close attempt to report the supplied failures.
        private void failReadsAndFirstClose(IOException readFailure, IOException closeFailure) {
            this.readFailure = readFailure;
            this.closeFailure = closeFailure;
            this.failFirstClose = true;
        }

        /// Reads remaining volume bytes or performs the configured zero-progress call.
        @Override
        public int read(ByteBuffer target) throws IOException {
            ensureOpen();
            if (!target.hasRemaining()) {
                return 0;
            }
            readCount++;
            if (readFailure != null) {
                throw readFailure;
            }
            if (readCount == zeroRead) {
                return 0;
            }
            if (position >= bytes.length) {
                return -1;
            }
            int arrayPosition = Math.toIntExact(position);
            int length = Math.min(target.remaining(), bytes.length - arrayPosition);
            target.put(bytes, arrayPosition, length);
            position += length;
            return length;
        }

        /// Rejects writes because test volumes are immutable.
        @Override
        public int write(ByteBuffer source) throws IOException {
            ensureOpen();
            throw new NonWritableChannelException();
        }

        /// Returns the current position.
        @Override
        public long position() throws IOException {
            ensureOpen();
            return position;
        }

        /// Changes the current position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            ensureOpen();
            if (newPosition < 0L) {
                throw new IllegalArgumentException("newPosition must not be negative");
            }
            position = newPosition;
            return this;
        }

        /// Returns the fixed volume size.
        @Override
        public long size() throws IOException {
            ensureOpen();
            return bytes.length;
        }

        /// Rejects truncation because test volumes are immutable.
        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            ensureOpen();
            throw new NonWritableChannelException();
        }

        /// Returns whether this channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this channel, optionally failing the first attempt.
        @Override
        public void close() throws IOException {
            if (!open) {
                return;
            }
            closeCount++;
            if (failFirstClose && closeCount == 1) {
                throw closeFailure != null ? closeFailure : new IOException("channel close failed");
            }
            open = false;
        }

        /// Returns the number of close attempts.
        private int closeCount() {
            return closeCount;
        }

        /// Requires this channel to remain open.
        private void ensureOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }
}
