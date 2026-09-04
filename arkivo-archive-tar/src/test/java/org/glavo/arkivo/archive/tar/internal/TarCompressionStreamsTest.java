// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar.internal;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.EncodingOptions;
import org.glavo.arkivo.codec.deflate.DeflateCodec;
import org.glavo.arkivo.codec.zstd.ZstdCodec;
import org.glavo.arkivo.codec.zstd.ZstdFrameFormat;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies TAR compression wrapper limits, endpoint identity, and setup-failure ownership.
@NotNullByDefault
final class TarCompressionStreamsTest {
    /// Temporary directory used to inspect generated seekable Zstandard output.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies archive limits intersect codec limits without relaxing either finite bound.
    @Test
    void intersectsArchiveAndCodecDecoderLimits() {
        ArchiveReadLimits archiveLimits = ArchiveReadLimits.builder()
                .maximumCompressionWindowSize(300L)
                .maximumDecoderMemorySize(400L)
                .build();
        CompressionCodec<?> archiveBounded = TarCompressionStreams.withReadLimits(
                DeflateCodec.DEFAULT,
                archiveLimits
        );
        assertEquals(300L, archiveBounded.maximumWindowSize());
        assertEquals(400L, archiveBounded.maximumMemorySize());

        CompressionCodec<?> codecBounded = TarCompressionStreams.withReadLimits(
                DeflateCodec.DEFAULT
                        .withMaximumWindowSize(200L)
                        .withMaximumMemorySize(500L),
                archiveLimits
        );
        assertEquals(200L, codecBounded.maximumWindowSize());
        assertEquals(400L, codecBounded.maximumMemorySize());

        CompressionCodec<?> codecOnly = TarCompressionStreams.withReadLimits(
                DeflateCodec.DEFAULT
                        .withMaximumWindowSize(200L)
                        .withMaximumMemorySize(500L),
                ArchiveReadLimits.UNLIMITED
        );
        assertEquals(200L, codecOnly.maximumWindowSize());
        assertEquals(500L, codecOnly.maximumMemorySize());

        CompressionCodec<?> unrestricted = TarCompressionStreams.withReadLimits(
                DeflateCodec.DEFAULT,
                ArchiveReadLimits.UNLIMITED
        );
        assertEquals(CompressionCodec.UNLIMITED_SIZE, unrestricted.maximumWindowSize());
        assertEquals(CompressionCodec.UNLIMITED_SIZE, unrestricted.maximumMemorySize());
    }

    /// Verifies uncompressed wrappers return every validated endpoint without adapting or closing it.
    @Test
    void preservesUncompressedEndpointIdentity() throws IOException {
        InputStream input = new ByteArrayInputStream(new byte[0]);
        OutputStream output = new ByteArrayOutputStream();
        ReadableByteChannel readable = Channels.newChannel(new ByteArrayInputStream(new byte[0]));
        WritableByteChannel writable = Channels.newChannel(new ByteArrayOutputStream());

        assertSame(input, TarCompressionStreams.openArchiveInput(input, null, ArchiveReadLimits.UNLIMITED));
        assertSame(output, TarCompressionStreams.openArchiveOutput(output, null));
        assertSame(readable, TarCompressionStreams.openArchiveInput(
                readable,
                null,
                ArchiveReadLimits.UNLIMITED
        ));
        assertSame(writable, TarCompressionStreams.openArchiveOutput(writable, null));
        assertTrue(readable.isOpen());
        assertTrue(writable.isOpen());

        input.close();
        output.close();
        readable.close();
        writable.close();
    }

    /// Verifies decoder setup failures retain their identity while failed source closure is retried once.
    @Test
    void closesInputEndpointsAfterDecoderSetupFailure() {
        IOException streamFailure = new IOException("stream decoder setup failure");
        RetryCloseInput streamSource = new RetryCloseInput("stream source");
        IOException thrownStreamFailure = assertThrows(
                IOException.class,
                () -> TarCompressionStreams.openArchiveInput(
                        (InputStream) streamSource,
                        new FailingCodec(streamFailure),
                        ArchiveReadLimits.UNLIMITED
                )
        );
        assertSame(streamFailure, thrownStreamFailure);
        assertRetriedClose(streamFailure, streamSource.closeCalls(), streamSource.isOpen(), "stream source");

        IllegalStateException channelFailure = new IllegalStateException("channel decoder setup failure");
        RetryCloseInput channelSource = new RetryCloseInput("channel source");
        IllegalStateException thrownChannelFailure = assertThrows(
                IllegalStateException.class,
                () -> TarCompressionStreams.openArchiveInput(
                        (ReadableByteChannel) channelSource,
                        new FailingCodec(channelFailure),
                        ArchiveReadLimits.UNLIMITED
                )
        );
        assertSame(channelFailure, thrownChannelFailure);
        assertRetriedClose(channelFailure, channelSource.closeCalls(), channelSource.isOpen(), "channel source");
    }

