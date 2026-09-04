// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.EncodingOptions;
import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.SeekableEncodingOptions;
import org.glavo.arkivo.codec.zstd.ZstdCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies seekable Zstandard writer frame bookkeeping, lifecycle, and transport-failure contracts.
@NotNullByDefault
final class ZstdSeekableWritableByteChannelTest {
    /// Temporary directory used to expose in-memory encodings through seekable channels.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies direct-buffer hashing and frame-record array growth preserve all indexed data.
    @Test
    void writesDirectInputAcrossExpandedChecksumTable() throws IOException {
        int maximumFrameSize = 9_000;
        byte[] expected = patternedBytes(maximumFrameSize * 17 + 123);
        ByteBuffer source = ByteBuffer.allocateDirect(expected.length + 11);
        source.position(7);
        source.put(expected);
        source.flip();
        source.position(7);
        int sourceLimit = source.limit();
        CollectingWritableChannel target = new CollectingWritableChannel();
        ZstdCodec codec = ZstdCodec.DEFAULT.withFrameChecksum(true);
        CompressingWritableByteChannel.FlushableFramed writer = codec.newSeekableWritableByteChannel(
                target,
                new SeekableEncodingOptions(expected.length, maximumFrameSize),
                ResourceOwnership.BORROWED
        );

        assertFalse(writer instanceof InterruptibleChannel);
        assertEquals(expected.length, writer.write(source));
        assertEquals(sourceLimit, source.position());
        assertEquals(expected.length, writer.inputBytes());

        writer.close();
        writer.close();
        byte[] encoded = target.bytes();
        assertFalse(writer.isOpen());
        assertTrue(target.isOpen());
        assertEquals(encoded.length, writer.outputBytes());

        CompressionCodec.Seekable.Index index = readIndex(codec, encoded, "expanded-table.zst");
        assertEquals(18, index.frameCount());
        for (int frameIndex = 0; frameIndex < 17; frameIndex++) {
            assertEquals(maximumFrameSize, index.frameUncompressedSize(frameIndex));
        }
        assertEquals(123L, index.frameUncompressedSize(17));
        assertArrayEquals(expected, decompress(codec, encoded, expected.length));
    }

    /// Verifies explicit frame starts, repeated boundaries, terminal idempotence, and closed-state rejection.
    @Test
    void supportsExplicitFrameLifecycleAndTerminalNoOps() throws IOException {
        byte[] expected = {1, 2, 3, 4, 5};
        CollectingWritableChannel target = new CollectingWritableChannel();
        CompressingWritableByteChannel.FlushableFramed writer =
                ZstdCodec.DEFAULT.newSeekableWritableByteChannel(
                        target,
                        new SeekableEncodingOptions(expected.length, 64),
                        ResourceOwnership.BORROWED
                );

        assertEquals(0, writer.write(ByteBuffer.allocateDirect(0)));
        assertThrows(IllegalStateException.class, writer::startFrame);
        assertEquals(2, writer.write(ByteBuffer.wrap(expected, 0, 2)));
        writer.finishFrame();
        long firstBoundarySize = writer.outputBytes();
        writer.finishFrame();
        assertEquals(firstBoundarySize, writer.outputBytes());

        writer.startFrame(EncodingOptions.ofSourceSize(3L));
        assertThrows(IllegalStateException.class, writer::startFrame);
        assertEquals(3, writer.write(ByteBuffer.wrap(expected, 2, 3)));
        writer.finishFrame();
        writer.finish();
        int encodedSize = target.size();
        writer.finish();
        writer.close();

        assertEquals(expected.length, writer.inputBytes());
        assertEquals(encodedSize, writer.outputBytes());
        assertFalse(writer.isOpen());
        assertTrue(target.isOpen());
        assertThrows(IOException.class, () -> writer.write(ByteBuffer.allocate(1)));
        assertThrows(IOException.class, writer::flush);
        assertThrows(IOException.class, writer::finishFrame);
        assertThrows(IOException.class, () -> writer.startFrame(EncodingOptions.DEFAULT));

        byte[] encoded = target.bytes();
        CompressionCodec.Seekable.Index index = readIndex(
                ZstdCodec.DEFAULT,
                encoded,
                "explicit-frames.zst"
        );
        assertEquals(2, index.frameCount());
        assertEquals(2L, index.frameUncompressedSize(0));
        assertEquals(3L, index.frameUncompressedSize(1));
        assertArrayEquals(expected, decompress(ZstdCodec.DEFAULT, encoded, expected.length));
    }

