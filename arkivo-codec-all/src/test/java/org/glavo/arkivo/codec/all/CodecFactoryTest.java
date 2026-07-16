// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.all;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CodecTransferResult;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.CompressionFormats;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies unified codec context discovery and channel ownership.
@NotNullByDefault
final class CodecFactoryTest {
    /// Repetitive content used for every codec round trip.
    private static final byte @Unmodifiable [] CONTENT =
            "unified codec context factory\n".repeat(512).getBytes(StandardCharsets.UTF_8);

    /// Verifies named factories round-trip the default codec of every installed bidirectional format.
    @Test
    void roundTripsEveryDefaultCodecByFormatName() throws IOException {
        for (CompressionFormat format : CompressionFormats.installed()) {
            CompressionCodec<?> codec = format.defaultCodec();
            TrackingWritableChannel target = new TrackingWritableChannel();
            try (CompressingWritableByteChannel encoder = CompressionFormats.newWritableByteChannel(
                    codec.format().name(),
                    target,
                    ChannelOwnership.CLOSE
            )) {
                writeAll(encoder, CONTENT);
            }
            assertFalse(target.isOpen(), codec.format().name());

            TrackingReadableChannel source = new TrackingReadableChannel(target.bytes());
            CompressionCodec<?> decoderCodec =
                    CodecContractConfigurations.decoderCodec(codec, CONTENT.length);
            try (DecompressingReadableByteChannel decoder = decoderCodec.newReadableByteChannel(
                    source,
                    ChannelOwnership.CLOSE
            )) {
                assertArrayEquals(CONTENT, readAll(decoder), codec.format().name());
            }
            assertFalse(source.isOpen(), codec.format().name());
        }
    }

