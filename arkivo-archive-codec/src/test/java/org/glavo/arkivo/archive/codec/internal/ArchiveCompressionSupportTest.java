// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.codec.internal;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.internal.ArkivoStreamingSource;
import org.glavo.arkivo.codec.DecompressionWindowLimitException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnmodifiableView;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies outer-compression probing, limit propagation, and source ownership transfer.
@NotNullByDefault
final class ArchiveCompressionSupportTest {
    /// The fixed history window required by gzip's Deflate payload.
    private static final long GZIP_WINDOW_SIZE = 1L << 15;

    /// Verifies an unmatched prefix is replayed exactly and remains owned by the returned result.
    @Test
    void replaysUncompressedSourceWithoutTransformation() throws IOException {
        byte[] content = "plain archive bytes".getBytes(StandardCharsets.UTF_8);
        TrackingReadableByteChannel original = new TrackingReadableByteChannel(content, 1, false);

        ArkivoStreamingSource result = ArchiveCompressionSupport.probe(original, ArchiveReadOptions.DEFAULT);
        assertFalse(result.transformed());
        assertTrue(original.isOpen());

        try (ReadableByteChannel logicalSource = result.takeChannel()) {
            assertArrayEquals(content, readAllBytes(logicalSource));
            assertTrue(original.isOpen());
        }

        assertFalse(original.isOpen());
        assertEquals(1, original.closeCalls());
        result.close();
        assertEquals(1, original.closeCalls());
    }

    /// Verifies a recognized gzip layer is decoded and closes its owned compressed source.
    @Test
    void decodesRecognizedOuterCompression() throws IOException {
        byte[] content = ("outer compression payload-" + "0123456789".repeat(64))
                .getBytes(StandardCharsets.UTF_8);
        TrackingReadableByteChannel original = new TrackingReadableByteChannel(gzip(content), 1, false);

        try (ArkivoStreamingSource result = ArchiveCompressionSupport.probe(original, ArchiveReadOptions.DEFAULT)) {
            assertTrue(result.transformed());
            try (ReadableByteChannel logicalSource = result.takeChannel()) {
                assertArrayEquals(content, readAllBytes(logicalSource));
                assertTrue(original.isOpen());
            }
        }

        assertFalse(original.isOpen());
        assertEquals(1, original.closeCalls());
    }

    /// Verifies interruptibility survives both prefix replay and outer decompression adapters.
    @Test
    void preservesInterruptibleSourceCapability() throws IOException {
        byte[] content = "interruptible outer compression".getBytes(StandardCharsets.UTF_8);
        InterruptibleTrackingReadableByteChannel original =
                new InterruptibleTrackingReadableByteChannel(gzip(content), 1);

        ArkivoStreamingSource result = ArchiveCompressionSupport.probe(original, ArchiveReadOptions.DEFAULT);
        assertTrue(result.transformed());
        try (ReadableByteChannel logicalSource = result.takeChannel()) {
            assertInstanceOf(InterruptibleChannel.class, logicalSource);
            assertArrayEquals(content, readAllBytes(logicalSource));
        }

        assertFalse(original.isOpen());
    }

    /// Verifies both archive decoder-allocation limits reach the selected codec configuration.
    @Test
    void propagatesWindowAndMemoryLimits() throws IOException {
        byte[] compressed = gzip("limited gzip".getBytes(StandardCharsets.UTF_8));
        ArchiveReadLimits windowLimits = ArchiveReadLimits.builder()
                .maximumCompressionWindowSize(GZIP_WINDOW_SIZE - 1L)
                .build();
        ArchiveReadLimits memoryLimits = ArchiveReadLimits.builder()
                .maximumDecoderMemorySize(GZIP_WINDOW_SIZE - 1L)
                .build();

        assertRejectedAndClosed(compressed, windowLimits);
        assertRejectedAndClosed(compressed, memoryLimits);
    }

    /// Verifies failed decoder-setup cleanup is suppressed and retried without hiding the decoding failure.
    @Test
    void suppressesOwnedSourceCloseFailureAfterDecoderSetupFailure() throws IOException {
        byte[] compressed = gzip("close failure".getBytes(StandardCharsets.UTF_8));
        TrackingReadableByteChannel original = new TrackingReadableByteChannel(compressed, 2, true);
        ArchiveReadOptions options = ArchiveReadOptions.DEFAULT.withLimits(
                ArchiveReadLimits.builder()
                        .maximumCompressionWindowSize(GZIP_WINDOW_SIZE - 1L)
                        .build()
        );

        DecompressionWindowLimitException failure = assertThrows(
                DecompressionWindowLimitException.class,
                () -> ArchiveCompressionSupport.probe(original, options)
        );

        assertFalse(original.isOpen());
        assertEquals(2, original.closeCalls());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("close failure", failure.getSuppressed()[0].getMessage());
    }

