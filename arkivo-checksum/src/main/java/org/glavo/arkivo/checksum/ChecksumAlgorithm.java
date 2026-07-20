// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.checksum;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.ByteBuffer;
import java.util.Objects;

/// Describes an immutable checksum algorithm configuration and creates independent accumulators.
///
/// Implementations must be safe for concurrent use. Operation progress belongs exclusively to accumulators returned by
/// [#newAccumulator()]. One-shot buffer operations consume all remaining source bytes without changing the source limit
/// or retaining the buffer.
@NotNullByDefault
public interface ChecksumAlgorithm {
    /// Returns the conventional algorithm name.
    ///
    /// The name is intended for diagnostics and is not a provider lookup key.
    ///
    /// @return the algorithm name
    String name();

    /// Returns the exact number of bytes in a completed checksum.
    ///
    /// @return a positive fixed result size
    int checksumSize();

    /// Creates a fresh accumulator in its active initial state.
    ///
    /// @return an independent accumulator
    ChecksumAccumulator newAccumulator();

    /// Computes a checksum over one complete byte array.
    ///
    /// @param source the complete input
    /// @return the immutable checksum value
    default ChecksumValue compute(byte[] source) {
        Objects.requireNonNull(source, "source");
        ChecksumAccumulator accumulator = newAccumulator();
        accumulator.update(source);
        return accumulator.finish();
    }

    /// Computes a checksum over one byte-array range.
    ///
    /// @param source the source array
    /// @param offset the first byte index
    /// @param length the number of bytes to include
    /// @return the immutable checksum value
    /// @throws IndexOutOfBoundsException if the range is outside `source`
    default ChecksumValue compute(byte[] source, int offset, int length) {
        Objects.requireNonNull(source, "source");
        ChecksumAccumulator accumulator = newAccumulator();
        accumulator.update(source, offset, length);
        return accumulator.finish();
    }

    /// Computes a checksum over the remaining source bytes.
    ///
    /// On success the source position advances to its original limit and its limit is unchanged. The source may be
    /// direct or read-only and is not retained.
    ///
    /// @param source the input buffer
    /// @return the immutable checksum value
    default ChecksumValue compute(ByteBuffer source) {
        Objects.requireNonNull(source, "source");
        ChecksumAccumulator accumulator = newAccumulator();
        accumulator.update(source);
        return accumulator.finish();
    }

    /// Computes a checksum into a caller-provided target.
    ///
    /// The target receives exactly [#checksumSize()] canonical bytes. The method validates the target before consuming
    /// source, so a read-only or undersized target leaves both buffer positions unchanged.
    ///
    /// @param source the input buffer
    /// @param target the destination for the canonical checksum bytes
    default void compute(ByteBuffer source, ByteBuffer target) {
        Objects.requireNonNull(source, "source");
        ChecksumValue.requireWritable(target, checksumSize());
        ChecksumAccumulator accumulator = newAccumulator();
        accumulator.update(source);
        accumulator.finish(target);
    }

    /// Describes a checksum whose complete result fits in a `long` carrier.
    ///
    /// Results narrower than 64 bits are exposed as nonnegative unsigned integers with all unused high bits cleared.
    /// A 64-bit result preserves its complete bit pattern and may be negative in Java's signed representation.
    @NotNullByDefault
    interface UpTo64Bits extends ChecksumAlgorithm {
        /// Returns the exact number of significant result bits.
        ///
        /// @return a value from 1 through 64
        int bitSize();

        /// Creates a primitive-result accumulator.
        ///
        /// @return an independent accumulator
        @Override
        ChecksumAccumulator.UpTo64Bits newAccumulator();

        /// Returns the byte width needed to carry [#bitSize()] bits.
        ///
        /// @return the positive fixed result size
        @Override
        default int checksumSize() {
            return (bitSize() + Byte.SIZE - 1) / Byte.SIZE;
        }

        /// Computes a primitive checksum over one complete byte array.
        ///
        /// @param source the complete input
        /// @return the unsigned value when narrower than 64 bits, otherwise the complete 64-bit pattern
        default long computeLong(byte[] source) {
            Objects.requireNonNull(source, "source");
            ChecksumAccumulator.UpTo64Bits accumulator = newAccumulator();
            accumulator.update(source);
            return accumulator.finishLong();
        }