    /// Verifies encoder setup failures retain their identity while failed target closure is retried once.
    @Test
    void closesOutputEndpointsAfterEncoderSetupFailure() {
        AssertionError streamFailure = new AssertionError("stream encoder setup failure");
        RetryCloseOutput streamTarget = new RetryCloseOutput("stream target");
        AssertionError thrownStreamFailure = assertThrows(
                AssertionError.class,
                () -> TarCompressionStreams.openArchiveOutput(
                        (OutputStream) streamTarget,
                        new FailingCodec(streamFailure)
                )
        );
        assertSame(streamFailure, thrownStreamFailure);
        assertRetriedClose(streamFailure, streamTarget.closeCalls(), streamTarget.isOpen(), "stream target");

        IOException channelFailure = new IOException("channel encoder setup failure");
        RetryCloseOutput channelTarget = new RetryCloseOutput("channel target");
        IOException thrownChannelFailure = assertThrows(
                IOException.class,
                () -> TarCompressionStreams.openArchiveOutput(
                        (WritableByteChannel) channelTarget,
                        new FailingCodec(channelFailure)
                )
        );
        assertSame(channelFailure, thrownChannelFailure);
        assertRetriedClose(channelFailure, channelTarget.closeCalls(), channelTarget.isOpen(), "channel target");
    }

    /// Verifies a second endpoint-close failure is retained and leaves ownership available for a later retry.
    @Test
    void retainsRepeatedCloseFailuresDuringSetupCleanup() throws IOException {
        IOException setupFailure = new IOException("encoder setup failure");
        RetryCloseOutput target = new RetryCloseOutput("persistent target", 2);

        IOException thrown = assertThrows(
                IOException.class,
                () -> TarCompressionStreams.openArchiveOutput(
                        (WritableByteChannel) target,
                        new FailingCodec(setupFailure)
                )
        );

        assertSame(setupFailure, thrown);
        assertEquals(2, target.closeCalls());
        assertTrue(target.isOpen());
        assertEquals(2, thrown.getSuppressed().length);
        assertEquals("persistent target close failure", thrown.getSuppressed()[0].getMessage());
        assertEquals("persistent target close failure", thrown.getSuppressed()[1].getMessage());

        target.close();
        assertFalse(target.isOpen());
        assertEquals(3, target.closeCalls());
    }

    /// Verifies one setup failure repeated by owned-target cleanup retains its original identity.
    @Test
    void preservesSharedSetupAndCleanupFailure() {
        IOException failure = new IOException("shared failure");
        RetryCloseOutput target = new RetryCloseOutput("shared target", 2, failure);

        IOException exception = assertThrows(
                IOException.class,
                () -> TarCompressionStreams.openArchiveOutput(
                        (WritableByteChannel) target,
                        new FailingCodec(failure)
                )
        );

        assertSame(failure, exception);
        assertEquals(0, exception.getSuppressed().length);
        assertEquals(2, target.closeCalls());
        assertTrue(target.isOpen());
    }

    /// Verifies supported Zstandard frames use indexed output while magicless frames use ordinary channel encoding.
    @Test
    void selectsSeekableChannelOutputByCodecConfiguration() throws IOException {
        byte[] content = "tar compression channel selection".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        byte[] seekable = encodeThroughChannel(ZstdCodec.DEFAULT, content);
        Path seekablePath = Files.write(temporaryDirectory.resolve("seekable.zst"), seekable);
        try (SeekableByteChannel source = Files.newByteChannel(seekablePath, StandardOpenOption.READ)) {
            assertNotNull(ZstdCodec.DEFAULT.readIndex(source));
        }
        assertArrayEquals(content, decode(ZstdCodec.DEFAULT, seekable, content.length));

        ZstdCodec magiclessCodec = ZstdCodec.DEFAULT.withFrameFormat(ZstdFrameFormat.MAGICLESS);
        byte[] magicless = encodeThroughChannel(magiclessCodec, content);
        assertArrayEquals(content, decode(magiclessCodec, magicless, content.length));
    }

    /// Verifies wrapper helpers reject null mandatory arguments before transferring endpoint ownership.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void validatesArguments() {
        InputStream input = new ByteArrayInputStream(new byte[0]);
        ReadableByteChannel readable = Channels.newChannel(new ByteArrayInputStream(new byte[0]));
        WritableByteChannel writable = Channels.newChannel(new ByteArrayOutputStream());

