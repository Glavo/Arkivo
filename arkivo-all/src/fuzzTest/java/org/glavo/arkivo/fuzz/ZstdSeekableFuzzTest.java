// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.SeekableEncodingOptions;
import org.glavo.arkivo.codec.zstd.ZstdCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Arrays;
import java.util.stream.Stream;

/// Fuzzes Zstandard seek-table production, parsing, and logical random access.
@NotNullByDefault
public final class ZstdSeekableFuzzTest {
    /// The control bytes preceding source content in valid round-trip inputs.
    private static final int HEADER_SIZE = 6;

    /// The minimum generated uncompressed frame size, limiting per-invocation frame counts.
    private static final int MINIMUM_FRAME_SIZE = 32;

    /// The maximum generated uncompressed frame size.
    private static final int MAXIMUM_FRAME_SIZE = 1024;

    /// The number of random logical reads performed after each complete sequential verification.
    private static final int RANDOM_READ_COUNT = 12;

    /// The finite decoder memory allowance used while parsing arbitrary seek tables.
    private static final long MAXIMUM_DECODER_MEMORY_SIZE = 32L * 1024L * 1024L;

    /// The byte stored outside every accessible source or target range.
    private static final byte GUARD_BYTE = (byte) 0xa5;

    /// Creates a seekable-Zstandard fuzz-test instance for JUnit.
    public ZstdSeekableFuzzTest() {
    }

    /// Generates one indexed stream and verifies its frame map through sequential and random logical reads.
    ///
    /// Controls select automatic frame sizing, write chunking, explicit flush and frame boundaries, source-size
    /// pledging, frame and seek-table checksums, buffer storage, and a nonzero encoded-source origin. Every generated
    /// operation is valid, so an I/O or runtime failure is a fuzz finding.
    ///
    /// @param data encoding controls followed by arbitrary logical content
    /// @throws IOException if a valid seekable encoding or logical read unexpectedly fails
    @MethodSource("roundTripSeeds")
    @FuzzTest(maxDuration = "1m")
    void fuzzZstdSeekableRoundTrip(byte @Unmodifiable [] data) throws IOException {
        if (data.length < HEADER_SIZE
                || data.length > HEADER_SIZE + FuzzSupport.MAX_ROUND_TRIP_INPUT_SIZE) {
            return;
        }

        int frameSelector = Byte.toUnsignedInt(data[0]) << 8 | Byte.toUnsignedInt(data[1]);
        int maximumFrameSize = MINIMUM_FRAME_SIZE
                + frameSelector % (MAXIMUM_FRAME_SIZE - MINIMUM_FRAME_SIZE + 1);
        int chunkSize = 1 + (Byte.toUnsignedInt(data[2]) & 0x3f);
        int controls = Byte.toUnsignedInt(data[3]);
        int frameInterval = 1 + (Byte.toUnsignedInt(data[4]) & 0x07);
        byte[] expected = Arrays.copyOfRange(data, HEADER_SIZE, data.length);
        ZstdCodec codec = configuredCodec(controls, Byte.toUnsignedInt(data[5]), expected.length);
        long sourceSize = (controls & 0x02) == 0
                ? CompressionCodec.UNKNOWN_SIZE
                : expected.length;

        byte[] encoded = encode(
                codec,
                new SeekableEncodingOptions(sourceSize, maximumFrameSize),
                expected,
                chunkSize,
                frameInterval,
                controls
        );
        int prefixSize = (controls & 0x40) == 0 ? 0 : 1 + (Byte.toUnsignedInt(data[5]) & 0x0f);
        byte[] container = prefixed(encoded, prefixSize, Byte.toUnsignedInt(data[5]));

        CompressionCodec.Seekable.Index index;
        try (ReadOnlyByteArrayChannel source = new ReadOnlyByteArrayChannel(container)) {
            source.position(prefixSize);
            index = codec.readIndex(source);
            if (index == null) {
                throw new AssertionError("Generated seekable encoding has no terminal index");
            }
            if (source.position() != prefixSize) {
                throw new AssertionError("Seek-table parsing changed the encoded source position");
            }
        }
        verifyGeneratedIndex(index, encoded.length, expected.length, maximumFrameSize);
        verifyLogicalReads(index, container, prefixSize, expected, chunkSize, controls, data);
        verifyOwnedSource(index, container, prefixSize);
    }

