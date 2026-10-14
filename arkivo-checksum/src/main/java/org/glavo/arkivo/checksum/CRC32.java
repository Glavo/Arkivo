// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.checksum;

import org.glavo.arkivo.checksum.internal.JdkChecksum32;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.ByteBuffer;

/// Computes CRC-32/ISO-HDLC checksums and creates independent incremental computations.
///
/// Factories return active, non-thread-safe accumulators. Static computation methods use independent state.
/// Input buffers are consumed through their limits without changing their limits or byte order and are not retained.
@NotNullByDefault
public final class CRC32 {
    /// Prevents construction.
    private CRC32() {
    }

    /// Creates an active CRC-32/ISO-HDLC computation.
    ///
    /// @return an independent accumulator
    public static ChecksumAccumulator.Width32 newAccumulator() {
        return new JdkChecksum32("CRC-32/ISO-HDLC", new java.util.zip.CRC32());
    }

    /// Computes CRC-32/ISO-HDLC over the supplied input.
    ///
    /// @param source the complete input
    /// @return the raw 32-bit checksum
    public static int computeInt(byte[] source) {
        var accumulator = newAccumulator();
        accumulator.update(source);
        return accumulator.finishInt();
    }

    /// Computes CRC-32/ISO-HDLC over the supplied input.
    ///
    /// @param source the source array
    /// @param offset the first byte index
    /// @param length the number of bytes
    /// @return the raw 32-bit checksum
    /// @throws IndexOutOfBoundsException if the range is outside the array
    public static int computeInt(byte[] source, int offset, int length) {
        var accumulator = newAccumulator();
        accumulator.update(source, offset, length);
        return accumulator.finishInt();
    }

    /// Computes CRC-32/ISO-HDLC over the supplied input.
    ///
    /// @param source the input buffer, consumed through its limit
    /// @return the raw 32-bit checksum
    public static int computeInt(ByteBuffer source) {
        var accumulator = newAccumulator();
        accumulator.update(source);
        return accumulator.finishInt();
    }

    /// Computes CRC-32/ISO-HDLC and writes its canonical bytes to the target.
    ///
    /// The target is validated before source consumption. Its position advances by 4 bytes on success.
    ///
    /// @param source the input buffer, consumed through its limit
    /// @param target the writable destination with at least 4 bytes remaining
    /// @throws java.nio.ReadOnlyBufferException if the target is read-only
    /// @throws java.nio.BufferOverflowException if the target has insufficient remaining space
    public static void compute(ByteBuffer source, ByteBuffer target) {
        ChecksumValue.requireWritable(target, 4);
        var accumulator = newAccumulator();
        accumulator.update(source);
        accumulator.finish(target);
    }
}
