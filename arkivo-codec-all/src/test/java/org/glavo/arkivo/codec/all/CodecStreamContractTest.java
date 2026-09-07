// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.all;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.CompressionFormats;
import org.glavo.arkivo.codec.DecompressionOutputLimitException;
import org.glavo.arkivo.codec.EncodingOptions;
import org.glavo.arkivo.codec.ResourceOwnership;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies Java stream contracts across the default codec of every installed format.
@NotNullByDefault
final class CodecStreamContractTest {
    /// Deterministic input spanning codec and stream adapter staging boundaries.
    private static final byte @Unmodifiable [] CONTENT = createContent();

    /// Verifies flush and close publish encoded bytes without closing a borrowed buffered target.
    @Test
    void flushesAndFinalizesBorrowedBufferedTargetsAcrossAllCodecs() throws IOException {
        for (CompressionFormat format : CompressionFormats.installed()) {
            CompressionCodec<?> codec = format.defaultCodec();
            ByteArrayOutputStream encodedBytes = new ByteArrayOutputStream();
            TrackingBufferedOutputStream bufferedTarget = new TrackingBufferedOutputStream(encodedBytes);
            OutputStream encoder = codec.newOutputStream(
                    bufferedTarget,
                    EncodingOptions.ofSourceSize(CONTENT.length),
                    ResourceOwnership.BORROWED
            );

            encoder.write(CONTENT, 0, 97);
            encoder.flush();
            assertEquals(1, bufferedTarget.flushCount(), format.name());
            encoder.write(CONTENT, 97, CONTENT.length - 97);
            encoder.close();

            assertEquals(2, bufferedTarget.flushCount(), format.name());
            assertFalse(bufferedTarget.closed(), format.name());
            assertThrows(IOException.class, () -> encoder.write(0), format.name());

            CompressionCodec<?> decoderCodec = CodecContractConfigurations
                    .decoderCodec(codec, CONTENT.length)
                    .withMaximumOutputSize(CONTENT.length);
            assertArrayEquals(
                    CONTENT,
                    bufferBytes(decoderCodec.decompress(ByteBuffer.wrap(encodedBytes.toByteArray()))),
                    format.name()
            );

            bufferedTarget.close();
            assertTrue(bufferedTarget.closed(), format.name());
        }
    }

    /// Verifies named stream factories also publish final bytes to borrowed buffered targets.
    @Test
    void finalizesBorrowedBufferedTargetsThroughNamedFactories() throws IOException {
        for (CompressionFormat format : CompressionFormats.installed()) {
            CompressionCodec<?> codec = format.defaultCodec();
            ByteArrayOutputStream encodedBytes = new ByteArrayOutputStream();
            TrackingBufferedOutputStream bufferedTarget = new TrackingBufferedOutputStream(encodedBytes);
            try (OutputStream encoder = CompressionFormats.newOutputStream(
                    format.name(),
                    bufferedTarget,
                    EncodingOptions.ofSourceSize(CONTENT.length),
                    ResourceOwnership.BORROWED
            )) {
                encoder.write(CONTENT);
            }

            assertEquals(1, bufferedTarget.flushCount(), format.name());
            assertFalse(bufferedTarget.closed(), format.name());
            CompressionCodec<?> decoderCodec = CodecContractConfigurations
                    .decoderCodec(codec, CONTENT.length)
                    .withMaximumOutputSize(CONTENT.length);
            assertArrayEquals(
                    CONTENT,
                    bufferBytes(decoderCodec.decompress(ByteBuffer.wrap(encodedBytes.toByteArray()))),
                    format.name()
            );
            bufferedTarget.close();
        }
    }

