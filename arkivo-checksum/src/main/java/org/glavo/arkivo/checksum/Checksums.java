// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.checksum;

import org.glavo.arkivo.checksum.internal.JdkChecksum32;
import org.glavo.arkivo.checksum.internal.MessageDigestChecksum;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.ByteBuffer;
import java.util.zip.Adler32;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;

/// Creates independent checksum computations and computes checksums in one call.
///
/// Factories return active, non-thread-safe accumulators. Static computation methods use independent state.
/// Input buffers are consumed through their limits without changing their limits or byte order and are not retained.
@NotNullByDefault
public final class Checksums {
    /// Prevents construction.
    private Checksums() {
    }

    /// Creates an active Adler-32 computation.
    ///
    /// @return an independent accumulator
    public static ChecksumAccumulator.Width32 adler32() {
        return new JdkChecksum32("Adler-32", new Adler32());
    }

    /// Computes Adler-32 over the supplied input.
    ///
    /// @param source the complete input
    /// @return the raw 32-bit checksum
    public static int adler32(byte[] source) {
        var accumulator = adler32();
        accumulator.update(source);
        return accumulator.finishInt();
    }

    /// Computes Adler-32 over the supplied input.
    ///
    /// @param source the source array
    /// @param offset the first byte index
    /// @param length the number of bytes
    /// @return the raw 32-bit checksum
    /// @throws IndexOutOfBoundsException if the range is outside the array
    public static int adler32(byte[] source, int offset, int length) {
        var accumulator = adler32();
        accumulator.update(source, offset, length);
        return accumulator.finishInt();
    }

    /// Computes Adler-32 over the supplied input.
    ///
    /// @param source the input buffer, consumed through its limit
    /// @return the raw 32-bit checksum
    public static int adler32(ByteBuffer source) {
        var accumulator = adler32();
        accumulator.update(source);
        return accumulator.finishInt();
    }

    /// Computes Adler-32 and writes its canonical bytes to the target.
    ///
    /// The target is validated before source consumption. Its position advances by 4 bytes on success.
    ///
    /// @param source the input buffer, consumed through its limit
    /// @param target the writable destination with at least 4 bytes remaining
    /// @throws java.nio.ReadOnlyBufferException if the target is read-only
    /// @throws java.nio.BufferOverflowException if the target has insufficient remaining space
    public static void adler32(ByteBuffer source, ByteBuffer target) {
        ChecksumValue.requireWritable(target, 4);
        var accumulator = adler32();
        accumulator.update(source);
        accumulator.finish(target);
    }

    /// Creates an active CRC-32/ISO-HDLC computation.
    ///
    /// @return an independent accumulator
    public static ChecksumAccumulator.Width32 crc32() {
        return new JdkChecksum32("CRC-32/ISO-HDLC", new CRC32());
    }

    /// Computes CRC-32/ISO-HDLC over the supplied input.
    ///
    /// @param source the complete input
    /// @return the raw 32-bit checksum
    public static int crc32(byte[] source) {
        var accumulator = crc32();
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
    public static int crc32(byte[] source, int offset, int length) {
        var accumulator = crc32();
        accumulator.update(source, offset, length);
        return accumulator.finishInt();
    }

    /// Computes CRC-32/ISO-HDLC over the supplied input.
    ///
    /// @param source the input buffer, consumed through its limit
    /// @return the raw 32-bit checksum
    public static int crc32(ByteBuffer source) {
        var accumulator = crc32();
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
    public static void crc32(ByteBuffer source, ByteBuffer target) {
        ChecksumValue.requireWritable(target, 4);
        var accumulator = crc32();
        accumulator.update(source);
        accumulator.finish(target);
    }

    /// Creates an active CRC-32C computation.
    ///
    /// @return an independent accumulator
    public static ChecksumAccumulator.Width32 crc32c() {
        return new JdkChecksum32("CRC-32C", new CRC32C());
    }

    /// Computes CRC-32C over the supplied input.
    ///
    /// @param source the complete input
    /// @return the raw 32-bit checksum
    public static int crc32c(byte[] source) {
        var accumulator = crc32c();
        accumulator.update(source);
        return accumulator.finishInt();
    }

    /// Computes CRC-32C over the supplied input.
    ///
    /// @param source the source array
    /// @param offset the first byte index
    /// @param length the number of bytes
    /// @return the raw 32-bit checksum
    /// @throws IndexOutOfBoundsException if the range is outside the array
    public static int crc32c(byte[] source, int offset, int length) {
        var accumulator = crc32c();
        accumulator.update(source, offset, length);
        return accumulator.finishInt();
    }

    /// Computes CRC-32C over the supplied input.
    ///
    /// @param source the input buffer, consumed through its limit
    /// @return the raw 32-bit checksum
    public static int crc32c(ByteBuffer source) {
        var accumulator = crc32c();
        accumulator.update(source);
        return accumulator.finishInt();
    }

    /// Computes CRC-32C and writes its canonical bytes to the target.
    ///
    /// The target is validated before source consumption. Its position advances by 4 bytes on success.
    ///
    /// @param source the input buffer, consumed through its limit
    /// @param target the writable destination with at least 4 bytes remaining
    /// @throws java.nio.ReadOnlyBufferException if the target is read-only
    /// @throws java.nio.BufferOverflowException if the target has insufficient remaining space
    public static void crc32c(ByteBuffer source, ByteBuffer target) {
        ChecksumValue.requireWritable(target, 4);
        var accumulator = crc32c();
        accumulator.update(source);
        accumulator.finish(target);
    }

    /// Creates an active SHA-256 computation.
    ///
    /// @return an independent accumulator
    public static ChecksumAccumulator sha256() {
        return new MessageDigestChecksum("SHA-256", 32);
    }

    /// Computes SHA-256 over the supplied input.
    ///
    /// @param source the complete input
    /// @return the immutable digest
    public static ChecksumValue sha256(byte[] source) {
        var accumulator = sha256();
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
    public static ChecksumValue sha256(byte[] source, int offset, int length) {
        var accumulator = sha256();
        accumulator.update(source, offset, length);
        return accumulator.finish();
    }

    /// Computes SHA-256 over the supplied input.
    ///
    /// @param source the input buffer, consumed through its limit
    /// @return the immutable digest
    public static ChecksumValue sha256(ByteBuffer source) {
        var accumulator = sha256();
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
    public static void sha256(ByteBuffer source, ByteBuffer target) {
        ChecksumValue.requireWritable(target, 32);
        var accumulator = sha256();
        accumulator.update(source);
        accumulator.finish(target);
    }
}