    /// Parses arbitrary possible seekable encodings and drains any structurally valid logical view.
    ///
    /// A missing index, malformed recognized index, invalid compressed frame, or configured resource-limit failure is
    /// an expected outcome. Runtime failures, position-restoration violations, invalid accepted mappings, and decoded
    /// length mismatches are findings.
    ///
    /// @param data arbitrary possible Zstandard seekable bytes
    /// @throws IOException if an in-memory channel unexpectedly fails outside malformed-input processing
    @MethodSource("indexSeeds")
    @FuzzTest(maxDuration = "1m")
    void fuzzZstdSeekableIndex(byte @Unmodifiable [] data) throws IOException {
        if (data.length > FuzzSupport.MAX_PARSER_INPUT_SIZE) {
            return;
        }

        ZstdCodec codec = ZstdCodec.DEFAULT
                .withMaximumOutputSize(FuzzSupport.MAX_DECODED_OUTPUT_SIZE)
                .withMaximumMemorySize(MAXIMUM_DECODER_MEMORY_SIZE);
        @Nullable CompressionCodec.Seekable.Index index;
        try (ReadOnlyByteArrayChannel source = new ReadOnlyByteArrayChannel(data)) {
            try {
                index = codec.readIndex(source);
            } catch (IOException expectedMalformedIndex) {
                if (source.position() != 0L) {
                    throw new AssertionError("Failed seek-table parsing did not restore the source position");
                }
                return;
            }
            if (source.position() != 0L) {
                throw new AssertionError("Seek-table parsing did not restore the source position");
            }
        }
        if (index == null) {
            return;
        }

        verifyAcceptedIndex(index, data.length);
        try (ReadOnlyByteArrayChannel source = new ReadOnlyByteArrayChannel(data)) {
            try (SeekableByteChannel logical = index.newReadableByteChannel(source, ResourceOwnership.BORROWED)) {
                long decodedSize = drain(logical, 257);
                if (decodedSize != index.uncompressedSize()) {
                    throw new AssertionError("Logical channel length differs from its accepted seek table");
                }
            } catch (IOException expectedMalformedFrame) {
                // A structurally valid seek table may still describe malformed or checksum-invalid data frames.
            }
            if (!source.isOpen()) {
                throw new AssertionError("Borrowed malformed-input source was closed by its logical view");
            }
        }
    }

    /// Returns a bounded standard-frame codec selected by arbitrary controls.
    private static ZstdCodec configuredCodec(int controls, int levelSelector, int outputSize) {
        ZstdCodec codec = ZstdCodec.DEFAULT
                .withFrameChecksum((controls & 0x01) != 0)
                .withMaximumOutputSize(outputSize)
                .withMaximumMemorySize(MAXIMUM_DECODER_MEMORY_SIZE);
        long level = switch (levelSelector & 0x03) {
            case 0 -> codec.minimumCompressionLevel();
            case 1 -> codec.defaultCompressionLevel();
            case 2 -> codec.maximumCompressionLevel();
            default -> (codec.minimumCompressionLevel() + codec.maximumCompressionLevel()) / 2L;
        };
        return codec.toBuilder()
                .compressionLevel(level)
                .contentSize((controls & 0x20) != 0)
                .build();
    }

    /// Encodes complete content with caller-selected chunk and explicit-frame behavior.
    private static byte @Unmodifiable [] encode(
            ZstdCodec codec,
            SeekableEncodingOptions options,
            byte @Unmodifiable [] content,
            int chunkSize,
            int frameInterval,
            int controls
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        WritableByteChannel target = Channels.newChannel(output);
        try {
            try (var encoder = codec.newSeekableWritableByteChannel(
                    target,
                    options,
                    ResourceOwnership.BORROWED
            )) {
                int offset = 0;
                int chunkIndex = 0;
                while (offset < content.length) {
                    int count = Math.min(chunkSize, content.length - offset);
                    ByteBuffer source = guardedSource(
                            content,
                            offset,
                            count,
                            (controls & 0x08) != 0,
                            (controls & 0x10) != 0
                    );
                    int limit = source.limit();
                    ByteOrder order = source.order();
                    int written = encoder.write(source);
                    if (written != count || source.position() != limit
                            || source.limit() != limit || source.order() != order) {
                        throw new AssertionError("Seekable encoder did not consume one source chunk exactly");
                    }
                    verifyGuards(source, count, "encoder source");
                    offset += count;
                    chunkIndex++;
                    if ((controls & 0x04) != 0) {
                        encoder.flush();
                    }
                    if ((controls & 0x80) != 0 && chunkIndex % frameInterval == 0) {
                        encoder.finishFrame();
                    }
                }
            }
            if (!target.isOpen()) {
                throw new AssertionError("Borrowed seekable target was closed by its encoder");
            }
            return output.toByteArray();
        } finally {
            target.close();
        }
    }

