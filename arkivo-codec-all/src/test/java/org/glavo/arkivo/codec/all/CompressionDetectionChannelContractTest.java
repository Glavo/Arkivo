// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.all;

import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.CompressionFormats;
import org.glavo.arkivo.codec.CompressionProbeResult;
import org.glavo.arkivo.codec.ResourceOwnership;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies channel-based detection and prefix replay across every signature-bearing compression format.
@NotNullByDefault
final class CompressionDetectionChannelContractTest {
    /// Deterministic content encoded by every tested format.
    private static final byte @Unmodifiable [] CONTENT = (
            "compression channel detection contract 0123456789abcdef;".repeat(17)
    ).getBytes(StandardCharsets.UTF_8);

    /// Sentinel retained before a nonzero logical source origin.
    private static final byte SOURCE_GUARD = (byte) 0xd3;

    /// Verifies fragmented seekable detection recognizes every signed format and restores the initial position.
    @Test
    void detectsEverySignedFormatFromFragmentedSeekableSources() throws IOException {
        int formatIndex = 0;
        for (CompressionFormat format : CompressionFormats.installed()) {
            if (format.probeSize() == 0) {
                continue;
            }

            byte[] encoded = encode(format);
            int sourceOffset = 4 + formatIndex;
            byte[] embedded = new byte[sourceOffset + encoded.length];
            Arrays.fill(embedded, 0, sourceOffset, SOURCE_GUARD);
            System.arraycopy(encoded, 0, embedded, sourceOffset, encoded.length);

            try (FragmentingSeekableByteChannel source =
                         new FragmentingSeekableByteChannel(embedded, 1 + formatIndex % 5)) {
                source.position(sourceOffset);
                assertSame(format, CompressionFormats.detect(source), format.name());
                assertEquals(sourceOffset, source.position(), format.name());
            }
            formatIndex++;
        }
    }

    /// Verifies every signed format's forward probe copies its prefix and replays the complete logical source.
    @Test
    void probesAndReplaysEverySignedFormatWithBothOwnershipModes() throws IOException {
        int catalogProbeSize = CompressionFormats.installed()
                .stream()
                .mapToInt(CompressionFormat::probeSize)
                .max()
                .orElseThrow();
        int formatIndex = 0;
        for (CompressionFormat format : CompressionFormats.installed()) {
            if (format.probeSize() == 0) {
                continue;
            }

            byte[] encoded = encode(format);
            int requestedPrefixSize = format.probeSize() + 7;
            int expectedPrefixSize = Math.min(encoded.length, Math.max(catalogProbeSize, requestedPrefixSize));
            ResourceOwnership ownership = (formatIndex & 1) == 0
                    ? ResourceOwnership.BORROWED
                    : ResourceOwnership.OWNED;
            try (FragmentingSeekableByteChannel source =
                         new FragmentingSeekableByteChannel(encoded, 1 + formatIndex % 4);
                 CompressionProbeResult probe = CompressionFormats.probe(
                         source,
                         requestedPrefixSize,
                         ownership
                 )) {
                assertSame(format, probe.format(), format.name());

                ByteBuffer prefix = probe.prefix();
                assertTrue(prefix.isReadOnly(), format.name());
                assertEquals(0, prefix.position(), format.name());
                assertEquals(expectedPrefixSize, prefix.remaining(), format.name());
                byte[] retained = new byte[prefix.remaining()];
                prefix.get(retained);
                assertArrayEquals(Arrays.copyOf(encoded, expectedPrefixSize), retained, format.name());
                assertEquals(0, probe.prefix().position(), format.name());

                try (ReadableByteChannel replay = probe.takeChannel()) {
                    assertEquals(0, replay.read(ByteBuffer.allocate(0)), format.name());
                    assertArrayEquals(encoded, readAll(replay, format.name()), format.name());
                }
                assertEquals(ownership == ResourceOwnership.BORROWED, source.isOpen(), format.name());
            }
            formatIndex++;
        }
    }

    /// Encodes the common content with one format's default codec.
    private static byte @Unmodifiable [] encode(CompressionFormat format) throws IOException {
        ByteBuffer encoded = format.defaultCodec().compress(ByteBuffer.wrap(CONTENT));
        byte[] result = new byte[encoded.remaining()];
        encoded.get(result);
        return result;
    }

    /// Reads an entire channel through a small direct buffer while requiring forward progress.
    private static byte @Unmodifiable [] readAll(ReadableByteChannel source, String context) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        ByteBuffer target = ByteBuffer.allocateDirect(7);
        while (true) {
            target.clear();
            int read = source.read(target);
            if (read < 0) {
                return result.toByteArray();
            }
            assertTrue(read > 0, context);
            target.flip();
            byte[] chunk = new byte[target.remaining()];
            target.get(chunk);
            result.writeBytes(chunk);
        }
    }

    /// Provides a read-only in-memory seekable source whose reads are deliberately fragmented.
    @NotNullByDefault
    private static final class FragmentingSeekableByteChannel implements SeekableByteChannel {
        /// Immutable source bytes.
        private final byte @Unmodifiable [] bytes;

        /// Maximum bytes returned by one nonempty read.
        private final int maximumReadSize;

        /// Current channel position.
        private int position;

        /// Whether the channel remains open.
        private boolean open = true;

        /// Creates a source over a private copy of the supplied bytes.
        private FragmentingSeekableByteChannel(byte @Unmodifiable [] bytes, int maximumReadSize) {
            this.bytes = bytes.clone();
            this.maximumReadSize = maximumReadSize;
        }

        /// Reads at most the configured fragment size from the current position.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            ensureOpen();
            if (!destination.hasRemaining()) {
                return 0;
            }
            if (position >= bytes.length) {
                return -1;
            }
            int count = Math.min(
                    Math.min(destination.remaining(), maximumReadSize),
                    bytes.length - position
            );
            destination.put(bytes, position, count);
            position += count;
            return count;
        }

        /// Rejects writes because the source is read-only.
        @Override
        public int write(ByteBuffer source) throws IOException {
            ensureOpen();
            throw new NonWritableChannelException();
        }

        /// Returns the current byte position.
        @Override
        public long position() throws IOException {
            ensureOpen();
            return position;
        }

        /// Sets the current byte position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            ensureOpen();
            if (newPosition < 0L || newPosition > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("newPosition out of range: " + newPosition);
            }
            position = (int) newPosition;
            return this;
        }

        /// Returns the fixed source size.
        @Override
        public long size() throws IOException {
            ensureOpen();
            return bytes.length;
        }

        /// Rejects truncation because the source is read-only.
        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            ensureOpen();
            throw new NonWritableChannelException();
        }

        /// Returns whether the source remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes the source.
        @Override
        public void close() {
            open = false;
        }

        /// Throws when an operation requires an open source.
        private void ensureOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }
}
