// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.checksum;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/// Represents an immutable checksum value in its canonical byte representation.
///
/// The first byte is the most significant byte for numeric checksums. Format-specific truncation and byte order are
/// deliberately not part of this type. Instances never expose mutable access to their storage.
@NotNullByDefault
public final class ChecksumValue {
    /// The owned canonical checksum bytes.
    private final byte @Unmodifiable [] bytes;

    /// Creates a value that takes ownership of an already isolated nonempty array.
    ///
    /// @param bytes the owned canonical checksum bytes
    private ChecksumValue(byte[] bytes) {
        this.bytes = bytes;
    }

    /// Copies a nonempty canonical checksum byte array into a value.
    ///
    /// @param bytes the canonical checksum bytes
    /// @return an immutable checksum value
    /// @throws IllegalArgumentException if `bytes` is empty
    public static ChecksumValue copyOf(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0) {
            throw new IllegalArgumentException("A checksum value must contain at least one byte");
        }
        return new ChecksumValue(bytes.clone());
    }

    /// Copies the remaining canonical checksum bytes without changing the source buffer.
    ///
    /// @param source the buffer whose remaining bytes form the value
    /// @return an immutable checksum value
    /// @throws IllegalArgumentException if `source` has no remaining bytes
    public static ChecksumValue copyOf(ByteBuffer source) {
        ByteBuffer view = Objects.requireNonNull(source, "source").slice();
        if (!view.hasRemaining()) {
            throw new IllegalArgumentException("A checksum value must contain at least one byte");
        }
        byte[] bytes = new byte[view.remaining()];
        view.get(bytes);
        return new ChecksumValue(bytes);
    }

    /// Creates the canonical big-endian representation of a 32-bit value.
    ///
    /// @param value the complete 32-bit pattern
    /// @return an immutable four-byte checksum value
    public static ChecksumValue ofInt(int value) {
        return new ChecksumValue(new byte[]{
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value
        });
    }

    /// Creates a canonical big-endian value from the low-order bytes of a `long`.
    ///
    /// For sizes below eight bytes, `value` must be a nonnegative unsigned value that fits in the requested width.
    /// An eight-byte value preserves all bits of the `long` without assigning signed numeric meaning to them.
    ///
    /// @param value    the checksum value or complete 64-bit pattern
    /// @param byteSize the result size from one through eight bytes
    /// @return an immutable checksum value
    /// @throws IllegalArgumentException if the size is outside the supported range or the value does not fit
    public static ChecksumValue ofLong(long value, int byteSize) {
        if (byteSize < 1 || byteSize > Long.BYTES) {
            throw new IllegalArgumentException("byteSize must be between 1 and 8");
        }
        if (byteSize < Long.BYTES && value >>> (byteSize * Byte.SIZE) != 0L) {
            throw new IllegalArgumentException("value does not fit in " + byteSize + " bytes");
        }
        byte[] bytes = new byte[byteSize];
        long remaining = value;
        for (int index = byteSize - 1; index >= 0; index--) {
            bytes[index] = (byte) remaining;
            remaining >>>= Byte.SIZE;
        }
        return new ChecksumValue(bytes);
    }

    /// Returns the exact number of bytes in this value.
    ///
    /// @return the positive checksum byte size
    public int byteSize() {
        return bytes.length;
    }

    /// Returns a new read-only view of the canonical bytes.
    ///
    /// The returned buffer has position zero and a limit equal to [#byteSize()]. Its content remains valid for the
    /// lifetime of this value.
    ///
    /// @return a read-only checksum view
    public @UnmodifiableView ByteBuffer buffer() {
        return ByteBuffer.wrap(bytes).asReadOnlyBuffer();
    }

    /// Returns a mutable copy of the canonical bytes.
    ///
    /// @return an independent byte array
    public byte[] toByteArray() {
        return bytes.clone();
    }

    /// Returns this value as a `long` bit pattern when it contains at most eight bytes.
    ///
    /// Values shorter than eight bytes are returned as nonnegative unsigned integers. An eight-byte value preserves
    /// all bits and may therefore be negative in Java's signed `long` representation.
    ///
    /// @return the checksum value carried by a `long`
    /// @throws IllegalStateException if this value contains more than eight bytes
    public long longValue() {
        if (bytes.length > Long.BYTES) {
            throw new IllegalStateException("A " + bytes.length + "-byte checksum does not fit in a long");
        }
        long value = 0L;
        for (byte current : bytes) {
            value = value << Byte.SIZE | Byte.toUnsignedLong(current);
        }
        return value;
    }

    /// Writes the complete canonical representation into a target buffer.
    ///
    /// On success the target position advances by [#byteSize()] and its limit is unchanged. If the target is read-only
    /// or too small, the method throws before changing its position.
    ///
    /// @param target the destination buffer
    /// @throws ReadOnlyBufferException if `target` is read-only
    /// @throws BufferOverflowException if `target` has insufficient remaining space
    public void writeTo(ByteBuffer target) {
        requireWritable(target, bytes.length);
        target.put(bytes);
    }

    /// Returns the lowercase hexadecimal canonical representation.
    ///
    /// @return a string containing two hexadecimal digits per byte
    public String toHexString() {
        return HexFormat.of().formatHex(bytes);
    }

    /// Compares canonical checksum bytes.
    ///
    /// @param other the object to compare
    /// @return whether `other` contains the same canonical bytes
    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || other instanceof ChecksumValue value && Arrays.equals(bytes, value.bytes);
    }

    /// Returns the canonical-byte hash code.
    ///
    /// @return this value's hash code
    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    /// Returns the lowercase hexadecimal canonical representation.
    ///
    /// @return the same representation as [#toHexString()]
    @Override
    public String toString() {
        return toHexString();
    }

    /// Validates a target before a checksum computation changes state or consumes input.
    ///
    /// @param target   the destination buffer
    /// @param required the required remaining byte count
    /// @throws ReadOnlyBufferException if `target` is read-only
    /// @throws BufferOverflowException if `target` has insufficient remaining space
    static void requireWritable(ByteBuffer target, int required) {
        Objects.requireNonNull(target, "target");
        if (target.isReadOnly()) {
            throw new ReadOnlyBufferException();
        }
        if (target.remaining() < required) {
            throw new BufferOverflowException();
        }
    }
}
