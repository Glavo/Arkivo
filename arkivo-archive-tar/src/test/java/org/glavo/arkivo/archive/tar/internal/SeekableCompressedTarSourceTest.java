// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar.internal;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArkivoReadLimitException;
import org.glavo.arkivo.archive.ArkivoReadLimitKind;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.ArkivoStoredContent;
import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.SeekableEncodingOptions;
import org.glavo.arkivo.codec.deflate.GzipCodec;
import org.glavo.arkivo.codec.zstd.ZstdCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies indexed outer-compression reuse and source-backed TAR body slices.
@NotNullByDefault
final class SeekableCompressedTarSourceTest {
    /// Verifies independently positioned slices preserve bounds, interruptibility, and channel ownership.
    @Test
    void opensIndependentBoundedSlices() throws IOException {
        byte[] decoded = patternedBytes(67);
        TrackingSource source = new TrackingSource(seekableEncoding(decoded));
        SeekableCompressedTarSource archive = openIndexed(source);
        TrackingChannel probe = source.channel(0);
        assertTrue(probe.isOpen());
        assertEquals(0L, probe.position());

        ArkivoStoredContent content = archive.newStoredContent(9L, 31L);
        assertEquals(31L, content.size());
        SeekableByteChannel first = content.openChannel(Set.of());
        SeekableByteChannel second = content.openChannel(Set.of(StandardOpenOption.READ));
        try {
            assertInstanceOf(InterruptibleChannel.class, first);
            assertInstanceOf(InterruptibleChannel.class, second);
            assertEquals(3, source.openCount());

            ByteBuffer complete = ByteBuffer.allocate(31);
            assertEquals(31, first.read(complete));
            assertArrayEquals(Arrays.copyOfRange(decoded, 9, 40), complete.array());
            assertEquals(-1, first.read(ByteBuffer.allocate(1)));

            second.position(11L);
            ByteBuffer range = ByteBuffer.allocate(8);
            assertEquals(8, second.read(range));
            assertArrayEquals(Arrays.copyOfRange(decoded, 20, 28), range.array());

            first.close();
            assertFalse(source.channel(1).isOpen());
            assertTrue(second.isOpen());
            content.close();
            assertTrue(second.isOpen());
        } finally {
            first.close();
            second.close();
            probe.close();
        }
        assertFalse(source.channel(2).isOpen());
    }

    /// Verifies a slice reports complete-frame progress before a later indexed frame fails.
    @Test
    void preservesDecodedProgressBeforeLaterFrameFailure() throws IOException {
        byte[] decoded = patternedBytes(25);
        byte[] encoded = seekableEncoding(decoded);
        long firstFrameSize;
        long secondFrameOffset;
        try (TrackingChannel indexSource = new TrackingChannel(encoded, 5)) {
            var index = Objects.requireNonNull(ZstdCodec.DEFAULT.readIndex(indexSource));
            firstFrameSize = index.frameUncompressedSize(0);
            secondFrameOffset = index.frameCompressedOffset(1);
        }

        byte[] corrupted = encoded.clone();
        corrupted[Math.toIntExact(secondFrameOffset)] ^= 1;
        TrackingSource source = new TrackingSource(encoded);
        SeekableCompressedTarSource archive = openIndexed(source);
        source.replaceContent(corrupted);

        ArkivoStoredContent content = archive.newStoredContent(0L, decoded.length);
        SeekableByteChannel channel = content.openChannel(Set.of());
        try {
            ByteBuffer target = ByteBuffer.allocate(decoded.length + 2);
            target.position(1);
            target.limit(1 + decoded.length);

            assertThrows(IOException.class, () -> channel.read(target));
            int transferred = Math.toIntExact(firstFrameSize);
            assertEquals(1 + transferred, target.position());
            assertEquals(1 + decoded.length, target.limit());
            assertEquals(firstFrameSize, channel.position());
            assertArrayEquals(
                    Arrays.copyOfRange(decoded, 0, transferred),
                    Arrays.copyOfRange(target.array(), 1, target.position())
            );
        } finally {
            channel.close();
            source.channel(0).close();
        }
        assertFalse(source.channel(1).isOpen());
    }