        /// Computes a primitive checksum over one byte-array range.
        ///
        /// @param source the source array
        /// @param offset the first byte index
        /// @param length the number of bytes to include
        /// @return the unsigned value when narrower than 64 bits, otherwise the complete 64-bit pattern
        /// @throws IndexOutOfBoundsException if the range is outside `source`
        default long computeLong(byte[] source, int offset, int length) {
            Objects.requireNonNull(source, "source");
            ChecksumAccumulator.UpTo64Bits accumulator = newAccumulator();
            accumulator.update(source, offset, length);
            return accumulator.finishLong();
        }

        /// Computes a primitive checksum over the remaining source bytes.
        ///
        /// @param source the input buffer, consumed through its original limit
        /// @return the unsigned value when narrower than 64 bits, otherwise the complete 64-bit pattern
        default long computeLong(ByteBuffer source) {
            Objects.requireNonNull(source, "source");
            ChecksumAccumulator.UpTo64Bits accumulator = newAccumulator();
            accumulator.update(source);
            return accumulator.finishLong();
        }
    }

    /// Describes an algorithm with an exact 32-bit checksum result.
    ///
    /// The `int` methods expose the raw 32-bit pattern. Inherited `long` methods expose the same result as an
    /// unsigned integer from zero through `0xffff_ffffL`.
    @NotNullByDefault
    interface Width32 extends UpTo64Bits {
        /// Returns the exact 32-bit result width.
        ///
        /// @return [Integer#SIZE]
        @Override
        default int bitSize() {
            return Integer.SIZE;
        }

        /// Creates a 32-bit accumulator.
        ///
        /// @return an independent accumulator
        @Override
        ChecksumAccumulator.Width32 newAccumulator();

        /// Computes the raw 32-bit checksum pattern over one complete byte array.
        ///
        /// @param source the complete input
        /// @return the raw result bits
        default int computeInt(byte[] source) {
            Objects.requireNonNull(source, "source");
            ChecksumAccumulator.Width32 accumulator = newAccumulator();
            accumulator.update(source);
            return accumulator.finishInt();
        }

        /// Computes the raw 32-bit checksum pattern over one byte-array range.
        ///
        /// @param source the source array
        /// @param offset the first byte index
        /// @param length the number of bytes to include
        /// @return the raw result bits
        /// @throws IndexOutOfBoundsException if the range is outside `source`
        default int computeInt(byte[] source, int offset, int length) {
            Objects.requireNonNull(source, "source");
            ChecksumAccumulator.Width32 accumulator = newAccumulator();
            accumulator.update(source, offset, length);
            return accumulator.finishInt();
        }

        /// Computes the raw 32-bit checksum pattern over the remaining source bytes.
        ///
        /// @param source the input buffer, consumed through its original limit
        /// @return the raw result bits
        default int computeInt(ByteBuffer source) {
            Objects.requireNonNull(source, "source");
            ChecksumAccumulator.Width32 accumulator = newAccumulator();
            accumulator.update(source);
            return accumulator.finishInt();
        }

        /// Computes the unsigned 32-bit checksum value over one complete byte array.
        ///
        /// @param source the complete input
        /// @return a value from zero through `0xffff_ffffL`
        @Override
        default long computeLong(byte[] source) {
            return Integer.toUnsignedLong(computeInt(source));
        }

        /// Computes the unsigned 32-bit checksum value over one byte-array range.
        ///
        /// @param source the source array
        /// @param offset the first byte index
        /// @param length the number of bytes to include
        /// @return a value from zero through `0xffff_ffffL`
        /// @throws IndexOutOfBoundsException if the range is outside `source`
        @Override
        default long computeLong(byte[] source, int offset, int length) {
            return Integer.toUnsignedLong(computeInt(source, offset, length));
        }

        /// Computes the unsigned 32-bit checksum value over the remaining source bytes.
        ///
        /// @param source the input buffer, consumed through its original limit
        /// @return a value from zero through `0xffff_ffffL`
        @Override
        default long computeLong(ByteBuffer source) {
            return Integer.toUnsignedLong(computeInt(source));
        }
    }

    /// Describes an algorithm with an exact 64-bit checksum result.
    @NotNullByDefault
    interface Width64 extends UpTo64Bits {
        /// Returns the exact 64-bit result width.
        ///
        /// @return [Long#SIZE]
        @Override
        default int bitSize() {
            return Long.SIZE;
        }

        /// Creates a 64-bit accumulator.
        ///
        /// @return an independent accumulator
        @Override
        ChecksumAccumulator.Width64 newAccumulator();
    }
}
