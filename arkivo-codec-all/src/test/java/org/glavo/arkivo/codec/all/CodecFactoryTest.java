// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.all;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CodecOption;
import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CodecTransferResult;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionCodecs;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies unified codec context discovery and channel ownership.
@NotNullByDefault
final class CodecFactoryTest {
    /// Repetitive content used for every codec round trip.
    private static final byte @Unmodifiable [] CONTENT =
            "unified codec context factory\n".repeat(512).getBytes(StandardCharsets.UTF_8);

    /// Verifies named factories round-trip every installed bidirectional codec.
    @Test
    void roundTripsEveryCodecByName() throws IOException {
        for (CompressionCodec codec : CompressionCodecs.installed()) {
            if (!codec.canCompress() || !codec.canDecompress()) {
                continue;
            }

            TrackingWritableChannel target = new TrackingWritableChannel();
            try (CompressionEncoder encoder = CompressionCodecs.openEncoder(
                    codec.name(),
                    target,
                    CodecOptions.EMPTY,
                    ChannelOwnership.CLOSE
            )) {
                writeAll(encoder, CONTENT);
            }
            assertFalse(target.isOpen(), codec.name());

            TrackingReadableChannel source = new TrackingReadableChannel(target.bytes());
            try (CompressionDecoder decoder = CompressionCodecs.openDecoder(
                    codec.name(),
                    source,
                    CodecContractOptions.decoderOptions(codec, CONTENT.length),
                    ChannelOwnership.CLOSE
            )) {
                assertArrayEquals(CONTENT, readAll(decoder), codec.name());
            }
            assertFalse(source.isOpen(), codec.name());
        }
    }

