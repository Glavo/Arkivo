// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.all;

import org.glavo.arkivo.codec.CompressionFormats;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies seekable compression-format detection preserves probe and restoration failures.
@NotNullByDefault
final class CompressionFormatsSeekableFailureTest {
    /// Verifies a distinct position-restoration failure is suppressed behind the read failure.
    @Test
    void suppressesDistinctRestorationFailure() {
        IOException readFailure = new IOException("read failure");
        IOException positionFailure = new IOException("position failure");
        ScriptedSeekableByteChannel source = new ScriptedSeekableByteChannel(readFailure, positionFailure);

        IOException exception = assertThrows(IOException.class, () -> CompressionFormats.detect(source));

        assertSame(readFailure, exception);
        assertEquals(1, exception.getSuppressed().length);
        assertSame(positionFailure, exception.getSuppressed()[0]);
        assertEquals(1, source.readCount());
        assertEquals(1, source.positionSetCount());
    }

    /// Verifies one shared read and restoration failure is propagated without illegal self-suppression.
    @Test
    void preservesSharedProbeFailure() {
        IOException failure = new IOException("shared failure");
        ScriptedSeekableByteChannel source = new ScriptedSeekableByteChannel(failure, failure);

        IOException exception = assertThrows(IOException.class, () -> CompressionFormats.detect(source));

        assertSame(failure, exception);
        assertEquals(0, exception.getSuppressed().length);
        assertEquals(1, source.readCount());
        assertEquals(1, source.positionSetCount());
    }

    /// Verifies zero progress is rejected and the original seekable position is restored.
    @Test
    void rejectsZeroProgressAndRestoresPosition() throws IOException {
        ScriptedSeekableByteChannel source = new ScriptedSeekableByteChannel(
                new byte[]{0x1f, (byte) 0x8b, 0x08},
                true,
                null
        );

        IOException exception = assertThrows(IOException.class, () -> CompressionFormats.detect(source));

        assertEquals("Compression format probe made no progress", exception.getMessage());
        assertEquals(1, source.readCount());
        assertEquals(1, source.positionSetCount());
        assertEquals(0L, source.position());
    }

    /// Verifies a restoration failure becomes primary after an otherwise successful format match.
    @Test
    void reportsRestorationFailureAfterSuccessfulProbe() {
        IOException positionFailure = new IOException("position failure");
        ScriptedSeekableByteChannel source = new ScriptedSeekableByteChannel(
                new byte[]{0x1f, (byte) 0x8b, 0x08, 0, 0, 0, 0, 0, 0, 0},
                false,
                positionFailure
        );

        IOException exception = assertThrows(IOException.class, () -> CompressionFormats.detect(source));

        assertSame(positionFailure, exception);
        assertEquals(0, exception.getSuppressed().length);
        assertTrue(source.readCount() > 0);
        assertEquals(1, source.positionSetCount());
    }

    /// Verifies an empty seekable source reaches physical end-of-input and still restores its origin.
    @Test
    void detectsEmptySourceAndRestoresPosition() throws IOException {
        ScriptedSeekableByteChannel source = new ScriptedSeekableByteChannel(new byte[0], false, null);

        assertNull(CompressionFormats.detect(source));

        assertEquals(1, source.readCount());
        assertEquals(1, source.positionSetCount());
        assertEquals(0L, source.position());
    }

    /// Implements a seekable source with configurable bytes, zero progress, and operation failures.
    @NotNullByDefault
    private static final class ScriptedSeekableByteChannel implements SeekableByteChannel {
        /// The immutable source bytes.
        private final byte @Unmodifiable [] content;

        /// The optional read failure.
        private final @Nullable IOException readFailure;

        /// Whether nonempty reads make zero progress.
        private final boolean zeroProgress;

        /// The optional position-set failure.
        private final @Nullable IOException positionFailure;

        /// The current read position.
        private long position;

        /// Number of read attempts.
        private int readCount;

        /// Number of position-set attempts.
        private int positionSetCount;

        /// Whether this channel remains open.
        private boolean open = true;

        /// Creates a channel that reports the requested read and restoration failures.
        private ScriptedSeekableByteChannel(IOException readFailure, IOException positionFailure) {
            this(new byte[0], readFailure, false, positionFailure);
        }

        /// Creates a channel over bytes with optional zero progress and restoration failure.
        private ScriptedSeekableByteChannel(
                byte[] content,
                boolean zeroProgress,
                @Nullable IOException positionFailure
        ) {
            this(content, null, zeroProgress, positionFailure);
        }

        /// Creates a fully scripted seekable channel.
        private ScriptedSeekableByteChannel(
                byte[] content,
                @Nullable IOException readFailure,
                boolean zeroProgress,
                @Nullable IOException positionFailure
        ) {
            this.content = content.clone();
            this.readFailure = readFailure;
            this.zeroProgress = zeroProgress;
            this.positionFailure = positionFailure;
        }

        /// Records one read attempt and reports the configured failure.
        @Override
        public int read(ByteBuffer target) throws IOException {
            readCount++;
            if (readFailure != null) {
                throw readFailure;
            }
            if (!target.hasRemaining()) {
                return 0;
            }
            if (zeroProgress) {
                return 0;
            }
            if (position >= content.length) {
                return -1;
            }
            int count = (int) Math.min((long) target.remaining(), content.length - position);
            target.put(content, Math.toIntExact(position), count);
            position += count;
            return count;
        }

        /// Rejects writes because the source is read-only.
        @Override
        public int write(ByteBuffer source) {
            throw new UnsupportedOperationException("read-only");
        }

        /// Returns the current source position.
        @Override
        public long position() {
            return position;
        }

        /// Records one position change and either reports the configured failure or updates the position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            positionSetCount++;
            if (positionFailure != null) {
                throw positionFailure;
            }
            if (newPosition < 0L) {
                throw new IllegalArgumentException("newPosition must not be negative");
            }
            position = newPosition;
            return this;
        }

        /// Returns the source byte count.
        @Override
        public long size() {
            return content.length;
        }

        /// Rejects truncation because the source is read-only.
        @Override
        public SeekableByteChannel truncate(long size) {
            throw new UnsupportedOperationException("read-only");
        }

        /// Returns whether this channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this synthetic channel.
        @Override
        public void close() {
            open = false;
        }

        /// Returns the number of read attempts.
        private int readCount() {
            return readCount;
        }

        /// Returns the number of position-set attempts.
        private int positionSetCount() {
            return positionSetCount;
        }
    }
}
