// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Arrays;
import java.util.Objects;

/// Describes one immutable coder in a 7z folder graph.
@NotNullByDefault
public final class SevenZipCoder {
    /// The supported coder method.
    private final SevenZipCoderMethod method;

    /// The immutable raw coder properties.
    private final byte @Unmodifiable [] properties;

    /// The number of graph input streams consumed by the coder.
    private final int inputStreamCount;

    /// The number of graph output streams produced by the coder.
    private final int outputStreamCount;

    /// The first graph input stream owned by the coder.
    private final int firstInputStreamIndex;

    /// The first graph output stream owned by the coder.
    private final int firstOutputStreamIndex;

    /// Validates and snapshots coder metadata.
    ///
    /// @param method                 the recognized coder method
    /// @param properties             the raw coder-property bytes to copy
    /// @param inputStreamCount       the positive number of graph inputs consumed
    /// @param outputStreamCount      the positive number of graph outputs produced
    /// @param firstInputStreamIndex  the non-negative first graph input index
    /// @param firstOutputStreamIndex the non-negative first graph output index
    /// @throws IllegalArgumentException if a stream count is not positive or a first index is negative
    public SevenZipCoder(
            SevenZipCoderMethod method,
            byte[] properties,
            int inputStreamCount,
            int outputStreamCount,
            int firstInputStreamIndex,
            int firstOutputStreamIndex
    ) {
        this.method = Objects.requireNonNull(method, "method");
        this.properties = Objects.requireNonNull(properties, "properties").clone();
        if (inputStreamCount <= 0 || outputStreamCount <= 0) {
            throw new IllegalArgumentException("Coder stream counts must be positive");
        }
        if (firstInputStreamIndex < 0 || firstOutputStreamIndex < 0) {
            throw new IllegalArgumentException("Coder stream indexes must be non-negative");
        }
        this.inputStreamCount = inputStreamCount;
        this.outputStreamCount = outputStreamCount;
        this.firstInputStreamIndex = firstInputStreamIndex;
        this.firstOutputStreamIndex = firstOutputStreamIndex;
    }

    /// Returns the supported coder method.
    ///
    /// @return the recognized coder method
    public SevenZipCoderMethod method() {
        return method;
    }

    /// Returns a copy of the raw coder properties.
    ///
    /// @return a new array containing the encoded coder properties
    public byte[] properties() {
        return properties.clone();
    }

    /// Returns the number of graph input streams consumed by the coder.
    ///
    /// @return the positive input stream count
    public int inputStreamCount() {
        return inputStreamCount;
    }

    /// Returns the number of graph output streams produced by the coder.
    ///
    /// @return the positive output stream count
    public int outputStreamCount() {
        return outputStreamCount;
    }

    /// Returns the first graph input stream owned by the coder.
    ///
    /// @return the non-negative first input stream index
    public int firstInputStreamIndex() {
        return firstInputStreamIndex;
    }

    /// Returns the first graph output stream owned by the coder.
    ///
    /// @return the non-negative first output stream index
    public int firstOutputStreamIndex() {
        return firstOutputStreamIndex;
    }

    /// Returns whether another coder has the same method, properties, and stream range.
    @Override
    public boolean equals(@Nullable Object object) {
        return object instanceof SevenZipCoder other
                && method == other.method
                && Arrays.equals(properties, other.properties)
                && inputStreamCount == other.inputStreamCount
                && outputStreamCount == other.outputStreamCount
                && firstInputStreamIndex == other.firstInputStreamIndex
                && firstOutputStreamIndex == other.firstOutputStreamIndex;
    }

    /// Returns a content-based hash code.
    @Override
    public int hashCode() {
        int result = Objects.hash(
                method,
                inputStreamCount,
                outputStreamCount,
                firstInputStreamIndex,
                firstOutputStreamIndex
        );
        return 31 * result + Arrays.hashCode(properties);
    }

    /// Returns a readable coder metadata representation.
    @Override
    public String toString() {
        return "SevenZipCoder[method=" + method
                + ", properties=" + Arrays.toString(properties)
                + ", inputStreamCount=" + inputStreamCount
                + ", outputStreamCount=" + outputStreamCount
                + ", firstInputStreamIndex=" + firstInputStreamIndex
                + ", firstOutputStreamIndex=" + firstOutputStreamIndex
                + "]";
    }
}