    /// Validates stronger invariants for a seek table produced by the tested writer.
    private static void verifyGeneratedIndex(
            CompressionCodec.Seekable.Index index,
            int compressedSize,
            int uncompressedSize,
            int maximumFrameSize
    ) {
        verifyAcceptedIndex(index, compressedSize);
        if (index.frameCount() < 1 || index.uncompressedSize() != uncompressedSize) {
            throw new AssertionError("Generated seek table has an invalid logical extent");
        }
        for (int frame = 0; frame < index.frameCount(); frame++) {
            long size = index.frameUncompressedSize(frame);
            if (size < 0L || size > maximumFrameSize) {
                throw new AssertionError("Generated seek table exceeds its maximum frame size");
            }
        }
    }

    /// Validates contiguous, nonnegative frame mappings returned for an accepted terminal index.
    private static void verifyAcceptedIndex(CompressionCodec.Seekable.Index index, long compressedSize) {
        if (index.compressedSize() != compressedSize || index.uncompressedSize() < 0L || index.frameCount() < 0) {
            throw new AssertionError("Accepted seek table exposes an invalid aggregate extent");
        }
        long nextCompressedOffset = 0L;
        long nextUncompressedOffset = 0L;
        for (int frame = 0; frame < index.frameCount(); frame++) {
            long frameCompressedSize = index.frameCompressedSize(frame);
            long frameUncompressedSize = index.frameUncompressedSize(frame);
            if (index.frameCompressedOffset(frame) != nextCompressedOffset
                    || index.frameUncompressedOffset(frame) != nextUncompressedOffset
                    || frameCompressedSize < 0L
                    || frameUncompressedSize < 0L) {
                throw new AssertionError("Accepted seek table exposes a noncontiguous frame mapping");
            }
            nextCompressedOffset = Math.addExact(nextCompressedOffset, frameCompressedSize);
            nextUncompressedOffset = Math.addExact(nextUncompressedOffset, frameUncompressedSize);
        }
        if (nextCompressedOffset > compressedSize || nextUncompressedOffset != index.uncompressedSize()) {
            throw new AssertionError("Accepted seek table frame totals differ from its aggregate extent");
        }
    }

    /// Verifies a generated index through full sequential decoding and varied random reads.
    private static void verifyLogicalReads(
            CompressionCodec.Seekable.Index index,
            byte @Unmodifiable [] container,
            int prefixSize,
            byte @Unmodifiable [] expected,
            int chunkSize,
            int controls,
            byte @Unmodifiable [] fuzzInput
    ) throws IOException {
        try (ReadOnlyByteArrayChannel source = new ReadOnlyByteArrayChannel(container)) {
            source.position(prefixSize);
            try (SeekableByteChannel logical = index.newReadableByteChannel(source, ResourceOwnership.BORROWED)) {
                if (logical.size() != expected.length) {
                    throw new AssertionError("Logical channel size differs from generated source content");
                }
                if (!Arrays.equals(expected, readAll(logical, chunkSize))) {
                    throw new AssertionError("Sequential seekable decoding changed source content");
                }
                verifyReadOnly(logical);
                for (int operation = 0; operation < RANDOM_READ_COUNT; operation++) {
                    int selector = Byte.toUnsignedInt(fuzzInput[operation % fuzzInput.length]);
                    long position;
                    if (operation == 0) {
                        position = 0L;
                    } else if (operation == 1) {
                        position = expected.length;
                    } else {
                        position = Math.floorMod(
                                selector * 257L + operation * 17L,
                                expected.length + 17L
                        );
                    }
                    int requested = 1 + ((selector + operation * 13) & 0x7f);
                    verifyRandomRead(logical, expected, position, requested, (controls & 0x08) != 0);
                }
            }
            if (!source.isOpen()) {
                throw new AssertionError("Borrowed encoded source was closed by its logical channel");
            }
        }
    }