    /// Verifies a logical source-size overflow is rejected before consuming the failing source buffer.
    @Test
    void rejectsPledgedInputOverflowWithoutConsumption() throws IOException {
        CollectingWritableChannel target = new CollectingWritableChannel();
        CompressingWritableByteChannel.FlushableFramed writer =
                ZstdCodec.DEFAULT.newSeekableWritableByteChannel(
                        target,
                        new SeekableEncodingOptions(3L, 64),
                        ResourceOwnership.BORROWED
                );

        assertEquals(2, writer.write(ByteBuffer.wrap(new byte[]{1, 2})));
        ByteBuffer excess = ByteBuffer.wrap(new byte[]{3, 4});
        IOException overflow = assertThrows(IOException.class, () -> writer.write(excess));
        assertEquals("Seekable encoding input exceeds the pledged source size", overflow.getMessage());
        assertEquals(0, excess.position());
        assertEquals(2L, writer.inputBytes());

        IOException incomplete = assertThrows(IOException.class, writer::finish);
        assertEquals(
                "Seekable encoding accepted 2 bytes but the pledged source size is 3",
                incomplete.getMessage()
        );
        int terminalSize = target.size();
        writer.finish();
        writer.close();
        assertEquals(terminalSize, target.size());
        assertFalse(writer.isOpen());
        assertTrue(target.isOpen());
    }

    /// Verifies seek-table output rejects zero progress and retains byte counts after a partial transport failure.
    @Test
    void reportsSeekTableTransportStallsAndPartialProgress() throws IOException {
        byte[] source = patternedBytes(128);

        CollectingWritableChannel stalledTarget = new CollectingWritableChannel();
        CompressingWritableByteChannel.FlushableFramed stalledWriter =
                ZstdCodec.DEFAULT.newSeekableWritableByteChannel(stalledTarget);
        stalledWriter.write(ByteBuffer.wrap(source));
        stalledWriter.finishFrame();
        long beforeStall = stalledWriter.outputBytes();
        stalledTarget.makeNoProgress();
        IOException stalled = assertThrows(IOException.class, stalledWriter::finish);
        assertEquals("Zstandard seek-table target made no progress", stalled.getMessage());
        assertEquals(beforeStall, stalledWriter.outputBytes());
        assertEquals(beforeStall, stalledTarget.size());
        stalledTarget.resumeWrites();
        stalledWriter.close();

        CollectingWritableChannel failingTarget = new CollectingWritableChannel();
        CompressingWritableByteChannel.FlushableFramed failingWriter =
                ZstdCodec.DEFAULT.newSeekableWritableByteChannel(failingTarget);
        failingWriter.write(ByteBuffer.wrap(source));
        failingWriter.finishFrame();
        long beforeFailure = failingWriter.outputBytes();
        IOException transportFailure = new IOException("seek-table transport failure");
        failingTarget.failWritesAfter(3, transportFailure);

        IOException thrown = assertThrows(IOException.class, failingWriter::finish);
        assertSame(transportFailure, thrown);
        assertEquals(beforeFailure + 3L, failingWriter.outputBytes());
        assertEquals(beforeFailure + 3L, failingTarget.size());
        failingWriter.close();
    }

    /// Verifies an owned-target close retry does not serialize the terminal seek table more than once.
    @Test
    void retriesOwnedTargetCloseWithoutDuplicatingOutput() throws IOException {
        byte[] expected = patternedBytes(257);
        IOException closeFailure = new IOException("target close failure");
        CollectingWritableChannel target = new CollectingWritableChannel();
        target.failNextCloses(1, closeFailure);
        CompressingWritableByteChannel.FlushableFramed writer =
                ZstdCodec.DEFAULT.newSeekableWritableByteChannel(
                        target,
                        SeekableEncodingOptions.DEFAULT,
                        ResourceOwnership.OWNED
                );
        writer.write(ByteBuffer.wrap(expected));

        IOException thrown = assertThrows(IOException.class, writer::finish);
        assertSame(closeFailure, thrown);
        byte[] encoded = target.bytes();
        assertEquals(1, target.closeAttempts());
        assertTrue(target.isOpen());
        assertFalse(writer.isOpen());

        writer.finish();
        assertEquals(2, target.closeAttempts());
        assertFalse(target.isOpen());
        assertArrayEquals(encoded, target.bytes());
        assertEquals(encoded.length, writer.outputBytes());
        writer.close();
        assertEquals(2, target.closeAttempts());
        assertArrayEquals(expected, decompress(ZstdCodec.DEFAULT, encoded, expected.length));
    }

    /// Verifies the interoperable frame-size bound is checked before the target is used or closed.
    @Test
    void rejectsOversizedMaximumFrameBeforeUsingTarget() throws IOException {
        CollectingWritableChannel target = new CollectingWritableChannel();
        SeekableEncodingOptions options = new SeekableEncodingOptions(
                CompressionCodec.UNKNOWN_SIZE,
                ZstdSeekableFormat.MAXIMUM_FRAME_SIZE + 1
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> ZstdCodec.DEFAULT.newSeekableWritableByteChannel(
                        target,
                        options,
                        ResourceOwnership.OWNED
                )
        );
        assertTrue(target.isOpen());
        assertEquals(0, target.size());
        assertEquals(0, target.closeAttempts());
        target.close();
    }

