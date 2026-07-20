// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.checksum.internal;

import org.glavo.arkivo.checksum.ChecksumAccumulator;
import org.glavo.arkivo.checksum.ChecksumAlgorithm;
import org.glavo.arkivo.checksum.ChecksumValue;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/// Adapts one mandatory fixed-size JCA message digest to the checksum lifecycle.
@NotNullByDefault
public final class MessageDigestChecksumAlgorithm implements ChecksumAlgorithm {
    /// The JCA algorithm name.
    private final String name;

    /// The fixed digest byte size.
    private final int checksumSize;

    /// Creates a fixed-size mandatory digest algorithm.
    ///
    /// @param name         the JCA and diagnostic name
    /// @param checksumSize the positive fixed digest size
    public MessageDigestChecksumAlgorithm(String name, int checksumSize) {
        this.name = Objects.requireNonNull(name, "name");
        if (checksumSize <= 0) {
            throw new IllegalArgumentException("checksumSize must be positive");
        }
        this.checksumSize = checksumSize;
        MessageDigest probe = newDigest();
        if (probe.getDigestLength() != checksumSize) {
            throw new IllegalArgumentException(
                    name + " has digest size " + probe.getDigestLength() + " instead of " + checksumSize
            );
        }
    }

    /// Returns the JCA algorithm name.
    ///
    /// @return the algorithm name
    @Override
    public String name() {
        return name;
    }

    /// Returns the fixed digest size.
    ///
    /// @return the positive byte size
    @Override
    public int checksumSize() {
        return checksumSize;
    }

    /// Creates fresh digest state.
    ///
    /// @return an independent accumulator
    @Override
    public ChecksumAccumulator newAccumulator() {
        return new Accumulator(this, newDigest());
    }

    /// Returns the JCA algorithm name.
    ///
    /// @return the algorithm name
    @Override
    public String toString() {
        return name;
    }

    /// Creates the mandatory JCA digest implementation.
    ///
    /// @return fresh digest state
    /// @throws AssertionError if the Java runtime violates its mandatory-algorithm requirement
    private MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(name);
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("Mandatory message digest is unavailable: " + name, exception);
        }
    }

    /// Holds one digest computation and its immutable completed value.
    @NotNullByDefault
    private static final class Accumulator implements ChecksumAccumulator {
        /// The immutable algorithm configuration.
        private final MessageDigestChecksumAlgorithm algorithm;

        /// The mutable JCA digest state.
        private final MessageDigest digest;

        /// The cached completed value, or `null` while active.
        private @Nullable ChecksumValue result;

        /// Creates active digest state.
        ///
        /// @param algorithm the immutable algorithm
        /// @param digest    the fresh JCA state
        private Accumulator(MessageDigestChecksumAlgorithm algorithm, MessageDigest digest) {
            this.algorithm = algorithm;
            this.digest = digest;
        }

        /// Returns the creating algorithm.
        ///
        /// @return the immutable algorithm
        @Override
        public ChecksumAlgorithm algorithm() {
            return algorithm;
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
            ChecksumValue current = result;
            if (current == null) {
                current = ChecksumValue.copyOf(digest.digest());
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
}