    /// Verifies convenience overloads accept aliases and retain caller-owned channels.
    @Test
    void opensNamedAliasesWhileRetainingChannels() throws IOException {
        TrackingWritableChannel target = new TrackingWritableChannel();
        try (CompressionEncoder encoder = CompressionCodecs.openEncoder("bz2", target)) {
            writeAll(encoder, CONTENT);
        }
        assertTrue(target.isOpen());

        TrackingReadableChannel source = new TrackingReadableChannel(target.bytes());
        try (CompressionDecoder decoder = CompressionCodecs.openDecoder(
                "BZIP2",
                source,
                CodecOptions.EMPTY
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
        Set<String> signed = CompressionCodecs.installed()
                .stream()
                .filter(codec -> codec.probeSize() > 0)
                .map(CompressionCodec::name)
                .collect(Collectors.toUnmodifiableSet());
        assertEquals(expected, signed);

        for (CompressionCodec codec : CompressionCodecs.installed()) {
            if (codec.probeSize() == 0) {
                continue;
            }
            byte[] encoded = encode(codec.name());
            TrackingReadableChannel source = new TrackingReadableChannel(encoded);
            try (CompressionDecoder decoder = CompressionCodecs.openDecoder(
                    source,
                    ChannelOwnership.CLOSE
            )) {
                assertArrayEquals(CONTENT, readAll(decoder), codec.name());
            }
            assertFalse(source.isOpen(), codec.name());
        }
    }

    /// Verifies named channel transfers round-trip every installed codec without closing caller channels.
    @Test
    void transfersEveryCodecByName() throws IOException {
        for (CompressionCodec codec : CompressionCodecs.installed()) {
            if (!codec.canCompress() || !codec.canDecompress()) {
                continue;
            }
            TrackingReadableChannel source = new TrackingReadableChannel(CONTENT);
            TrackingWritableChannel compressed = new TrackingWritableChannel();
            CodecTransferResult compression = CompressionCodecs.compress(
                    codec.name(),
                    source,
                    compressed,
                    CodecOptions.EMPTY
            );
            assertEquals(CONTENT.length, compression.inputBytes(), codec.name());
            assertEquals(compressed.bytes().length, compression.outputBytes(), codec.name());
            assertTrue(source.isOpen(), codec.name());
            assertTrue(compressed.isOpen(), codec.name());

            TrackingReadableChannel encoded = new TrackingReadableChannel(compressed.bytes());
            TrackingWritableChannel decoded = new TrackingWritableChannel();
            CodecTransferResult decompression = CompressionCodecs.decompress(
                    codec.name(),
                    encoded,
                    decoded,
                    CodecContractOptions.decoderOptions(codec, CONTENT.length)
            );
            if (CodecContractOptions.requiresDecoderOptions(codec)) {
                assertTrue(decompression.inputBytes() <= compressed.bytes().length, codec.name());
            } else {
                assertEquals(compressed.bytes().length, decompression.inputBytes(), codec.name());
            }
            assertEquals(CONTENT.length, decompression.outputBytes(), codec.name());
            assertArrayEquals(CONTENT, decoded.bytes(), codec.name());
            assertTrue(encoded.isOpen(), codec.name());
            assertTrue(decoded.isOpen(), codec.name());

            if (codec.probeSize() > 0) {
                TrackingReadableChannel detectedSource =
                        new TrackingReadableChannel(compressed.bytes());
                TrackingWritableChannel detectedTarget = new TrackingWritableChannel();
                CodecTransferResult detected = CompressionCodecs.decompress(
                        detectedSource,
                        detectedTarget,
                        CodecOptions.EMPTY
                );
                assertEquals(CONTENT.length, detected.outputBytes(), codec.name());
                assertArrayEquals(CONTENT, detectedTarget.bytes(), codec.name());
                assertTrue(detectedSource.isOpen(), codec.name());
                assertTrue(detectedTarget.isOpen(), codec.name());
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
        for (CompressionCodec codec : CompressionCodecs.installed()) {
            if (!codec.canCompress() || !codec.canDecompress()) {
                continue;
            }
            TrackingOutputStream compressed = new TrackingOutputStream();
            try (OutputStream output = CompressionCodecs.compressTo(
                    codec.name(),
                    compressed,
                    CodecOptions.EMPTY
            )) {
                output.write(CONTENT);
            }
            assertTrue(compressed.closed(), codec.name());

            TrackingInputStream encoded = new TrackingInputStream(compressed.bytes());
            try (InputStream input = CompressionCodecs.decompressFrom(
                    codec.name(),
                    encoded,
                    CodecContractOptions.decoderOptions(codec, CONTENT.length)
            )) {
                assertArrayEquals(CONTENT, input.readAllBytes(), codec.name());
            }
            assertTrue(encoded.closed(), codec.name());

            if (codec.probeSize() > 0) {
                TrackingInputStream detectedSource = new TrackingInputStream(compressed.bytes());
                try (InputStream input = CompressionCodecs.decompressFrom(detectedSource)) {
                    assertArrayEquals(CONTENT, input.readAllBytes(), codec.name());
                }
                assertTrue(detectedSource.closed(), codec.name());
            }
        }

        TrackingOutputStream unknownTarget = new TrackingOutputStream();
        assertThrows(
                IOException.class,
                () -> CompressionCodecs.compressTo("missing", unknownTarget)
        );
        assertTrue(unknownTarget.closed());

        TrackingInputStream unknownSource = new TrackingInputStream(new byte[]{1, 2, 3});
        assertThrows(
                IOException.class,
                () -> CompressionCodecs.decompressFrom("missing", unknownSource)
        );
        assertTrue(unknownSource.closed());

        TrackingInputStream unrecognizedSource = new TrackingInputStream(new byte[]{1, 2, 3});
        assertThrows(
                IOException.class,
                () -> CompressionCodecs.decompressFrom(unrecognizedSource)
        );
        assertTrue(unrecognizedSource.closed());
    }

    /// Verifies failed lookup and detection apply the requested ownership exactly once.
    @Test
    void appliesOwnershipAfterFactoryFailures() throws IOException {
        TrackingWritableChannel retainedTarget = new TrackingWritableChannel();
        IOException retainedEncodeFailure = assertThrows(
                IOException.class,
                () -> CompressionCodecs.openEncoder("missing", retainedTarget)
        );
        assertEquals("Unknown compression codec: missing", retainedEncodeFailure.getMessage());
        assertTrue(retainedTarget.isOpen());

        TrackingWritableChannel ownedTarget = new TrackingWritableChannel();
        assertThrows(
                IOException.class,
                () -> CompressionCodecs.openEncoder(
                        "missing",
                        ownedTarget,
                        CodecOptions.EMPTY,
                        ChannelOwnership.CLOSE
                )
        );
        assertFalse(ownedTarget.isOpen());

        TrackingReadableChannel retainedSource = new TrackingReadableChannel(new byte[]{1, 2, 3, 4});
        IOException retainedDetectFailure = assertThrows(
                IOException.class,
                () -> CompressionCodecs.openDecoder(retainedSource)
        );
        assertEquals("Unrecognized compression codec", retainedDetectFailure.getMessage());
        assertTrue(retainedSource.isOpen());

        TrackingReadableChannel ownedSource = new TrackingReadableChannel(new byte[]{1, 2, 3, 4});
        assertThrows(
                IOException.class,
                () -> CompressionCodecs.openDecoder(ownedSource, ChannelOwnership.CLOSE)
        );
        assertFalse(ownedSource.isOpen());

        retainedTarget.close();
        retainedSource.close();
    }

    /// Verifies setup failures close owned endpoints and preserve close failures as suppressed exceptions.
    @Test
    void closesOwnedChannelsAfterSetupFailure() throws IOException {
        CodecOption<String> unsupported = CodecOption.of("test.unsupported", String.class);
        CodecOptions options = CodecOptions.builder().set(unsupported, "value").build();

        TrackingWritableChannel target = new TrackingWritableChannel();
        assertThrows(
                UnsupportedOperationException.class,
                () -> CompressionCodecs.openEncoder(
                        "gzip",
                        target,
                        options,
                        ChannelOwnership.CLOSE
                )
        );
        assertFalse(target.isOpen());

        TrackingReadableChannel source = new TrackingReadableChannel(encode("gzip"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> CompressionCodecs.openDecoder(source, options, ChannelOwnership.CLOSE)
        );
        assertFalse(source.isOpen());

        FailingCloseWritableChannel failing = new FailingCloseWritableChannel();
        IOException failure = assertThrows(
                IOException.class,
                () -> CompressionCodecs.openEncoder(
                        "missing",
                        failing,
                        CodecOptions.EMPTY,
                        ChannelOwnership.CLOSE
                )
        );
        assertEquals(1, failing.closeCount());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("channel close failed", failure.getSuppressed()[0].getMessage());
    }

    /// Encodes the shared content through one named codec.
    private static byte[] encode(String codecName) throws IOException {
        TrackingWritableChannel target = new TrackingWritableChannel();
        try (CompressionEncoder encoder = CompressionCodecs.openEncoder(
                codecName,
                target,
                CodecOptions.EMPTY,
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