    /// Verifies argument validation occurs before ownership of the supplied source is taken.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void validatesOptionsBeforeTakingSourceOwnership() throws IOException {
        TrackingReadableByteChannel original = new TrackingReadableByteChannel(new byte[]{1, 2, 3}, 1, false);

        assertThrows(NullPointerException.class, () -> ArchiveCompressionSupport.probe(original, null));
        assertTrue(original.isOpen());
        assertEquals(0, original.closeCalls());

        original.close();
    }

    /// Verifies one restrictive limit rejects gzip setup and closes the owned source.
    private static void assertRejectedAndClosed(byte[] compressed, ArchiveReadLimits limits) {
        TrackingReadableByteChannel original = new TrackingReadableByteChannel(compressed, 3, false);
        ArchiveReadOptions options = ArchiveReadOptions.DEFAULT.withLimits(limits);

        DecompressionWindowLimitException failure = assertThrows(
                DecompressionWindowLimitException.class,
                () -> ArchiveCompressionSupport.probe(original, options)
        );

        assertEquals(GZIP_WINDOW_SIZE - 1L, failure.maximumWindowSize());
        assertEquals(GZIP_WINDOW_SIZE, failure.requiredWindowSize());
        assertFalse(original.isOpen());
        assertEquals(1, original.closeCalls());
    }

    /// Encodes one gzip member with the JDK implementation.
    private static byte[] gzip(byte[] content) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(content);
        }
        return compressed.toByteArray();
    }

    /// Reads all bytes while exercising repeated small target buffers.
    private static byte[] readAllBytes(ReadableByteChannel source) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteBuffer target = ByteBuffer.allocate(7);
        while (true) {
            int read = source.read(target);
            if (read < 0) {
                return output.toByteArray();
            }
            if (read == 0) {
                throw new AssertionError("Readable channel made no progress");
            }
            output.write(target.array(), 0, target.position());
            target.clear();
        }
    }

    /// Provides a chunked source with observable close behavior.
    @NotNullByDefault
    private static class TrackingReadableByteChannel implements ReadableByteChannel {
        /// The remaining source bytes.
        private final @UnmodifiableView ByteBuffer content;

        /// The maximum bytes returned by one read.
        private final int maximumChunkSize;

        /// Whether the first close attempt reports an I/O failure.
        private final boolean failFirstClose;

        /// Whether the channel remains open.
        private boolean open = true;

        /// The number of close attempts.
        private int closeCalls;

        /// Creates a tracking source over a private content copy.
        private TrackingReadableByteChannel(byte[] content, int maximumChunkSize, boolean failFirstClose) {
            this.content = ByteBuffer.wrap(content.clone()).asReadOnlyBuffer();
            this.maximumChunkSize = maximumChunkSize;
            this.failFirstClose = failFirstClose;
        }

        /// Returns up to the configured chunk size from the source.
        @Override
        public int read(ByteBuffer target) throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
            if (!target.hasRemaining()) {
                return 0;
            }
            if (!content.hasRemaining()) {
                return -1;
            }

            int count = Math.min(Math.min(target.remaining(), content.remaining()), maximumChunkSize);
            ByteBuffer chunk = content.duplicate();
            chunk.limit(chunk.position() + count);
            target.put(chunk);
            content.position(content.position() + count);
            return count;
        }

        /// Returns whether the channel is open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes the channel and optionally reports the configured failure.
        @Override
        public void close() throws IOException {
            closeCalls++;
            if (failFirstClose && closeCalls == 1) {
                throw new IOException("close failure");
            }
            open = false;
        }

        /// Returns the number of close attempts.
        private int closeCalls() {
            return closeCalls;
        }
    }

    /// Provides an interruptible marker on the observable chunked source.
    @NotNullByDefault
    private static final class InterruptibleTrackingReadableByteChannel
            extends TrackingReadableByteChannel
            implements InterruptibleChannel {
        /// Creates an interruptible tracking source.
        private InterruptibleTrackingReadableByteChannel(byte[] content, int maximumChunkSize) {
            super(content, maximumChunkSize, false);
        }
    }
}