    /// Verifies mixed single-byte, ranged, skipping, bulk, and transfer reads through every codec stream.
    @Test
    void supportsMixedInputStreamOperationsAcrossAllCodecs() throws IOException {
        for (CompressionFormat format : CompressionFormats.installed()) {
            CompressionCodec<?> codec = format.defaultCodec();
            byte[] encoded = encode(codec);
            TrackingInputStream encodedSource = new TrackingInputStream(encoded);
            CompressionCodec<?> decoderCodec = CodecContractConfigurations
                    .decoderCodec(codec, CONTENT.length)
                    .withMaximumOutputSize(CONTENT.length);
            InputStream decoder = decoderCodec.newInputStream(encodedSource, ResourceOwnership.BORROWED);

            assertFalse(decoder.markSupported(), format.name());
            byte[] ranged = new byte[43];
            Arrays.fill(ranged, (byte) 0x5a);
            assertEquals(0, decoder.read(ranged, 3, 0), format.name());
            assertEquals(Byte.toUnsignedInt(CONTENT[0]), decoder.read(), format.name());
            assertEquals(37, decoder.readNBytes(ranged, 3, 37), format.name());
            assertEquals((byte) 0x5a, ranged[2], format.name());
            assertEquals((byte) 0x5a, ranged[40], format.name());
            assertArrayEquals(Arrays.copyOfRange(CONTENT, 1, 38), Arrays.copyOfRange(ranged, 3, 40), format.name());

            int position = 38;
            decoder.skipNBytes(503L);
            position += 503;
            assertEquals(Byte.toUnsignedInt(CONTENT[position]), decoder.read(), format.name());
            position++;

            byte[] middle = decoder.readNBytes(777);
            assertArrayEquals(Arrays.copyOfRange(CONTENT, position, position + middle.length), middle, format.name());
            position += middle.length;

            ByteArrayOutputStream remainder = new ByteArrayOutputStream();
            assertEquals(CONTENT.length - position, decoder.transferTo(remainder), format.name());
            assertArrayEquals(Arrays.copyOfRange(CONTENT, position, CONTENT.length), remainder.toByteArray(), format.name());
            assertEquals(-1, decoder.read(), format.name());
            assertEquals(-1, decoder.read(new byte[4]), format.name());
            assertEquals(0, decoder.read(new byte[4], 2, 0), format.name());
            assertArrayEquals(new byte[0], decoder.readNBytes(0), format.name());

            decoder.close();
            assertFalse(encodedSource.closed(), format.name());
            assertThrows(IOException.class, decoder::read, format.name());
            encodedSource.close();
            assertTrue(encodedSource.closed(), format.name());
        }
    }

    /// Verifies skipped and returned bytes share one limit across inherited bulk stream operations.
    @ParameterizedTest
    @ValueSource(strings = {"skip", "skipNBytes", "readNBytes", "rangedReadNBytes", "transferTo"})
    void enforcesOutputLimitsAcrossBulkOperations(String operation) throws IOException {
        for (CompressionFormat format : CompressionFormats.installed()) {
            CompressionCodec<?> codec = format.defaultCodec();
            byte[] encoded = encode(codec);
            for (int maximum : new int[]{0, 8191, 8192, 8193, CONTENT.length}) {
                String context = format.name() + " " + operation + " maximum=" + maximum;
                CompressionCodec<?> configured = CodecContractConfigurations.decoderCodec(codec, CONTENT.length)
                        .withMaximumOutputSize(maximum);
                TrackingInputStream source = new TrackingInputStream(encoded);
                try (InputStream decoder = configured.newInputStream(source, ResourceOwnership.BORROWED)) {
                    int prefixSize = Math.min(maximum, 48);
                    int readPrefix = Math.min(prefixSize, 17);
                    assertArrayEquals(Arrays.copyOf(CONTENT, readPrefix), decoder.readNBytes(readPrefix), context);
                    decoder.skipNBytes(prefixSize - readPrefix);

                    byte[] ranged = new byte[CONTENT.length + 3];
                    Arrays.fill(ranged, (byte) 0x5a);
                    ByteArrayOutputStream transferred = new ByteArrayOutputStream();
                    Executable bulkRead = () -> {
                        switch (operation) {
                            case "skip":
                                assertEquals(CONTENT.length - prefixSize, decoder.skip(Long.MAX_VALUE), context);
                                break;
                            case "skipNBytes":
                                decoder.skipNBytes(Long.MAX_VALUE);
                                break;
                            case "readNBytes":
                                assertArrayEquals(Arrays.copyOfRange(CONTENT, prefixSize, CONTENT.length),
                                        decoder.readNBytes(CONTENT.length + 1), context);
                                break;
                            case "rangedReadNBytes":
                                assertEquals(CONTENT.length - prefixSize,
                                        decoder.readNBytes(ranged, 1, CONTENT.length + 1), context);
                                break;
                            case "transferTo":
                                assertEquals(CONTENT.length - prefixSize, decoder.transferTo(transferred), context);
                                break;
                            default:
                                throw new AssertionError(operation);
                        }
                    };
                    if (maximum < CONTENT.length) {
                        DecompressionOutputLimitException failure = assertThrows(
                                DecompressionOutputLimitException.class, bulkRead, context);
                        assertEquals(maximum, failure.maximum(), context);
                        int remainingSource = source.available();
                        assertEquals(maximum, assertThrows(DecompressionOutputLimitException.class,
                                decoder::read, context).maximum(), context);
                        assertEquals(remainingSource, source.available(), context);
                    } else {
                        if (operation.equals("skipNBytes")) {
                            assertThrows(EOFException.class, bulkRead, context);
                        } else {
                            assertDoesNotThrow(bulkRead, context);
                        }
                        assertEquals(-1, decoder.read(), context);
                        assertEquals(0L, decoder.skip(Long.MAX_VALUE), context);
                    }
                    if (operation.equals("rangedReadNBytes")) {
                        int delivered = maximum - prefixSize;
                        assertArrayEquals(Arrays.copyOfRange(CONTENT, prefixSize, maximum),
                                Arrays.copyOfRange(ranged, 1, delivered + 1), context);
                        assertEquals((byte) 0x5a, ranged[0], context);
                        for (int index = delivered + 1; index < ranged.length; index++) {
                            assertEquals((byte) 0x5a, ranged[index], context);
                        }
                    } else if (operation.equals("transferTo")) {
                        assertArrayEquals(Arrays.copyOfRange(CONTENT, prefixSize, maximum),
                                transferred.toByteArray(), context);
                    }
                    assertEquals(0, decoder.read(ranged, 1, 0), context);
                }
                assertFalse(source.closed(), context);
                source.close();
                assertTrue(source.closed(), context);
            }
        }
    }