    /// Reads a non-null seekable index from one encoded byte array.
    private CompressionCodec.Seekable.Index readIndex(
            ZstdCodec codec,
            byte[] encoded,
            String fileName
    ) throws IOException {
        Path path = Files.write(temporaryDirectory.resolve(fileName), encoded);
        try (SeekableByteChannel source = Files.newByteChannel(path, StandardOpenOption.READ)) {
            CompressionCodec.Seekable.Index index = codec.readIndex(source);
            if (index == null) {
                throw new AssertionError("Expected a Zstandard seek table");
            }
            return index;
        }
    }

    /// Decodes concatenated data frames while ignoring the terminal skippable seek-table frame.
    private static byte[] decompress(ZstdCodec codec, byte[] encoded, int expectedSize) throws IOException {
        ByteBuffer decoded = codec.withMaximumOutputSize(expectedSize).decompress(ByteBuffer.wrap(encoded));
        byte[] actual = new byte[decoded.remaining()];
        decoded.get(actual);
        return actual;
    }

    /// Creates deterministic bytes that exercise literal and match encoding paths.
    private static byte[] patternedBytes(int size) {
        byte[] bytes = new byte[size];
        for (int index = 0; index < size; index++) {
            bytes[index] = (byte) (index * 31 + index / 17);
        }
        return bytes;
    }

    /// Collects writes while exposing deterministic progress, write-failure, and close-failure controls.
    @NotNullByDefault
    private static final class CollectingWritableChannel implements WritableByteChannel {
        /// Bytes accepted by this channel.
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        /// Write failure thrown after `bytesBeforeWriteFailure` additional bytes, or `null` when writes succeed.
        private @Nullable IOException writeFailure;

        /// Number of additional bytes accepted before `writeFailure` is thrown.
        private long bytesBeforeWriteFailure = Long.MAX_VALUE;

        /// Close failure thrown by the remaining configured close attempts, or `null` when closure succeeds.
        private @Nullable IOException closeFailure;

        /// Number of future close attempts that must fail.
        private int closeFailuresRemaining;

        /// Number of close attempts made against this channel.
        private int closeAttempts;

        /// Whether writes currently return zero without consuming input.
        private boolean zeroProgress;

        /// Whether this channel remains open.
        private boolean open = true;

        /// Accepts source bytes according to the configured progress and failure behavior.
        @Override
        public int write(ByteBuffer source) throws IOException {
            Objects.requireNonNull(source, "source");
            if (!open) {
                throw new ClosedChannelException();
            }
            if (zeroProgress) {
                return 0;
            }
            IOException failure = writeFailure;
            if (failure != null && bytesBeforeWriteFailure == 0L) {
                throw failure;
            }
            int count = source.remaining();
            if (failure != null) {
                count = (int) Math.min(count, bytesBeforeWriteFailure);
            }
            byte[] bytes = new byte[count];
            source.get(bytes);
            output.writeBytes(bytes);
            if (failure != null) {
                bytesBeforeWriteFailure -= count;
            }
            return count;
        }

        /// Returns whether this channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this channel unless the next configured close failure applies.
        @Override
        public void close() throws IOException {
            closeAttempts++;
            if (closeFailuresRemaining > 0) {
                closeFailuresRemaining--;
                throw Objects.requireNonNull(closeFailure, "closeFailure");
            }
            open = false;
        }

        /// Makes subsequent writes return zero without consuming source bytes.
        private void makeNoProgress() {
            zeroProgress = true;
        }

        /// Restores successful write progress.
        private void resumeWrites() {
            zeroProgress = false;
        }

        /// Configures a stable failure after the requested number of additional accepted bytes.
        private void failWritesAfter(long byteCount, IOException failure) {
            if (byteCount < 0L) {
                throw new IllegalArgumentException("byteCount must not be negative");
            }
            bytesBeforeWriteFailure = byteCount;
            writeFailure = Objects.requireNonNull(failure, "failure");
        }

        /// Configures the requested number of future close attempts to fail.
        private void failNextCloses(int count, IOException failure) {
            if (count < 0) {
                throw new IllegalArgumentException("count must not be negative");
            }
            closeFailuresRemaining = count;
            closeFailure = Objects.requireNonNull(failure, "failure");
        }

        /// Returns a snapshot of all accepted bytes.
        private byte[] bytes() {
            return output.toByteArray();
        }

        /// Returns the number of accepted bytes.
        private int size() {
            return output.size();
        }

        /// Returns the number of attempted closes.
        private int closeAttempts() {
            return closeAttempts;
        }
    }
}
