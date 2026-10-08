// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies member transitions, optional headers, and cumulative limits in gzip input streams.
@NotNullByDefault
final class GzipMemberStreamTest {
    /// The bytes returned before the second member begins.
    private static final byte @Unmodifiable [] FIRST = "first member\n".getBytes(StandardCharsets.UTF_8);

    /// The second member's distinct bytes, including high-bit values and zero bytes.
    private static final byte @Unmodifiable [] SECOND = {0, (byte) 255, 12, (byte) 128, 0, 7};

    /// Combines all defined flag bits with short reads and conservative availability estimates.
    private static Stream<Arguments> headerConfigurations() {
        return IntStream.range(0, 32).boxed().flatMap(flags -> Stream.of(1, 7, 8192)
                .flatMap(chunk -> Stream.of(false, true).map(zeroAvailable ->
                        Arguments.of(flags, chunk, zeroAvailable))));
    }

    /// Reads empty members before, between, and after nonempty members with independent header checksums.
    @ParameterizedTest(name = "flags={0}, chunk={1}, zero available={2}")
    @MethodSource("headerConfigurations")
    void readsConcatenatedMembers(int flags, int chunk, boolean zeroAvailable) throws IOException {
        byte[] archive = concatenate(member(new byte[0], flags), member(FIRST, flags),
                member(new byte[0], 31 - flags), member(SECOND, 31 - flags), member(new byte[0], flags));
        byte[] expected = concatenate(FIRST, SECOND);
        try (var input = GzipCodec.DEFAULT.newInputStream(new FragmentedInput(archive, chunk, zeroAvailable))) {
            assertArrayEquals(expected, input.readAllBytes());
            assertEquals(-1, input.read());
            assertEquals(-1, input.read());
        }
        try (var reference = new GZIPInputStream(new ByteArrayInputStream(archive))) {
            assertArrayEquals(expected, reference.readAllBytes());
        }
    }

    /// Supplies every nonempty incomplete prefix of a second member with all optional fields.
    private static Stream<Arguments> truncatedMembers() throws IOException {
        int size = member(SECOND, 30).length;
        return IntStream.range(1, size).boxed().flatMap(length -> Stream.of(1, 8192)
                .map(chunk -> Arguments.of(length, chunk)));
    }

    /// Does not mistake a truncated following member for successful completion of the first member.
    @ParameterizedTest(name = "second member prefix={0}, chunk={1}")
    @MethodSource("truncatedMembers")
    void rejectsTruncatedFollowingMember(int length, int chunk) throws IOException {
        byte[] archive = concatenate(member(FIRST, 0), Arrays.copyOf(member(SECOND, 30), length));
        try (var input = GzipCodec.DEFAULT.newInputStream(new FragmentedInput(archive, chunk, true))) {
            assertArrayEquals(FIRST, input.readNBytes(FIRST.length));
            assertThrows(IOException.class, input::readAllBytes);
        }
    }

    /// Selects signature, method, flags, optional fields, header CRC, content CRC, and size mutations.
    private static Stream<Arguments> corruptMembers() throws IOException {
        int size = member(SECOND, 30).length;
        return Stream.of(0, 1, 2, 3, 12, 16, 22, 26, size - 8, size - 4).flatMap(offset ->
                Stream.of(1, 8192).map(chunk -> Arguments.of(offset, chunk)));
    }

    /// Validates the next member independently rather than reusing the preceding checksum or EOF state.
    @ParameterizedTest(name = "corrupt offset={0}, chunk={1}")
    @MethodSource("corruptMembers")
    void rejectsCorruptFollowingMember(int offset, int chunk) throws IOException {
        byte[] damaged = member(SECOND, 30);
        damaged[offset] ^= 0x40;
        byte[] archive = concatenate(member(FIRST, 0), damaged);
        try (var input = GzipCodec.DEFAULT.newInputStream(new FragmentedInput(archive, chunk, true))) {
            assertArrayEquals(FIRST, input.readNBytes(FIRST.length));
            assertThrows(IOException.class, input::readAllBytes);
        }
    }

    /// Supplies output limits before, at, and beyond each nonempty member boundary.
    private static Stream<Arguments> outputLimits() {
        return Stream.of(0, FIRST.length - 1, FIRST.length, FIRST.length + 1,
                FIRST.length + SECOND.length - 1, FIRST.length + SECOND.length,
                FIRST.length + SECOND.length + 1).flatMap(limit -> Stream.of(1, 8192)
                .map(chunk -> Arguments.of(limit, chunk)));
    }

