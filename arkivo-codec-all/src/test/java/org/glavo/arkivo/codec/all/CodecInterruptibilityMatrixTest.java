// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.all;

import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.CompressionFormats;
import org.glavo.arkivo.codec.CompressionProbeResult;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.ResourceOwnership;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies public codec factories preserve endpoint interruptibility without changing codec behavior.
@NotNullByDefault
final class CodecInterruptibilityMatrixTest {
    /// Deterministic content encoded by every installed codec.
    private static final byte @Unmodifiable [] CONTENT = (
            "interruptible codec matrix 0123456789abcdef;".repeat(257)
    ).getBytes(StandardCharsets.UTF_8);

    /// Verifies every codec selects interruptible wrappers only for interruptible endpoints.
    @Test
    void preservesInterruptibilityAcrossEveryInstalledCodec(@TempDir Path directory) throws IOException {
        int index = 0;
        for (CompressionFormat format : CompressionFormats.installed()) {
            CompressionCodec<?> codec = format.defaultCodec();
            String context = format.name();
            Path compressedPath = directory.resolve("encoded-" + index++ + ".bin");

            try (FileChannel target = FileChannel.open(
                    compressedPath,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            )) {
                try (CompressingWritableByteChannel encoder = codec.newWritableByteChannel(target)) {
                    assertInstanceOf(InterruptibleChannel.class, encoder, context);
                    writeAll(encoder, CONTENT, context);
                }
                assertTrue(target.isOpen(), context);
            }

            byte[] compressed = Files.readAllBytes(compressedPath);
            CompressionCodec<?> decoderCodec = CodecContractConfigurations.decoderCodec(codec, CONTENT.length)
                    .withMaximumOutputSize(CONTENT.length);
            try (FileChannel source = FileChannel.open(compressedPath, StandardOpenOption.READ)) {
                try (DecompressingReadableByteChannel decoder = decoderCodec.newReadableByteChannel(source)) {
                    assertInstanceOf(InterruptibleChannel.class, decoder, context);
                    assertArrayEquals(CONTENT, readAll(decoder, context), context);
                }
                assertTrue(source.isOpen(), context);
            }

            PlainWritableByteChannel plainTarget = new PlainWritableByteChannel();
            try (CompressingWritableByteChannel encoder = codec.newWritableByteChannel(plainTarget)) {
                assertFalse(encoder instanceof InterruptibleChannel, context);
                writeAll(encoder, CONTENT, context);
            }
            assertTrue(plainTarget.isOpen(), context);

            PlainReadableByteChannel plainSource = new PlainReadableByteChannel(plainTarget.toByteArray());
            try (DecompressingReadableByteChannel decoder = decoderCodec.newReadableByteChannel(plainSource)) {
                assertFalse(decoder instanceof InterruptibleChannel, context);
                assertArrayEquals(CONTENT, readAll(decoder, context), context);
            }
            assertTrue(plainSource.isOpen(), context);

            assertTrue(compressed.length > 0, context);
        }
    }

    /// Verifies every concrete codec adapter enforces NIO pre-interruption closure semantics.
    @Test
    void honorsPreInterruptionAcrossEveryInstalledCodec(@TempDir Path directory) throws IOException {
        int index = 0;
        for (CompressionFormat format : CompressionFormats.installed()) {
            CompressionCodec<?> codec = format.defaultCodec();
            String context = format.name();
            Path encodedPath = directory.resolve("interrupted-" + index++ + ".bin");

            try (FileChannel target = FileChannel.open(
                    encodedPath,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            )) {
                CompressingWritableByteChannel encoder = codec.newWritableByteChannel(target);
                try {
                    Thread.currentThread().interrupt();
                    assertThrows(
                            ClosedByInterruptException.class,
                            () -> encoder.write(ByteBuffer.wrap(new byte[]{1})),
                            context
                    );
                    assertTrue(Thread.currentThread().isInterrupted(), context);
                    assertFalse(encoder.isOpen(), context);
                    assertFalse(target.isOpen(), context);
                } finally {
                    Thread.interrupted();
                    encoder.close();
                }
            }

            ByteBuffer encodedBuffer = codec.compress(ByteBuffer.wrap(CONTENT));
            byte[] encoded = new byte[encodedBuffer.remaining()];
            encodedBuffer.get(encoded);
            Files.write(
                    encodedPath,
                    encoded,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );

            CompressionCodec<?> decoderCodec = CodecContractConfigurations.decoderCodec(codec, CONTENT.length)
                    .withMaximumOutputSize(CONTENT.length);
            try (FileChannel source = FileChannel.open(encodedPath, StandardOpenOption.READ)) {
                DecompressingReadableByteChannel decoder = decoderCodec.newReadableByteChannel(source);
                try {
                    Thread.currentThread().interrupt();
                    assertThrows(
                            ClosedByInterruptException.class,
                            () -> decoder.read(ByteBuffer.allocate(1)),
                            context
                    );
                    assertTrue(Thread.currentThread().isInterrupted(), context);
                    assertFalse(decoder.isOpen(), context);
                    assertFalse(source.isOpen(), context);
                } finally {
                    Thread.interrupted();
                    decoder.close();
                }
            }
        }
    }

