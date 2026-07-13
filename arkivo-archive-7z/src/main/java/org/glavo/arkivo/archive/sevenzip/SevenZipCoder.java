// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Arrays;
import java.util.Objects;

/// Describes one coder in a 7z folder graph.
///
/// @param method the supported coder method
/// @param properties the raw coder properties
/// @param inputStreamCount the number of graph input streams consumed by the coder
/// @param outputStreamCount the number of graph output streams produced by the coder
/// @param firstInputStreamIndex the first graph input stream owned by the coder
/// @param firstOutputStreamIndex the first graph output stream owned by the coder
@NotNullByDefault
public record SevenZipCoder(
        SevenZipCoderMethod method,
        byte @Unmodifiable [] properties,
        int inputStreamCount,
        int outputStreamCount,
        int firstInputStreamIndex,
        int firstOutputStreamIndex
) {
    /// Validates and snapshots coder metadata.
    public SevenZipCoder {
        Objects.requireNonNull(method, "method");
        properties = Objects.requireNonNull(properties, "properties").clone();
        if (inputStreamCount <= 0 || outputStreamCount <= 0) {
            throw new IllegalArgumentException("Coder stream counts must be positive");
        }
        if (firstInputStreamIndex < 0 || firstOutputStreamIndex < 0) {
            throw new IllegalArgumentException("Coder stream indexes must be non-negative");
        }
    }

    /// Returns whether another coder has the same method, properties, and stream range.
    @Override
    public boolean equals(Object object) {
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

    /// Returns a copy of the raw coder properties.
    @Override
    public byte[] properties() {
        return properties.clone();
    }
}
