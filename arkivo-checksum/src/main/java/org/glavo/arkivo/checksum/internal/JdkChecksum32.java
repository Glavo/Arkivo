// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.checksum.internal;

import org.glavo.arkivo.checksum.ChecksumAccumulator;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.zip.Checksum;

/// Accumulates a 32-bit checksum using a JDK implementation.
@NotNullByDefault
public final class JdkChecksum32 implements ChecksumAccumulator.Width32 {
    /// The diagnostic algorithm name.
    private final String name;

    /// The mutable JDK checksum state.
    private final Checksum checksum;

    /// The cached raw result bits.
    private int result;

    /// Whether this computation has finished.
    private boolean finished;

    /// Creates active computation state owning the supplied JDK checksum.
    ///
    /// @param name the diagnostic algorithm name
    /// @param checksum the fresh JDK checksum, not shared with other computations
    public JdkChecksum32(String name, Checksum checksum) {
        this.name = Objects.requireNonNull(name, "name");
        this.checksum = Objects.requireNonNull(checksum, "checksum");
    }

    @Override
    public String name() {
        return name;
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
