// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Arrays;
import java.util.Objects;

/// Stores the ordered coder pipeline for one 7z folder.
@NotNullByDefault
final class SevenZipFolderMethod {
    /// The ordered method IDs.
    private final byte @Unmodifiable [] @Unmodifiable [] methodIds;

    /// The ordered coder properties.
    private final byte @Unmodifiable [] @Unmodifiable [] properties;

    /// The unpack size produced by each ordered coder.
    private final long @Unmodifiable [] unpackSizes;

    /// Creates an ordered 7z folder method.
    SevenZipFolderMethod(byte[][] methodIds, byte[][] properties, long[] unpackSizes) {
        Objects.requireNonNull(methodIds, "methodIds");
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(unpackSizes, "unpackSizes");
        if (methodIds.length == 0) {
            throw new IllegalArgumentException("methodIds must not be empty");
        }
        if (methodIds.length != properties.length || methodIds.length != unpackSizes.length) {
            throw new IllegalArgumentException("methodIds, properties, and unpackSizes must have the same length");
        }
        this.methodIds = cloneMatrix(methodIds);
        this.properties = cloneMatrix(properties);
        this.unpackSizes = unpackSizes.clone();
        for (long unpackSize : this.unpackSizes) {
            if (unpackSize < 0) {
                throw new IllegalArgumentException("unpackSizes must not contain negative values");
            }
        }
    }

    /// Creates a single-coder folder method.
    static SevenZipFolderMethod single(byte[] methodId, byte[] properties, long unpackSize) {
        return new SevenZipFolderMethod(new byte[][]{methodId}, new byte[][]{properties}, new long[]{unpackSize});
    }

    /// Returns the number of coders in this pipeline.
    int coderCount() {
        return methodIds.length;
    }

    /// Returns a copy of the method ID for the coder at the given pipeline index.
    byte[] methodId(int index) {
        return methodIds[index].clone();
    }

    /// Returns a copy of the coder properties for the coder at the given pipeline index.
    byte[] properties(int index) {
        return properties[index].clone();
    }

    /// Returns the unpack size produced by the coder at the given pipeline index.
    long unpackSize(int index) {
        return unpackSizes[index];
    }

    /// Returns the final unpack size produced by this folder method.
    long finalUnpackSize() {
        return unpackSizes[unpackSizes.length - 1];
    }

    /// Returns a copy of the first method ID for compatibility with older internal callers.
    byte[] firstMethodId() {
        return methodId(0);
    }

    /// Returns a copy of the first coder properties for compatibility with older internal callers.
    byte[] firstProperties() {
        return properties(0);
    }

    /// Returns whether every coder in this pipeline uses the Copy method.
    boolean isCopyOnly() {
        for (byte[] methodId : methodIds) {
            if (!SevenZipLZMADecoder.isCopy(methodId)) {
                return false;
            }
        }
        return true;
    }

    /// Returns whether this method contains the given method ID.
    boolean containsMethod(byte[] expectedMethodId) {
        for (byte[] methodId : methodIds) {
            if (Arrays.equals(methodId, expectedMethodId)) {
                return true;
            }
        }
        return false;
    }

    /// Returns a deep copy of the given byte array matrix.
    private static byte[][] cloneMatrix(byte[][] value) {
        byte[][] result = new byte[value.length][];
        for (int index = 0; index < value.length; index++) {
            result[index] = Objects.requireNonNull(value[index], "value").clone();
        }
        return result;
    }
}