    /// Verifies slice range validation, read-only options, empty reads, and closed-channel behavior.
    @Test
    void validatesSliceBoundsAndReadOnlyChannelContract() throws IOException {
        byte[] decoded = patternedBytes(24);
        TrackingSource source = new TrackingSource(seekableEncoding(decoded));
        SeekableCompressedTarSource archive = openIndexed(source);

        assertThrows(IllegalArgumentException.class, () -> archive.newStoredContent(-1L, 0L));
        assertThrows(IllegalArgumentException.class, () -> archive.newStoredContent(0L, -1L));
        assertThrows(IllegalArgumentException.class, () -> archive.newStoredContent(23L, 2L));
        assertThrows(IllegalArgumentException.class, () -> archive.newStoredContent(Long.MAX_VALUE, 1L));

        ArkivoStoredContent content = archive.newStoredContent(3L, 5L);
        int openCount = source.openCount();
        assertThrows(
                UnsupportedOperationException.class,
                () -> content.openChannel(Set.of(StandardOpenOption.WRITE))
        );
        assertEquals(openCount, source.openCount());

        ArkivoStoredContent empty = archive.newStoredContent(decoded.length, 0L);
        SeekableByteChannel channel = empty.openChannel(Set.of());
        try {
            assertEquals(0, channel.read(ByteBuffer.allocate(0)));
            assertEquals(-1, channel.read(ByteBuffer.allocate(1)));
            channel.position(10L);
            assertEquals(-1, channel.read(ByteBuffer.allocate(1)));
            assertThrows(NonWritableChannelException.class, () -> channel.write(ByteBuffer.allocate(1)));
            assertThrows(NonWritableChannelException.class, () -> channel.truncate(0L));
        } finally {
            channel.close();
            source.channel(0).close();
        }
        assertThrows(ClosedChannelException.class, channel::position);
        assertThrows(ClosedChannelException.class, channel::size);
        assertThrows(ClosedChannelException.class, () -> channel.read(ByteBuffer.allocate(1)));
    }

    /// Verifies whole-stream adapters seek efficiently and close their independently opened encoded session.
    @Test
    void inputStreamSkipUsesLogicalPositionAndOwnsItsSession() throws IOException {
        byte[] decoded = patternedBytes(41);
        TrackingSource source = new TrackingSource(seekableEncoding(decoded));
        SeekableCompressedTarSource archive = openIndexed(source);

        try (InputStream input = archive.newInputStream()) {
            assertEquals(13L, input.skip(13L));
            assertEquals(Byte.toUnsignedInt(decoded[13]), input.read());
            assertArrayEquals(Arrays.copyOfRange(decoded, 14, decoded.length), input.readAllBytes());
        } finally {
            source.channel(0).close();
        }
        assertEquals(2, source.openCount());
        assertFalse(source.channel(1).isOpen());
    }

    /// Verifies non-seekable codecs are ignored and indexed decoded-size limits fail before body access.
    @Test
    void detectsApplicableCodecAndEnforcesDecodedArchiveLimit() throws IOException {
        byte[] decoded = patternedBytes(37);
        TrackingSource source = new TrackingSource(seekableEncoding(decoded));
        TrackingChannel probe = source.openChannel();
        probe.position(3L);
        try {
            assertNull(SeekableCompressedTarSource.open(source, probe, null, ArchiveReadLimits.UNLIMITED));
            assertNull(SeekableCompressedTarSource.open(
                    source,
                    probe,
                    GzipCodec.DEFAULT,
                    ArchiveReadLimits.UNLIMITED
            ));
            assertEquals(3L, probe.position());

            ArchiveReadLimits limits = ArchiveReadLimits.builder()
                    .maximumDecodedArchiveSize(decoded.length - 1L)
                    .build();
            ArkivoReadLimitException failure = assertThrows(
                    ArkivoReadLimitException.class,
                    () -> SeekableCompressedTarSource.open(source, probe, ZstdCodec.DEFAULT, limits)
            );
            assertEquals(ArkivoReadLimitKind.DECODED_ARCHIVE_SIZE, failure.kind());
            assertEquals(decoded.length - 1L, failure.maximum());
            assertEquals(decoded.length, failure.actual());
            assertNull(failure.entryPath());
            assertTrue(probe.isOpen());
        } finally {
            probe.close();
        }
    }

