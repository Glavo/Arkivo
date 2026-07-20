// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.checksum.internal;

import org.glavo.arkivo.checksum.ChecksumAccumulator;
import org.glavo.arkivo.checksum.ChecksumAlgorithm;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.zip.Checksum;

/// Adapts one mandatory JDK 32-bit checksum implementation to the Arkivo lifecycle.
@NotNullByDefault
public final class JdkChecksumAlgorithm32 implements ChecksumAlgorithm.Width32 {
    /// The diagnostic algorithm name.
    private final String name;

    /// Creates fresh JDK computation state.
    private final Supplier<? extends Checksum> factory;

    /// Creates an immutable JDK-backed algorithm.
    ///
    /// @param name    the diagnostic algorithm name
    /// @param factory the state factory
    public JdkChecksumAlgorithm32(String name, Supplier<? extends Checksum> factory) {
        this.name = Objects.requireNonNull(name, "name");
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    /// Returns the configured diagnostic name.
    ///
    /// @return the algorithm name
    @Override
    public String name() {
        return name;
    }

    /// Creates fresh active state.
    ///
    /// @return an independent accumulator
    @Override
    public ChecksumAccumulator.Width32 newAccumulator() {
        return new Accumulator(this, factory.get());
    }

    /// Returns the diagnostic algorithm name.
    ///
    /// @return the algorithm name
    @Override
    public String toString() {
        return name;
    }

    /// Holds one JDK checksum computation and its explicit finished state.
    @NotNullByDefault
    private static final class Accumulator implements ChecksumAccumulator.Width32 {
        /// The immutable algorithm configuration.
        private final JdkChecksumAlgorithm32 algorithm;

        /// The mutable JDK checksum state.
        private final Checksum checksum;

        /// The cached raw result bits.
        private int result;

        /// Whether this computation has finished.
        private boolean finished;

        /// Creates active computation state.
        ///
        /// @param algorithm the immutable algorithm
        /// @param checksum  the fresh JDK state
        private Accumulator(JdkChecksumAlgorithm32 algorithm, Checksum checksum) {
            this.algorithm = algorithm;
            this.checksum = Objects.requireNonNull(checksum, "checksum");
        }

        /// Returns the creating algorithm.
        ///
        /// @return the immutable algorithm
        @Override
        public ChecksumAlgorithm.Width32 algorithm() {
            return algorithm;
        }

        /// Adds one byte.
        ///
        /// @param value the byte to add
        @Override
        public void update(byte value) {
            requireActive();
            checksum.update(Byte.toUnsignedInt(value));
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
            checksum.update(source, offset, length);
        }

        /// Adds all remaining buffer bytes.
        ///
        /// @param source the source buffer
        @Override
        public void update(ByteBuffer source) {
            Objects.requireNonNull(source, "source");
            requireActive();
            checksum.update(source);
        }

        /// Completes and returns the raw 32-bit pattern.
        ///
        /// @return the completed result bits
        @Override
        public int finishInt() {
            if (!finished) {
                result = (int) checksum.getValue();
                finished = true;
            }
            return result;
        }

        /// Restores fresh JDK state.
        @Override
        public void reset() {
            checksum.reset();
            result = 0;
            finished = false;
        }

        /// Requires this computation to remain active.
        ///
        /// @throws IllegalStateException if it has finished
        private void requireActive() {
            if (finished) {
                throw new IllegalStateException("Checksum computation is finished");
            }
        }
    }
}