    /// Verifies probing and detected decoding preserve a real file channel's interruption capability.
    @Test
    void preservesInterruptibilityThroughProbeReplayAndDetectedDecoding(@TempDir Path directory) throws IOException {
        CompressionCodec<?> gzip = CompressionFormats.require("gzip").defaultCodec();
        ByteBuffer encodedBuffer = gzip.compress(ByteBuffer.wrap(CONTENT));
        byte[] encoded = new byte[encodedBuffer.remaining()];
        encodedBuffer.get(encoded);
        Path compressedPath = directory.resolve("detected.gz");
        Files.write(compressedPath, encoded, StandardOpenOption.CREATE_NEW);

        try (FileChannel source = FileChannel.open(compressedPath, StandardOpenOption.READ)) {
            CompressionProbeResult probe = CompressionFormats.probe(source, ResourceOwnership.BORROWED);
            CompressionFormat detected = assertInstanceOf(CompressionFormat.class, probe.format());
            assertEquals("gzip", detected.name());
            ReadableByteChannel replay = probe.takeChannel();
            assertInstanceOf(InterruptibleChannel.class, replay);
            assertArrayEquals(encoded, readAll(replay, "probe replay"));
            replay.close();
            probe.close();
            assertTrue(source.isOpen());

            source.position(0L);
            try (DecompressingReadableByteChannel decoder = CompressionFormats.newReadableByteChannel(
                    source,
                    ResourceOwnership.BORROWED
            )) {
                assertInstanceOf(InterruptibleChannel.class, decoder);
                assertArrayEquals(CONTENT, readAll(decoder, "detected gzip"));
            }
            assertTrue(source.isOpen());
        }

        PlainReadableByteChannel plainSource = new PlainReadableByteChannel(encoded);
        CompressionProbeResult plainProbe = CompressionFormats.probe(
                plainSource,
                ResourceOwnership.BORROWED
        );
        ReadableByteChannel plainReplay = plainProbe.takeChannel();
        assertFalse(plainReplay instanceof InterruptibleChannel);
        assertArrayEquals(encoded, readAll(plainReplay, "plain probe replay"));
        plainReplay.close();
        plainProbe.close();
        assertTrue(plainSource.isOpen());

        PlainReadableByteChannel detectedPlainSource = new PlainReadableByteChannel(encoded);
        try (DecompressingReadableByteChannel decoder = CompressionFormats.newReadableByteChannel(
                detectedPlainSource,
                ResourceOwnership.BORROWED
        )) {
            assertFalse(decoder instanceof InterruptibleChannel);
            assertArrayEquals(CONTENT, readAll(decoder, "plain detected gzip"));
        }
        assertTrue(detectedPlainSource.isOpen());
    }

    /// Writes every byte while requiring forward progress from the compressing channel.
    private static void writeAll(
            WritableByteChannel target,
            byte @Unmodifiable [] content,
            String context
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(content, "content");
        ByteBuffer source = ByteBuffer.allocateDirect(content.length);
        source.put(content).flip();
        while (source.hasRemaining()) {
            assertTrue(target.write(source) > 0, context);
        }
    }

    /// Reads all bytes while requiring forward progress from the decompressing channel.
    private static byte @Unmodifiable [] readAll(ReadableByteChannel source, String context) throws IOException {
        Objects.requireNonNull(source, "source");
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        ByteBuffer target = ByteBuffer.allocateDirect(37);
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

    /// Collects bytes without implementing the optional interruption marker.
    @NotNullByDefault
    private static final class PlainWritableByteChannel implements WritableByteChannel {
        /// Collected bytes.
        private final ByteArrayOutputStream content = new ByteArrayOutputStream();

        /// Whether this endpoint remains open.
        private boolean open = true;

        /// Writes all remaining source bytes to the in-memory target.
        @Override
        public int write(ByteBuffer source) throws IOException {
            Objects.requireNonNull(source, "source");
            if (!open) {
                throw new ClosedChannelException();
            }
            int count = source.remaining();
            byte[] bytes = new byte[count];
            source.get(bytes);
            content.writeBytes(bytes);
            return count;
        }

        /// Returns whether this endpoint remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this endpoint.
        @Override
        public void close() {
            open = false;
        }

        /// Returns a private copy of the collected bytes.
        private byte @Unmodifiable [] toByteArray() {
            return content.toByteArray();
        }
    }

    /// Supplies immutable bytes without implementing the optional interruption marker.
    @NotNullByDefault
    private static final class PlainReadableByteChannel implements ReadableByteChannel {
        /// Read-only view of the remaining source bytes.
        private final @UnmodifiableView ByteBuffer content;

        /// Whether this endpoint remains open.
        private boolean open = true;

        /// Creates a source over a defensive copy of the supplied bytes.
        private PlainReadableByteChannel(byte[] content) {
            this.content = ByteBuffer.wrap(Objects.requireNonNull(content, "content").clone())
                    .asReadOnlyBuffer();
        }

        /// Reads available bytes into the target buffer.
        @Override
        public int read(ByteBuffer target) throws IOException {
            Objects.requireNonNull(target, "target");
            if (!open) {
                throw new ClosedChannelException();
            }
            if (!target.hasRemaining()) {
                return 0;
            }
            if (!content.hasRemaining()) {
                return -1;
            }

            int count = Math.min(content.remaining(), target.remaining());
            @UnmodifiableView ByteBuffer chunk = content.duplicate();
            chunk.limit(chunk.position() + count);
            target.put(chunk);
            content.position(content.position() + count);
            return count;
        }

        /// Returns whether this endpoint remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this endpoint.
        @Override
        public void close() {
            open = false;
        }
    }
}
