// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.CompressionFormats;
import org.glavo.arkivo.codec.EncodingOptions;
import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.SeekableEncodingOptions;
import org.glavo.arkivo.codec.zstd.ZstdCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies output failure isolation using every installed encoder and the indexed Zstandard writer.
@NotNullByDefault
final class CompressionOutputFailureTest {
    /// Reproducible source bytes shared without mutation by the format matrix.
    private static final byte @Unmodifiable [] CONTENT = content();

    /// Creates source data without storing binary fixtures in the repository.
    private static byte[] content() {
        byte[] bytes = new byte[16_384];
        new Random(742L).nextBytes(bytes);
        return bytes;
    }

    /// Supplies all format, buffer, ownership, endpoint, and partial-failure combinations.
    private static Stream<Arguments> failures() {
        return Stream.concat(CompressionFormats.installed().stream().map(CompressionFormat::name),
                        Stream.of("zstd-seekable"))
                .flatMap(format -> Stream.of(ResourceOwnership.values())
                        .flatMap(ownership -> Stream.of(false, true)
                                .flatMap(interruptible -> Stream.of(false, true)
                                        .flatMap(direct -> Stream.of(false, true)
                                                .map(partial -> Arguments.of(format, ownership, interruptible, direct, partial))))));
    }

    /// Fails the first physical write, including encoders that buffer the entire body until finish.
    @ParameterizedTest(name = "{0}, {1}, interruptible={2}, direct={3}, partial={4}")
    @MethodSource("failures")
    void outputFailureNeverEmitsMoreData(String format, ResourceOwnership ownership,
                                        boolean interruptible, boolean direct, boolean partial) throws IOException {
        Target target = interruptible ? new InterruptibleTarget(partial) : new Target(partial);
        CompressingWritableByteChannel channel = format.equals("zstd-seekable")
                ? ZstdCodec.DEFAULT.newSeekableWritableByteChannel(target,
                        SeekableEncodingOptions.ofMaximumFrameSize(4096), ownership)
                : CompressionFormats.require(format).defaultCodec().newWritableByteChannel(
                        target, EncodingOptions.ofSourceSize(CONTENT.length), ownership);
        ByteBuffer storage = direct ? ByteBuffer.allocateDirect(CONTENT.length + 4)
                : ByteBuffer.allocate(CONTENT.length + 4);
        storage.position(2).put(CONTENT).flip().position(2);
        @UnmodifiableView ByteBuffer source = storage.asReadOnlyBuffer();
        assertSame(target.failure, assertThrows(IOException.class, () -> {
            channel.write(source);
            channel.finish();
        }));
        assertFalse(channel.isOpen());
        assertEquals(source.position() - 2L, channel.inputBytes());
        assertEquals(target.bytes.size(), channel.outputBytes());
        byte[] incomplete = target.bytes.toByteArray();
        int writes = target.writes;
        assertThrows(IOException.class, () -> channel.write(ByteBuffer.wrap(new byte[]{1})));
        if (channel instanceof CompressingWritableByteChannel.Flushable flushable) {
            assertThrows(IOException.class, flushable::flush);
        }
        if (channel instanceof CompressingWritableByteChannel.Framed framed) {
            assertThrows(IOException.class, framed::finishFrame);
            assertThrows(IOException.class, framed::startFrame);
        }
        try {
            channel.close();
        } catch (IOException exception) {
            if (!format.equals("zstd-seekable")) {
                throw exception;
            }
            // The indexed wrapper reports that its failed data frame cannot be indexed.
        }
        channel.close();
        assertEquals(writes, target.writes);
        assertArrayEquals(incomplete, target.bytes.toByteArray());
        assertEquals(ownership == ResourceOwnership.BORROWED, target.isOpen());
        assertEquals(ownership == ResourceOwnership.OWNED ? 1 : 0, target.closeCalls);
    }

    /// Fails once and remains writable so unexpected cleanup output is observable.
    @NotNullByDefault
    private static class Target implements WritableByteChannel {
        /// Stable exception reported by the first target write.
        final IOException failure = new IOException("injected compressed output failure");
        /// Bytes actually accepted by the endpoint.
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        /// Whether the failed write consumes one byte first.
        private final boolean partial;
        /// Number of write attempts.
        int writes;
        /// Number of close attempts.
        int closeCalls;
        /// Whether the target has not been closed.
        private boolean open = true;

        /// Configures whether failure occurs before or after partial target progress.
        Target(boolean partial) {
            this.partial = partial;
        }

        /// Fails the first call and accepts all bytes from any later calls.
        @Override
        public int write(ByteBuffer source) throws IOException {
            writes++;
            if (writes == 1) {
                if (partial) {
                    bytes.write(source.get());
                }
                throw failure;
            }
            int count = source.remaining();
            while (source.hasRemaining()) {
                bytes.write(source.get());
            }
            return count;
        }

        /// Returns whether ownership cleanup has occurred.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Records ownership cleanup without changing the retained bytes.
        @Override
        public void close() {
            closeCalls++;
            open = false;
        }
    }

    /// Selects interruptible factory adapters with an immediately failing endpoint.
    @NotNullByDefault
    private static final class InterruptibleTarget extends Target implements InterruptibleChannel {
        /// Configures failure before or after target progress.
        InterruptibleTarget(boolean partial) {
            super(partial);
        }
    }
}
