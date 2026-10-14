// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.checksum;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.ByteBuffer;
import java.util.Objects;

/// Accumulates input for one checksum computation.
///
/// A new accumulator is active. Calling [#finish()] completes the current value and enters the finished state. Finishing
/// is idempotent, but any update in the finished state throws [IllegalStateException]. [#reset()] discards the current
/// computation and restores the algorithm's configured initial state. Accumulators are not safe for concurrent use.
@NotNullByDefault
public interface ChecksumAccumulator {
    /// Returns the conventional algorithm name for diagnostics, not for provider lookup.
    ///
    /// @return the algorithm name
    String name();

    /// Returns the fixed number of bytes in a completed checksum.
    ///
    /// @return a positive result size
    int checksumSize();

    /// Adds one complete byte to the active computation.
    ///
    /// @param value the byte to add
    /// @throws IllegalStateException if this accumulator is finished
    void update(byte value);

    /// Adds one complete byte array to the active computation.
    ///
    /// @param source the bytes to add
    /// @throws IllegalStateException if this accumulator is finished
    default void update(byte[] source) {
        Objects.requireNonNull(source, "source");
        update(source, 0, source.length);
    }

    /// Adds a byte-array range to the active computation.
    ///
    /// @param source the source array
    /// @param offset the first byte index
    /// @param length the number of bytes to add
    /// @throws IndexOutOfBoundsException if the range is outside `source`
    /// @throws IllegalStateException     if this accumulator is finished
    void update(byte[] source, int offset, int length);

    /// Adds all remaining source bytes to the active computation.
    ///
    /// On success the source position advances to its original limit and its limit is unchanged. The source may be
    /// direct or read-only and is not retained.
    ///
    /// @param source the input buffer
    /// @throws IllegalStateException if this accumulator is finished
    void update(ByteBuffer source);

    /// Completes and returns the immutable checksum value.
    ///
    /// Repeated calls without an intervening reset return equal values and do not perform another computation.
    ///
    /// @return the completed checksum
    ChecksumValue finish();

    /// Completes the checksum and writes its canonical representation.
    ///
    /// The target position advances by [#checksumSize()] on success. A read-only or undersized target
    /// is rejected before this accumulator enters the finished state.
    ///
    /// @param target the destination buffer
    default void finish(ByteBuffer target) {
        ChecksumValue.requireWritable(target, checksumSize());
        finish().writeTo(target);
    }

    /// Discards current progress and restores the active configured initial state.
    void reset();

    /// Accumulates a checksum whose complete result fits in a `long` carrier.
    @NotNullByDefault
    interface UpTo64Bits extends ChecksumAccumulator {
        /// Returns the number of significant result bits.
        ///
        /// @return a value from 1 through 64
        int bitSize();

        /// Returns the byte width needed to carry the result bits.
        ///
        /// @return the fixed result size
        @Override
        default int checksumSize() {
            return (bitSize() + Byte.SIZE - 1) / Byte.SIZE;
        }

        /// Completes and returns the checksum in a `long` carrier.
        ///
        /// Results narrower than 64 bits are nonnegative unsigned integers. A 64-bit result preserves its complete bit
        /// pattern and may be negative. This method has the same state transition and idempotence as [#finish()].
        ///
        /// @return the completed primitive checksum
        long finishLong();

        /// Completes and returns the canonical immutable checksum value.
        ///
        /// @return the completed checksum
        @Override
        default ChecksumValue finish() {
            return ChecksumValue.ofLong(finishLong(), checksumSize());
        }
    }

    /// Accumulates an exact 32-bit checksum.
    @NotNullByDefault
    interface Width32 extends UpTo64Bits {
        /// Returns the exact 32-bit result width.
        ///
        /// @return [Integer#SIZE]
        @Override
        default int bitSize() {
            return Integer.SIZE;
        }

        /// Completes and returns the raw 32-bit checksum pattern.
        ///
        /// This method has the same state transition and idempotence as [#finish()].
        ///
        /// @return the raw result bits
        int finishInt();

        /// Completes and returns the checksum as an unsigned 32-bit integer.
        ///
        /// @return a value from zero through `0xffff_ffffL`
        @Override
        default long finishLong() {
            return Integer.toUnsignedLong(finishInt());
        }
    }

    /// Accumulates an exact 64-bit checksum.
    @NotNullByDefault
    interface Width64 extends UpTo64Bits {
        /// Returns the exact 64-bit result width.
        ///
        /// @return [Long#SIZE]
        @Override
        default int bitSize() {
            return Long.SIZE;
        }
    }
}