    /// Verifies a fresh encoded channel is closed when it no longer matches the parsed index.
    @Test
    void closesEncodedChannelWhenSliceSetupFails() throws IOException {
        byte[] decoded = patternedBytes(29);
        byte[] encoded = seekableEncoding(decoded);
        TrackingSource source = new TrackingSource(encoded);
        SeekableCompressedTarSource archive = openIndexed(source);
        source.replaceContent(Arrays.copyOf(encoded, encoded.length - 1));

        ArkivoStoredContent content = archive.newStoredContent(0L, decoded.length);
        assertThrows(IOException.class, () -> content.openChannel(Set.of()));
        assertEquals(2, source.openCount());
        assertFalse(source.channel(1).isOpen());
        assertTrue(source.channel(1).closeAttempts() >= 1);
        source.channel(0).close();
    }

    /// Verifies a shared setup and cleanup exception is propagated without illegal self-suppression.
    @Test
    void preservesSharedSetupAndCleanupFailure() throws IOException {
        byte[] decoded = patternedBytes(29);
        TrackingSource source = new TrackingSource(seekableEncoding(decoded));
        SeekableCompressedTarSource archive = openIndexed(source);
        IOException sharedFailure = new IOException("shared setup failure");
        source.failNextChannelWith(sharedFailure);

        ArkivoStoredContent content = archive.newStoredContent(0L, decoded.length);
        IOException failure = assertThrows(IOException.class, () -> content.openChannel(Set.of()));

        assertSame(sharedFailure, failure);
        assertEquals(0, failure.getSuppressed().length);
        assertEquals(2, source.openCount());
        TrackingChannel failedChannel = source.channel(1);
        assertTrue(failedChannel.isOpen());
        assertEquals(1, failedChannel.closeAttempts());

        failedChannel.repeatedFailure = null;
        failedChannel.close();
        source.channel(0).close();
    }

    /// Opens and requires an indexed compressed source through the first tracking channel.
    private static SeekableCompressedTarSource openIndexed(TrackingSource source) throws IOException {
        TrackingChannel probe = source.openChannel();
        return Objects.requireNonNull(SeekableCompressedTarSource.open(
                source,
                probe,
                ZstdCodec.DEFAULT,
                ArchiveReadLimits.UNLIMITED
        ));
    }

