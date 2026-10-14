// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.checksum.internal;

import org.glavo.arkivo.checksum.ChecksumAccumulator;
import org.glavo.arkivo.checksum.ChecksumValue;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/// Accumulates a fixed-size JCA message digest.
@NotNullByDefault
public final class MessageDigestChecksum implements ChecksumAccumulator {
    /// The diagnostic algorithm name.
    private final String name;

    /// The fixed digest byte size.
    private final int checksumSize;

    /// The mutable JCA digest state.
    private final MessageDigest digest;

    /// The cached completed value, or `null` while active.
    private @Nullable ChecksumValue result;

    /// Creates an active digest with the requested result width.
    ///
    /// @param name the JCA algorithm name
    /// @param checksumSize the positive digest byte size
    /// @throws IllegalArgumentException if the size is nonpositive or differs from the digest size
    /// @throws AssertionError if the required digest is unavailable
    public MessageDigestChecksum(String name, int checksumSize) {
        this.name = Objects.requireNonNull(name, "name");
        if (checksumSize <= 0) {
            throw new IllegalArgumentException("checksumSize must be positive");
        }
        this.checksumSize = checksumSize;
        try {
            digest = MessageDigest.getInstance(name);
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("Required message digest is unavailable: " + name, exception);
        }
        if (digest.getDigestLength() != checksumSize) {
            throw new IllegalArgumentException(
                    name + " has digest size " + digest.getDigestLength() + " instead of " + checksumSize);
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public int checksumSize() {
        return checksumSize;
    }

    /// Adds one byte.
    ///
    /// @param value the byte to add
    @Override
    public void update(byte value) {
        requireActive();
        digest.update(value);
    }

    /// Adds one array range.
    ///
    /// @param source the source array
    /// @param offset the first byte index
    /// @param length the number of bytes
    @Override
    public void update(byte[] source, int offset, int length) {
        Objects.requireNonNull(source, "source");
        Objects.checkFromIndexSize(offset, length, source.length);
        requireActive();
        digest.update(source, offset, length);
    }

    /// Adds all remaining buffer bytes.
    ///
    /// @param source the source buffer
    @Override
    public void update(ByteBuffer source) {
        Objects.requireNonNull(source, "source");
        requireActive();
        digest.update(source);
    }

    /// Completes and caches the digest.
    ///
    /// @return the immutable digest value
    @Override
    public ChecksumValue finish() {
        @Nullable ChecksumValue current = result;
        if (current == null) {
            current = ChecksumValue.ofBytes(digest.digest());
            result = current;
        }
        return current;
    }

    /// Restores active empty digest state.
    @Override
    public void reset() {
        digest.reset();
        result = null;
    }

    /// Requires this computation to remain active.
    private void requireActive() {
        if (result != null) {
            throw new IllegalStateException("Checksum computation is finished");
        }
    }
}