    /// Verifies logical write and truncate operations retain read-only channel semantics.
    private static void verifyReadOnly(SeekableByteChannel logical) throws IOException {
        try {
            logical.write(ByteBuffer.allocate(1));
            throw new AssertionError("Seekable decoded channel accepted a write");
        } catch (NonWritableChannelException expected) {
            // Expected read-only behavior.
        }
        try {
            logical.truncate(0L);
            throw new AssertionError("Seekable decoded channel accepted truncation");
        } catch (NonWritableChannelException expected) {
            // Expected read-only behavior.
        }
    }

    /// Verifies one random logical read and its buffer, position, and end-of-input effects.
    private static void verifyRandomRead(
            SeekableByteChannel logical,
            byte @Unmodifiable [] expected,
            long position,
            int requested,
            boolean direct
    ) throws IOException {
        logical.position(position);
        ByteBuffer target = guardedTarget(requested, direct);
        int initialPosition = target.position();
        int initialLimit = target.limit();
        ByteOrder initialOrder = target.order();
        int actualCount = logical.read(target);
        int expectedCount = position >= expected.length
                ? -1
                : Math.min(requested, expected.length - Math.toIntExact(position));
        if (actualCount != expectedCount
                || target.position() != initialPosition + Math.max(actualCount, 0)
                || target.limit() != initialLimit
                || target.order() != initialOrder
                || logical.position() != position + Math.max(actualCount, 0)) {
            throw new AssertionError("Random logical read exposes invalid progress");
        }
        verifyGuards(target, requested, "logical read target");
        if (actualCount > 0) {
            byte[] actual = new byte[actualCount];
            ByteBuffer view = target.duplicate();
            view.position(initialPosition);
            view.limit(initialPosition + actualCount);
            view.get(actual);
            byte[] expectedRange = Arrays.copyOfRange(
                    expected,
                    Math.toIntExact(position),
                    Math.toIntExact(position) + actualCount
            );
            if (!Arrays.equals(expectedRange, actual)) {
                throw new AssertionError("Random logical read changed decoded bytes");
            }
        }
    }

    /// Verifies an owning logical view closes its encoded source.
    private static void verifyOwnedSource(
            CompressionCodec.Seekable.Index index,
            byte @Unmodifiable [] container,
            int prefixSize
    ) throws IOException {
        ReadOnlyByteArrayChannel source = new ReadOnlyByteArrayChannel(container);
        source.position(prefixSize);
        SeekableByteChannel logical = index.newReadableByteChannel(source, ResourceOwnership.OWNED);
        logical.close();
        if (source.isOpen()) {
            throw new AssertionError("Owned encoded source remained open after its logical channel closed");
        }
    }

    /// Reads a logical channel completely with a bounded positive buffer size.
    private static byte @Unmodifiable [] readAll(SeekableByteChannel source, int bufferSize) throws IOException {
        source.position(0L);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteBuffer buffer = ByteBuffer.allocate(Math.max(1, bufferSize));
        while (true) {
            int count = source.read(buffer);
            if (count < 0) {
                return output.toByteArray();
            }
            if (count == 0) {
                throw new AssertionError("Seekable logical channel made no progress");
            }
            buffer.flip();
            while (buffer.hasRemaining()) {
                output.write(buffer.get());
            }
            buffer.clear();
        }
    }