    /// Keeps one output budget across member resets, including intervening empty members.
    @ParameterizedTest(name = "output limit={0}, chunk={1}")
    @MethodSource("outputLimits")
    void enforcesCumulativeOutputLimit(int limit, int chunk) throws IOException {
        byte[] archive = concatenate(member(FIRST, 30), member(new byte[0], 30), member(SECOND, 30),
                member(new byte[0], 30));
        var codec = GzipCodec.DEFAULT.withMaximumOutputSize(limit);
        try (var input = codec.newInputStream(new FragmentedInput(archive, chunk, true))) {
            if (limit < FIRST.length + SECOND.length) {
                assertThrows(IOException.class, input::readAllBytes);
            } else {
                assertArrayEquals(concatenate(FIRST, SECOND), input.readAllBytes());
            }
        }
    }

    /// Crosses both bytes of XLEN and its maximum unsigned 16-bit value.
    private static Stream<Arguments> extraLengths() {
        return Stream.of(0, 255, 256, 65535).flatMap(length -> Stream.of(1, 8192)
                .map(chunk -> Arguments.of(length, chunk)));
    }

    /// Includes the complete extra field in FHCRC without treating embedded zero or magic bytes as boundaries.
    @ParameterizedTest(name = "extra length={0}, chunk={1}")
    @MethodSource("extraLengths")
    void readsExtraFieldLengthBoundaries(int length, int chunk) throws IOException {
        byte[] archive = concatenate(member(FIRST, 30, length), member(SECOND, 30, 0));
        byte[] expected = concatenate(FIRST, SECOND);
        try (var input = GzipCodec.DEFAULT.newInputStream(new FragmentedInput(archive, chunk, true))) {
            assertArrayEquals(expected, input.readAllBytes());
        }
        try (var reference = new GZIPInputStream(new ByteArrayInputStream(archive))) {
            assertArrayEquals(expected, reference.readAllBytes());
        }
    }

    /// Encodes the body with the JDK and adds only the optional fields selected by the five defined flags.
    private static byte[] member(byte @Unmodifiable [] content, int flags) throws IOException {
        return member(content, flags, 4);
    }

    /// Builds a member whose extra field has the requested length, when enabled by the flags.
    private static byte[] member(byte @Unmodifiable [] content, int flags, int extraLength) throws IOException {
        var encoded = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(encoded)) {
            gzip.write(content);
        }
        byte[] original = encoded.toByteArray();
        original[3] = (byte) flags;
        var output = new ByteArrayOutputStream();
        output.write(original, 0, 10);
        if ((flags & 4) != 0) {
            byte[] extra = new byte[extraLength + 2];
            ByteArrayAccess.writeShortLittleEndian(extra, 0, (short) extraLength);
            for (int index = 2; index < extra.length; index++) {
                extra[index] = (byte) (index * 31);
            }
            if (extraLength >= 4) {
                extra[2] = 0x1f;
                extra[3] = (byte) 0x8b;
                extra[4] = 0;
                extra[5] = (byte) 255;
            }
            output.writeBytes(extra);
        }
        if ((flags & 8) != 0) {
            output.writeBytes(new byte[]{'n', (byte) 233, 'm', 'e', 0});
        }
        if ((flags & 16) != 0) {
            output.writeBytes(new byte[]{'c', (byte) 255, 'm', 't', 0});
        }
        if ((flags & 2) != 0) {
            CRC32 crc = new CRC32();
            crc.update(output.toByteArray());
            byte[] checksum = new byte[2];
            ByteArrayAccess.writeShortLittleEndian(checksum, 0, (short) crc.getValue());
            output.writeBytes(checksum);
        }
        output.write(original, 10, original.length - 10);
        return output.toByteArray();
    }

    /// Joins complete members or a complete member followed by a damaged suffix.
    private static byte[] concatenate(byte @Unmodifiable []... parts) {
        var output = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            output.writeBytes(part);
        }
        return output.toByteArray();
    }

    /// Models a source whose availability estimate need not reveal a following member.
    @NotNullByDefault
    private static final class FragmentedInput extends ByteArrayInputStream {
        /// The maximum compressed bytes returned by a bulk read.
        private final int chunk;

        /// Whether availability always returns the permitted conservative estimate of zero.
        private final boolean zeroAvailable;

        /// Creates a short-reading source without altering end-of-input semantics.
        private FragmentedInput(byte @Unmodifiable [] bytes, int chunk, boolean zeroAvailable) {
            super(bytes);
            this.chunk = chunk;
            this.zeroAvailable = zeroAvailable;
        }

        @Override
        public synchronized int read(byte[] target, int offset, int length) {
            return super.read(target, offset, Math.min(length, chunk));
        }

        @Override
        public synchronized int available() {
            return zeroAvailable ? 0 : super.available();
        }
    }
}