    /// Encodes the shared content through one stream adapter.
    private static byte[] encode(CompressionCodec<?> codec) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (OutputStream output = codec.newOutputStream(
                encoded,
                EncodingOptions.ofSourceSize(CONTENT.length),
                ResourceOwnership.BORROWED
        )) {
            output.write(CONTENT[0]);
            int offset = 1;
            while (offset < CONTENT.length) {
                int count = Math.min(1 + offset % 113, CONTENT.length - offset);
                output.write(CONTENT, offset, count);
                offset += count;
            }
        }
        return encoded.toByteArray();
    }

    /// Creates deterministic input with both repetitive and varying regions.
    private static byte[] createContent() {
        byte[] content = new byte[16_417];
        for (int index = 0; index < content.length; index++) {
            content[index] = (byte) (index * 73 + (index >>> 3) * 19);
        }
        for (int index = 4096; index < 8192; index++) {
            content[index] = content[index - 4096];
        }
        return content;
    }

    /// Copies the remaining bytes from a buffer without changing its state.
    private static byte[] bufferBytes(ByteBuffer buffer) {
        ByteBuffer view = buffer.duplicate();
        byte[] bytes = new byte[view.remaining()];
        view.get(bytes);
        return bytes;
    }

    /// Buffers bytes while exposing flush and close calls made by the codec stream.
    @NotNullByDefault
    private static final class TrackingBufferedOutputStream extends BufferedOutputStream {
        /// Number of completed flush calls.
        private int flushCount;

        /// Whether the stream has been closed.
        private boolean closed;

        /// Creates a tracking stream whose entire encoded fixture fits in its buffer.
        private TrackingBufferedOutputStream(OutputStream target) {
            super(target, 1 << 20);
        }

        /// Flushes buffered bytes and records successful completion.
        @Override
        public void flush() throws IOException {
            super.flush();
            flushCount++;
        }

        /// Closes the stream and records successful completion.
        @Override
        public void close() throws IOException {
            super.close();
            closed = true;
        }

        /// Returns the number of completed flush calls.
        private int flushCount() {
            return flushCount;
        }

        /// Returns whether close completed.
        private boolean closed() {
            return closed;
        }
    }

    /// Reads immutable bytes while exposing whether the source stream was closed.
    @NotNullByDefault
    private static final class TrackingInputStream extends ByteArrayInputStream {
        /// Whether this source has been closed.
        private boolean closed;

        /// Creates a tracking source over a defensive byte copy.
        private TrackingInputStream(byte[] bytes) {
            super(bytes.clone());
        }

        /// Closes this source and records completion.
        @Override
        public void close() throws IOException {
            super.close();
            closed = true;
        }

        /// Returns whether close completed.
        private boolean closed() {
            return closed;
        }
    }
}