    /// Verifies required lookup accepts stable names and aliases and rejects unknown formats.
    @Test
    void requiresFormatsByNameOrAlias() {
        CompressionFormat format = CompressionFormats.require("bzip2");

        assertSame(format, CompressionFormats.require("BZIP2"));
        assertSame(format, CompressionFormats.require("bz2"));
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> CompressionFormats.require("missing")
        );
        assertEquals("Unknown compression format: missing", exception.getMessage());
    }

    /// Verifies convenience overloads accept aliases and retain caller-owned channels.
    @Test
    void opensNamedAliasesWhileRetainingChannels() throws IOException {
        TrackingWritableChannel target = new TrackingWritableChannel();
        try (CompressingWritableByteChannel encoder = CompressionFormats.newWritableByteChannel("bz2", target)) {
            writeAll(encoder, CONTENT);
        }
        assertTrue(target.isOpen());

        TrackingReadableChannel source = new TrackingReadableChannel(target.bytes());
        try (DecompressingReadableByteChannel decoder = CompressionFormats.newReadableByteChannel(
                "BZIP2",
                source
        )) {
            assertArrayEquals(CONTENT, readAll(decoder));
        }
        assertTrue(source.isOpen());
        source.close();
        target.close();
    }

    /// Verifies automatic decoder discovery replays prefixes for every signed codec.
    @Test
    void detectsAndOpensEverySignedCodec() throws IOException {
        Set<String> expected = Set.of("bzip2", "gzip", "xz", "zlib", "zstd");
        Set<String> signed = CompressionFormats.installed()
                .stream()
                .filter(format -> format.probeSize() > 0)
                .map(CompressionFormat::name)
                .collect(Collectors.toUnmodifiableSet());
        assertEquals(expected, signed);

        for (CompressionFormat format : CompressionFormats.installed()) {
            CompressionCodec<?> codec = format.defaultCodec();
            if (format.probeSize() == 0) {
                continue;
            }
            byte[] encoded = encode(codec.format().name());
            TrackingReadableChannel source = new TrackingReadableChannel(encoded);
            try (DecompressingReadableByteChannel decoder = CompressionFormats.newReadableByteChannel(
                    source,
                    ChannelOwnership.CLOSE
            )) {
                assertArrayEquals(CONTENT, readAll(decoder), codec.format().name());
            }
            assertFalse(source.isOpen(), codec.format().name());
        }
    }

    /// Verifies named channel transfers round-trip default codecs for all installed formats without closing caller channels.
    @Test
    void transfersEveryDefaultCodecByFormatName() throws IOException {
        for (CompressionFormat format : CompressionFormats.installed()) {
            CompressionCodec<?> codec = format.defaultCodec();

            TrackingReadableChannel source = new TrackingReadableChannel(CONTENT);
            TrackingWritableChannel compressed = new TrackingWritableChannel();
            CodecTransferResult compression = CompressionFormats.compress(
                    codec.format().name(),
                    source,
                    compressed
            );
            assertEquals(CONTENT.length, compression.inputBytes(), codec.format().name());
            assertEquals(compressed.bytes().length, compression.outputBytes(), codec.format().name());
            assertTrue(source.isOpen(), codec.format().name());
            assertTrue(compressed.isOpen(), codec.format().name());

            TrackingReadableChannel encoded = new TrackingReadableChannel(compressed.bytes());
            TrackingWritableChannel decoded = new TrackingWritableChannel();
            CompressionCodec<?> decoderCodec =
                    CodecContractConfigurations.decoderCodec(codec, CONTENT.length);
            CodecTransferResult decompression = decoderCodec.decompress(encoded, decoded);
            if (CodecContractConfigurations.requiresDecoderConfiguration(codec)) {
                assertTrue(decompression.inputBytes() <= compressed.bytes().length, codec.format().name());
            } else {
                assertEquals(compressed.bytes().length, decompression.inputBytes(), codec.format().name());
            }
            assertEquals(CONTENT.length, decompression.outputBytes(), codec.format().name());
            assertArrayEquals(CONTENT, decoded.bytes(), codec.format().name());
            assertTrue(encoded.isOpen(), codec.format().name());
            assertTrue(decoded.isOpen(), codec.format().name());

            if (codec.format().probeSize() > 0) {
                TrackingReadableChannel detectedSource =
                        new TrackingReadableChannel(compressed.bytes());
                TrackingWritableChannel detectedTarget = new TrackingWritableChannel();
                CodecTransferResult detected = CompressionFormats.decompress(
                        detectedSource,
                        detectedTarget
                );
                assertEquals(CONTENT.length, detected.outputBytes(), codec.format().name());
                assertArrayEquals(CONTENT, detectedTarget.bytes(), codec.format().name());
                assertTrue(detectedSource.isOpen(), codec.format().name());
                assertTrue(detectedTarget.isOpen(), codec.format().name());
                detectedSource.close();
                detectedTarget.close();
            }

            source.close();
            compressed.close();
            encoded.close();
            decoded.close();
        }
    }

    /// Verifies owning stream adapters support named and detected codec operations.
    @Test
    void opensNamedAndDetectedStreamAdapters() throws IOException {
        for (CompressionFormat format : CompressionFormats.installed()) {
            CompressionCodec<?> codec = format.defaultCodec();

            TrackingOutputStream compressed = new TrackingOutputStream();
            try (OutputStream output = CompressionFormats.newOutputStream(
                    codec.format().name(),
                    compressed,
                    ChannelOwnership.CLOSE
            )) {
                output.write(CONTENT);
            }
            assertTrue(compressed.closed(), codec.format().name());

            TrackingInputStream encoded = new TrackingInputStream(compressed.bytes());
            CompressionCodec<?> decoderCodec =
                    CodecContractConfigurations.decoderCodec(codec, CONTENT.length);
            try (InputStream input = decoderCodec.newInputStream(
                    encoded,
                    ChannelOwnership.CLOSE
            )) {
                assertArrayEquals(CONTENT, input.readAllBytes(), codec.format().name());
            }
            assertTrue(encoded.closed(), codec.format().name());

            if (codec.format().probeSize() > 0) {
                TrackingInputStream detectedSource = new TrackingInputStream(compressed.bytes());
                try (InputStream input = CompressionFormats.newInputStream(
                        detectedSource,
                        ChannelOwnership.CLOSE
                )) {
                    assertArrayEquals(CONTENT, input.readAllBytes(), codec.format().name());
                }
                assertTrue(detectedSource.closed(), codec.format().name());
            }
        }

        TrackingOutputStream unknownTarget = new TrackingOutputStream();
        assertThrows(
                IOException.class,
                () -> CompressionFormats.newOutputStream(
                        "missing",
                        unknownTarget,
                        ChannelOwnership.CLOSE
                )
        );
        assertTrue(unknownTarget.closed());

        TrackingInputStream unknownSource = new TrackingInputStream(new byte[]{1, 2, 3});
        assertThrows(
                IOException.class,
                () -> CompressionFormats.newInputStream(
                        "missing",
                        unknownSource,
                        ChannelOwnership.CLOSE
                )
        );
        assertTrue(unknownSource.closed());

        TrackingInputStream unrecognizedSource = new TrackingInputStream(new byte[]{1, 2, 3});
        assertThrows(
                IOException.class,
                () -> CompressionFormats.newInputStream(
                        unrecognizedSource,
                        ChannelOwnership.CLOSE
                )
        );
        assertTrue(unrecognizedSource.closed());
    }

    /// Verifies failed lookup and detection apply the requested ownership exactly once.
    @Test
    void appliesOwnershipAfterFactoryFailures() throws IOException {
        TrackingWritableChannel retainedTarget = new TrackingWritableChannel();
        IOException retainedEncodeFailure = assertThrows(
                IOException.class,
                () -> CompressionFormats.newWritableByteChannel("missing", retainedTarget)
        );
        assertEquals("Unknown compression format: missing", retainedEncodeFailure.getMessage());
        assertTrue(retainedTarget.isOpen());

        TrackingWritableChannel ownedTarget = new TrackingWritableChannel();
        assertThrows(
                IOException.class,
                () -> CompressionFormats.newWritableByteChannel(
                        "missing",
                        ownedTarget,
                        ChannelOwnership.CLOSE
                )
        );
        assertFalse(ownedTarget.isOpen());

        TrackingReadableChannel retainedSource = new TrackingReadableChannel(new byte[]{1, 2, 3, 4});
        IOException retainedDetectFailure = assertThrows(
                IOException.class,
                () -> CompressionFormats.newReadableByteChannel(retainedSource)
        );
        assertEquals("Unrecognized compression format", retainedDetectFailure.getMessage());
        assertTrue(retainedSource.isOpen());

        TrackingReadableChannel ownedSource = new TrackingReadableChannel(new byte[]{1, 2, 3, 4});
        assertThrows(
                IOException.class,
                () -> CompressionFormats.newReadableByteChannel(ownedSource, ChannelOwnership.CLOSE)
        );
        assertFalse(ownedSource.isOpen());

        retainedTarget.close();
        retainedSource.close();
    }

    /// Verifies setup failures close owned endpoints and preserve close failures as suppressed exceptions.
    @Test
    void closesOwnedChannelsAfterSetupFailure() throws IOException {
        TrackingReadableChannel source = new TrackingReadableChannel(new byte[0]);
        assertThrows(
                IllegalStateException.class,
                () -> CompressionFormats.newReadableByteChannel(
                        "ppmd",
                        source,
                        ChannelOwnership.CLOSE
                )
        );
        assertFalse(source.isOpen());

        FailingCloseWritableChannel failing = new FailingCloseWritableChannel();
        IOException failure = assertThrows(
                IOException.class,
                () -> CompressionFormats.newWritableByteChannel(
                        "missing",
                        failing,
                        ChannelOwnership.CLOSE
                )
        );
        assertEquals(1, failing.closeCount());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("channel close failed", failure.getSuppressed()[0].getMessage());
    }

    /// Encodes the shared content through one named codec.
    private static byte[] encode(String formatName) throws IOException {
        TrackingWritableChannel target = new TrackingWritableChannel();
        try (CompressingWritableByteChannel encoder = CompressionFormats.newWritableByteChannel(
                formatName,
                target,
                ChannelOwnership.CLOSE
        )) {
            writeAll(encoder, CONTENT);
        }
        return target.bytes();
    }

    /// Writes every source byte while requiring forward progress.
    private static void writeAll(WritableByteChannel target, byte[] bytes) throws IOException {
        ByteBuffer source = ByteBuffer.wrap(bytes);
        while (source.hasRemaining()) {
            if (target.write(source) == 0) {
                throw new IOException("Test channel write made no progress");
            }
        }
    }

    /// Reads every channel byte while requiring forward progress.
    private static byte[] readAll(ReadableByteChannel source) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteBuffer buffer = ByteBuffer.allocate(257);
        while (true) {
            int read = source.read(buffer);
            if (read < 0) {
                return output.toByteArray();
            }
            if (read == 0) {
                throw new IOException("Test channel read made no progress");
            }
            buffer.flip();
            byte[] chunk = new byte[buffer.remaining()];
            buffer.get(chunk);
            output.write(chunk);
            buffer.clear();
        }
    }

    /// Collects bytes while exposing whether the stream has been closed.
    @NotNullByDefault
    private static final class TrackingOutputStream extends ByteArrayOutputStream {
        /// Whether close has completed.
        private boolean closed;

        /// Closes this stream and records completion.
        @Override
        public void close() throws IOException {
            super.close();
            closed = true;
        }

        /// Returns a copy of collected bytes.
        private byte[] bytes() {
            return toByteArray();
        }

        /// Returns whether close has completed.
        private boolean closed() {
            return closed;
        }
    }

    /// Reads immutable bytes while exposing whether the stream has been closed.
    @NotNullByDefault
    private static final class TrackingInputStream extends ByteArrayInputStream {
        /// Whether close has completed.
        private boolean closed;

        /// Creates a stream over copied bytes.
        private TrackingInputStream(byte[] bytes) {
            super(bytes.clone());
        }

        /// Closes this stream and records completion.
        @Override
        public void close() throws IOException {
            super.close();
            closed = true;
        }

        /// Returns whether close has completed.
        private boolean closed() {
            return closed;
        }
    }

    /// Collects written bytes and exposes channel lifecycle state.
    @NotNullByDefault
    private static class TrackingWritableChannel implements WritableByteChannel {
        /// Collected bytes.
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        /// Whether this channel remains open.
        private boolean open = true;

        /// Writes every remaining source byte.
        @Override
        public int write(ByteBuffer source) throws IOException {
            ensureOpen();
            int count = source.remaining();
            byte[] chunk = new byte[count];
            source.get(chunk);
            output.write(chunk);
            return count;
        }

        /// Returns whether this channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this channel.
        @Override
        public void close() throws IOException {
            open = false;
        }

        /// Returns a copy of collected bytes.
        private byte[] bytes() {
            return output.toByteArray();
        }

        /// Requires this channel to remain open.
        private void ensureOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }

    /// Reads immutable bytes and exposes channel lifecycle state.
    @NotNullByDefault
    private static final class TrackingReadableChannel implements ReadableByteChannel {
        /// Immutable source bytes.
        private final byte @Unmodifiable [] bytes;

        /// Current read offset.
        private int offset;

        /// Whether this channel remains open.
        private boolean open = true;

        /// Creates a channel over copied bytes.
        private TrackingReadableChannel(byte[] bytes) {
            this.bytes = bytes.clone();
        }

        /// Reads available source bytes.
        @Override
        public int read(ByteBuffer target) throws IOException {
            ensureOpen();
            if (!target.hasRemaining()) {
                return 0;
            }
            if (offset == bytes.length) {
                return -1;
            }
            int count = Math.min(target.remaining(), bytes.length - offset);
            target.put(bytes, offset, count);
            offset += count;
            return count;
        }

        /// Returns whether this channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this channel.
        @Override
        public void close() throws IOException {
            open = false;
        }

        /// Requires this channel to remain open.
        private void ensureOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }

    /// Fails every close attempt while recording how many were made.
    @NotNullByDefault
    private static final class FailingCloseWritableChannel extends TrackingWritableChannel {
        /// Number of close attempts.
        private int closeCount;

        /// Reports a configured close failure.
        @Override
        public void close() throws IOException {
            closeCount++;
            throw new IOException("channel close failed");
        }

        /// Returns the number of close attempts.
        private int closeCount() {
            return closeCount;
        }
    }
}
