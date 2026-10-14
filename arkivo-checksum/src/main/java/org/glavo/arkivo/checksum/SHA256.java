// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.checksum;

import org.glavo.arkivo.checksum.internal.MessageDigestChecksum;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.ByteBuffer;

/// Computes SHA-256 checksums and creates independent incremental computations.
///
/// Factories return active, non-thread-safe accumulators. Static computation methods use independent state.
/// Input buffers are consumed through their limits without changing their limits or byte order and are not retained.
@NotNullByDefault
public final class SHA256 {
    /// Prevents construction.
    private SHA256() {
    }

    /// Creates an active SHA-256 computation.
    ///
    /// @return an independent accumulator
    public static ChecksumAccumulator newAccumulator() {
        return new MessageDigestChecksum("SHA-256", 32);
    }

    /// Computes SHA-256 over the supplied input.
    ///
    /// @param source the complete input
    /// @return the immutable digest
    public static ChecksumValue compute(byte[] source) {
        var accumulator = newAccumulator();
        accumulator.update(source);
        return accumulator.finish();
    }

    /// Computes SHA-256 over the supplied input.
    ///
    /// @param source the source array
    /// @param offset the first byte index
    /// @param length the number of bytes
    /// @return the immutable digest
    /// @throws IndexOutOfBoundsException if the range is outside the array
    public static ChecksumValue compute(byte[] source, int offset, int length) {
        var accumulator = newAccumulator();
        accumulator.update(source, offset, length);
        return accumulator.finish();
    }

    /// Computes SHA-256 over the supplied input.
    ///
    /// @param source the input buffer, consumed through its limit
    /// @return the immutable digest
    public static ChecksumValue compute(ByteBuffer source) {
        var accumulator = newAccumulator();
        accumulator.update(source);
        return accumulator.finish();
    }

    /// Computes SHA-256 and writes its canonical bytes to the target.
    ///
    /// The target is validated before source consumption. Its position advances by 32 bytes on success.
    ///
    /// @param source the input buffer, consumed through its limit
    /// @param target the writable destination with at least 32 bytes remaining
    /// @throws java.nio.ReadOnlyBufferException if the target is read-only
    /// @throws java.nio.BufferOverflowException if the target has insufficient remaining space
    public static void compute(ByteBuffer source, ByteBuffer target) {
        ChecksumValue.requireWritable(target, 32);
        var accumulator = newAccumulator();
        accumulator.update(source);
        accumulator.finish(target);
    }
}