    /// Encodes bytes as a small-frame seekable Zstandard stream.
    private static byte @Unmodifiable [] seekableEncoding(byte @Unmodifiable [] decoded) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (WritableByteChannel target = Channels.newChannel(output);
             var encoder = ZstdCodec.DEFAULT.newSeekableWritableByteChannel(
                     target,
                     new SeekableEncodingOptions(decoded.length, 8),
                     ResourceOwnership.BORROWED
             )) {
            encoder.encode(ByteBuffer.wrap(decoded));
        }
        return output.toByteArray();
    }

    /// Creates deterministic decoded bytes spanning several indexed frames.
    private static byte @Unmodifiable [] patternedBytes(int size) {
        byte[] bytes = new byte[size];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (index * 29 + 7);
        }
        return bytes;
    }

    /// Opens independently positioned interruptible channels over replaceable immutable bytes.
    @NotNullByDefault
    private static final class TrackingSource implements ArkivoSeekableChannelSource {
        /// Current encoded bytes copied into subsequently opened channels.
        private byte @Unmodifiable [] content;

        /// Channels opened so far in call order.
        private final ArrayList<TrackingChannel> channels = new ArrayList<>();

        /// Failure assigned to the next opened channel's positioning and closure, or `null`.
        private @Nullable IOException nextRepeatedFailure;

        /// Creates a tracking source over a private copy of encoded bytes.
        private TrackingSource(byte @Unmodifiable [] content) {
            this.content = content.clone();
        }

        /// Opens a new fragmented read-only interruptible channel.
        @Override
        public TrackingChannel openChannel() {
            TrackingChannel channel = new TrackingChannel(content, 5);
            channel.repeatedFailure = nextRepeatedFailure;
            nextRepeatedFailure = null;
            channels.add(channel);
            return channel;
        }

        /// Makes the next opened channel report the same failure from positioning and closure.
        private void failNextChannelWith(IOException failure) {
            nextRepeatedFailure = Objects.requireNonNull(failure, "failure");
        }

        /// Changes the bytes exposed by future channels without affecting already opened channels.
        private void replaceContent(byte @Unmodifiable [] content) {
            this.content = content.clone();
        }

        /// Returns one channel opened previously.
        private TrackingChannel channel(int index) {
            return channels.get(index);
        }

        /// Returns the number of independently opened channels.
        private int openCount() {
            return channels.size();
        }
    }

    /// Implements a fragmented, read-only, interruptible in-memory channel with observable closure.
    @NotNullByDefault
    private static final class TrackingChannel implements SeekableByteChannel, InterruptibleChannel {
        /// Immutable channel bytes.
        private final byte @Unmodifiable [] content;

        /// Maximum positive bytes returned by one physical read.
        private final int maximumReadSize;

        /// Current channel position.
        private long position;

        /// Number of close attempts.
        private int closeAttempts;

        /// Whether the channel remains open.
        private boolean open = true;

        /// Failure optionally repeated by positioning and closure.
        private @Nullable IOException repeatedFailure;

        /// Creates a channel over a private content copy.
        private TrackingChannel(byte @Unmodifiable [] content, int maximumReadSize) {
            this.content = content.clone();
            this.maximumReadSize = maximumReadSize;
        }

        /// Reads one bounded fragment from the current position.
        @Override
        public int read(ByteBuffer target) throws IOException {
            Objects.requireNonNull(target, "target");
            ensureOpen();
            if (!target.hasRemaining()) {
                return 0;
            }
            if (position >= content.length) {
                return -1;
            }
            int offset = Math.toIntExact(position);
            int count = Math.min(Math.min(target.remaining(), maximumReadSize), content.length - offset);
            target.put(content, offset, count);
            position += count;
            return count;
        }

        /// Rejects writes because the encoded view is read-only.
        @Override
        public int write(ByteBuffer source) throws IOException {
            Objects.requireNonNull(source, "source");
            ensureOpen();
            throw new NonWritableChannelException();
        }

        /// Returns the current physical position.
        @Override
        public long position() throws IOException {
            ensureOpen();
            return position;
        }

        /// Changes the physical position.
        ///
        /// @throws IOException if the configured repeated failure remains active
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            if (newPosition < 0L) {
                throw new IllegalArgumentException("newPosition must not be negative");
            }
            ensureOpen();
            if (repeatedFailure != null) {
                throw repeatedFailure;
            }
            position = newPosition;
            return this;
        }

        /// Returns the fixed encoded byte count.
        @Override
        public long size() throws IOException {
            ensureOpen();
            return content.length;
        }

        /// Rejects truncation because the encoded view is read-only.
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

        /// Closes this channel and records every close attempt.
        ///
        /// @throws IOException if the configured repeated failure remains active
        @Override
        public void close() throws IOException {
            closeAttempts++;
            if (repeatedFailure != null) {
                throw repeatedFailure;
            }
            open = false;
        }

        /// Returns the number of close attempts.
        private int closeAttempts() {
            return closeAttempts;
        }

        /// Requires this channel to remain open.
        private void ensureOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }
}