    /// Drains a logical channel and returns its decoded byte count.
    private static long drain(SeekableByteChannel source, int bufferSize) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
        long total = 0L;
        while (true) {
            int count = source.read(buffer);
            if (count < 0) {
                return total;
            }
            if (count == 0) {
                throw new AssertionError("Accepted seekable input made no logical progress");
            }
            total = Math.addExact(total, count);
            if (total > FuzzSupport.MAX_DECODED_OUTPUT_SIZE) {
                throw new AssertionError("Seekable decoding exceeded its configured output limit");
            }
            buffer.clear();
        }
    }

    /// Creates a guarded heap or direct source view over one content range.
    private static ByteBuffer guardedSource(
            byte @Unmodifiable [] content,
            int offset,
            int count,
            boolean direct,
            boolean readOnly
    ) {
        ByteBuffer storage = guardedStorage(count, direct);
        storage.put(content, offset, count);
        storage.position(2);
        storage.limit(2 + count);
        storage.order(ByteOrder.LITTLE_ENDIAN);
        return readOnly ? storage.asReadOnlyBuffer().order(storage.order()) : storage;
    }

    /// Creates a guarded heap or direct writable target with the requested accessible size.
    private static ByteBuffer guardedTarget(int size, boolean direct) {
        ByteBuffer target = guardedStorage(size, direct);
        target.position(2);
        target.limit(2 + size);
        target.order(ByteOrder.LITTLE_ENDIAN);
        return target;
    }

    /// Allocates storage filled with guard bytes and positioned at the accessible content start.
    private static ByteBuffer guardedStorage(int contentSize, boolean direct) {
        ByteBuffer storage = direct
                ? ByteBuffer.allocateDirect(contentSize + 4)
                : ByteBuffer.allocate(contentSize + 4);
        while (storage.hasRemaining()) {
            storage.put(GUARD_BYTE);
        }
        storage.position(2);
        return storage;
    }

    /// Verifies bytes immediately before and after one accessible buffer range remain untouched.
    private static void verifyGuards(ByteBuffer buffer, int contentSize, String context) {
        ByteBuffer storage = buffer.duplicate();
        storage.clear();
        if (storage.get(0) != GUARD_BYTE
                || storage.get(1) != GUARD_BYTE
                || storage.get(contentSize + 2) != GUARD_BYTE
                || storage.get(contentSize + 3) != GUARD_BYTE) {
            throw new AssertionError(context + " modified inaccessible guard bytes");
        }
    }

    /// Prepends deterministic non-indexed bytes to an encoded stream.
    private static byte @Unmodifiable [] prefixed(
            byte @Unmodifiable [] encoded,
            int prefixSize,
            int salt
    ) {
        byte[] result = new byte[prefixSize + encoded.length];
        for (int index = 0; index < prefixSize; index++) {
            result[index] = (byte) (salt + index * 31);
        }
        System.arraycopy(encoded, 0, result, prefixSize, encoded.length);
        return result;
    }

    /// Supplies representative seekable writer control combinations.
    ///
    /// @return deterministic valid seekable round-trip seeds
    private static Stream<Arguments> roundTripSeeds() {
        return Stream.of(
                Arguments.of((Object) FuzzSupport.prefix(
                        new byte[]{0, 0, 7, 0x02, 2, 1},
                        FuzzSupport.SEED_CONTENT
                )),
                Arguments.of((Object) FuzzSupport.prefix(
                        new byte[]{3, (byte) 0xff, 13, (byte) 0xfd, 1, 2},
                        FuzzSupport.SEED_CONTENT
                )),
                Arguments.of((Object) new byte[]{0, 7, 1, 0x63, 3, 0})
        );
    }

    /// Supplies generated indexed streams and one ordinary frame for structural mutation.
    ///
    /// @return deterministic possible seekable-index seeds
    /// @throws IOException if a valid seed cannot be encoded
    private static Stream<Arguments> indexSeeds() throws IOException {
        byte[] plain = FuzzSupport.SEED_CONTENT;
        byte[] explicitFrames = Arrays.copyOf(plain, plain.length * 2);
        System.arraycopy(plain, 0, explicitFrames, plain.length, plain.length);
        byte[] ordinary = FuzzSupport.remainingBytes(ZstdCodec.DEFAULT.compress(ByteBuffer.wrap(plain)));
        return Stream.of(
                Arguments.of((Object) encodeSeed(plain, 64, false, false)),
                Arguments.of((Object) encodeSeed(explicitFrames, 32, true, true)),
                Arguments.of((Object) encodeSeed(new byte[0], 64, true, false)),
                Arguments.of((Object) ordinary)
        );
    }

    /// Creates one valid seekable input seed without embedding fuzz-control bytes.
    private static byte @Unmodifiable [] encodeSeed(
            byte @Unmodifiable [] content,
            int maximumFrameSize,
            boolean checksum,
            boolean explicitBoundary
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (WritableByteChannel target = Channels.newChannel(output);
             var encoder = ZstdCodec.DEFAULT.withFrameChecksum(checksum).newSeekableWritableByteChannel(
                     target,
                     new SeekableEncodingOptions(content.length, maximumFrameSize),
                     ResourceOwnership.BORROWED
             )) {
            int split = explicitBoundary ? content.length / 2 : content.length;
            encoder.write(ByteBuffer.wrap(content, 0, split));
            if (explicitBoundary) {
                encoder.finishFrame();
                encoder.write(ByteBuffer.wrap(content, split, content.length - split));
            }
        }
        return output.toByteArray();
    }
}