        assertThrows(
                NullPointerException.class,
                () -> TarCompressionStreams.openArchiveInput(
                        (InputStream) null,
                        null,
                        ArchiveReadLimits.UNLIMITED
                )
        );
        assertThrows(
                NullPointerException.class,
                () -> TarCompressionStreams.openArchiveInput(input, null, null)
        );
        assertThrows(
                NullPointerException.class,
                () -> TarCompressionStreams.openArchiveInput(
                        (ReadableByteChannel) null,
                        null,
                        ArchiveReadLimits.UNLIMITED
                )
        );
        assertThrows(
                NullPointerException.class,
                () -> TarCompressionStreams.openArchiveInput(readable, null, null)
        );
        assertThrows(
                NullPointerException.class,
                () -> TarCompressionStreams.openArchiveOutput((OutputStream) null, null)
        );
        assertThrows(
                NullPointerException.class,
                () -> TarCompressionStreams.openArchiveOutput((WritableByteChannel) null, null)
        );
        assertThrows(NullPointerException.class, () -> TarCompressionStreams.withReadLimits(null, ArchiveReadLimits.UNLIMITED));
        assertThrows(NullPointerException.class, () -> TarCompressionStreams.withReadLimits(DeflateCodec.DEFAULT, null));

        assertTrue(readable.isOpen());
        assertTrue(writable.isOpen());
    }

    /// Verifies a setup failure contains one close failure and the second close attempt completed ownership cleanup.
    private static void assertRetriedClose(
            Throwable setupFailure,
            int closeCalls,
            boolean endpointOpen,
            String endpointName
    ) {
        assertEquals(2, closeCalls);
        assertFalse(endpointOpen);
        assertEquals(1, setupFailure.getSuppressed().length);
        assertEquals(endpointName + " close failure", setupFailure.getSuppressed()[0].getMessage());
    }

    /// Encodes one byte array through the TAR channel wrapper and verifies ownership closes the target channel.
    private static byte[] encodeThroughChannel(CompressionCodec<?> codec, byte[] content) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        WritableByteChannel target = Channels.newChannel(encoded);
        try (WritableByteChannel output = TarCompressionStreams.openArchiveOutput(target, codec)) {
            ByteBuffer source = ByteBuffer.wrap(content);
            while (source.hasRemaining()) {
                int count = output.write(source);
                if (count == 0) {
                    throw new AssertionError("TAR compression channel made no progress");
                }
            }
        }
        assertFalse(target.isOpen());
        return encoded.toByteArray();
    }

    /// Decodes one complete byte array with a finite output bound.
    private static byte[] decode(CompressionCodec<?> codec, byte[] encoded, int expectedSize) throws IOException {
        ByteBuffer decoded = codec.withMaximumOutputSize(expectedSize).decompress(ByteBuffer.wrap(encoded));
        byte[] content = new byte[decoded.remaining()];
        decoded.get(content);
        return content;
    }

    /// Implements a codec whose engine factories report one caller-supplied setup failure.
    ///
    /// @param maximumOutputSize configured decoded-output limit
    /// @param maximumWindowSize configured history-window limit
    /// @param maximumMemorySize configured decoder-memory limit
    /// @param failure failure reported by both engine factories
    @NotNullByDefault
    private record FailingCodec(
            long maximumOutputSize,
            long maximumWindowSize,
            long maximumMemorySize,
            Throwable failure
    ) implements CompressionCodec<FailingCodec> {
        /// Creates an unrestricted failing codec.
        private FailingCodec(Throwable failure) {
            this(UNLIMITED_SIZE, UNLIMITED_SIZE, UNLIMITED_SIZE, failure);
        }

        /// Validates the configured limits and setup failure.
        private FailingCodec {
            requireLimit(maximumOutputSize, "maximumOutputSize");
            requireLimit(maximumWindowSize, "maximumWindowSize");
            requireLimit(maximumMemorySize, "maximumMemorySize");
            Objects.requireNonNull(failure, "failure");
            if (!(failure instanceof IOException)
                    && !(failure instanceof RuntimeException)
                    && !(failure instanceof Error)) {
                throw new IllegalArgumentException("failure must be an IOException, RuntimeException, or Error");
            }
        }

        /// Returns this codec with the requested decoded-output limit.
        @Override
        public FailingCodec withMaximumOutputSize(long value) {
            requireLimit(value, "maximumOutputSize");
            return value == maximumOutputSize
                    ? this
                    : new FailingCodec(value, maximumWindowSize, maximumMemorySize, failure);
        }

        /// Returns this codec with the requested history-window limit.
        @Override
        public FailingCodec withMaximumWindowSize(long value) {
            requireLimit(value, "maximumWindowSize");
            return value == maximumWindowSize
                    ? this
                    : new FailingCodec(maximumOutputSize, value, maximumMemorySize, failure);
        }

        /// Returns this codec with the requested decoder-memory limit.
        @Override
        public FailingCodec withMaximumMemorySize(long value) {
            requireLimit(value, "maximumMemorySize");
            return value == maximumMemorySize
                    ? this
                    : new FailingCodec(maximumOutputSize, maximumWindowSize, value, failure);
        }

        /// Returns the raw DEFLATE format as inert metadata for this test codec.
        @Override
        public CompressionFormat format() {
            return DeflateCodec.DEFAULT.format();
        }

        /// Reports the configured failure instead of creating an encoder.
        @Override
        public CompressionEncoder newEncoder(EncodingOptions options) throws IOException {
            Objects.requireNonNull(options, "options");
            return fail();
        }

        /// Reports the configured failure instead of creating a decoder.
        @Override
        public CompressionDecoder newDecoder() throws IOException {
            return fail();
        }

        /// Reports the configured checked or unchecked failure with its original identity.
        private <T> T fail() throws IOException {
            if (failure instanceof IOException exception) {
                throw exception;
            }
            if (failure instanceof RuntimeException exception) {
                throw exception;
            }
            throw (Error) failure;
        }

        /// Requires one codec limit to be unrestricted or non-negative.
        private static void requireLimit(long value, String name) {
            if (value < UNLIMITED_SIZE) {
                throw new IllegalArgumentException(name + " must be non-negative or UNLIMITED_SIZE");
            }
        }
    }

    /// Provides a readable stream and channel whose first close attempt fails.
    @NotNullByDefault
    private static final class RetryCloseInput extends InputStream implements ReadableByteChannel {
        /// Endpoint name used in the deterministic close failure.
        private final String name;

        /// Number of close calls received.
        private int closeCalls;

        /// Whether this endpoint remains open.
        private boolean open = true;

        /// Creates an open endpoint with one scheduled close failure.
        private RetryCloseInput(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        /// Reports physical end of input while open.
        @Override
        public int read() throws IOException {
            requireOpen();
            return -1;
        }

        /// Reports physical end of input, or zero for an empty destination, while open.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            Objects.requireNonNull(destination, "destination");
            requireOpen();
            return destination.hasRemaining() ? -1 : 0;
        }

        /// Returns whether the second close attempt has completed.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Fails the first close attempt and completes the second.
        @Override
        public void close() throws IOException {
            closeCalls++;
            if (closeCalls == 1) {
                throw new IOException(name + " close failure");
            }
            open = false;
        }

        /// Returns the number of close calls received.
        private int closeCalls() {
            return closeCalls;
        }

        /// Requires this endpoint to remain open.
        private void requireOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }

    /// Provides a writable stream and channel whose first close attempt fails.
    @NotNullByDefault
    private static final class RetryCloseOutput extends OutputStream implements WritableByteChannel {
        /// Endpoint name used in the deterministic close failure.
        private final String name;

        /// Number of close calls received.
        private int closeCalls;

        /// Number of future close calls that must fail.
        private int closeFailuresRemaining;

        /// The optional failure reused by each scheduled close failure.
        private final @Nullable IOException closeFailure;

        /// Whether this endpoint remains open.
        private boolean open = true;

        /// Creates an open endpoint with one scheduled close failure.
        private RetryCloseOutput(String name) {
            this(name, 1, null);
        }

        /// Creates an open endpoint with the requested number of scheduled close failures.
        private RetryCloseOutput(String name, int closeFailuresRemaining) {
            this(name, closeFailuresRemaining, null);
        }

        /// Creates an open endpoint with scheduled close failures that optionally reuse one exception.
        private RetryCloseOutput(
                String name,
                int closeFailuresRemaining,
                @Nullable IOException closeFailure
        ) {
            this.name = Objects.requireNonNull(name, "name");
            if (closeFailuresRemaining < 0) {
                throw new IllegalArgumentException("closeFailuresRemaining must not be negative");
            }
            this.closeFailuresRemaining = closeFailuresRemaining;
            this.closeFailure = closeFailure;
        }

        /// Accepts one byte while open.
        @Override
        public void write(int value) throws IOException {
            requireOpen();
        }

        /// Consumes every source byte while open.
        @Override
        public int write(ByteBuffer source) throws IOException {
            Objects.requireNonNull(source, "source");
            requireOpen();
            int count = source.remaining();
            source.position(source.limit());
            return count;
        }

        /// Returns whether the second close attempt has completed.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Fails the first close attempt and completes the second.
        @Override
        public void close() throws IOException {
            closeCalls++;
            if (closeFailuresRemaining > 0) {
                closeFailuresRemaining--;
                if (closeFailure != null) {
                    throw closeFailure;
                }
                throw new IOException(name + " close failure");
            }
            open = false;
        }

        /// Returns the number of close calls received.
        private int closeCalls() {
            return closeCalls;
        }

        /// Requires this endpoint to remain open.
        private void requireOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }
}
