// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.archive.sevenzip.SevenZipCoder;
import org.glavo.arkivo.archive.sevenzip.SevenZipCoderGraph;
import org.glavo.arkivo.archive.sevenzip.SevenZipCoderMethod;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/// Stores the complete coder graph for one 7z folder.
@NotNullByDefault
final class SevenZipFolderMethod {
    /// The method IDs in coder declaration order.
    private final byte @Unmodifiable [] @Unmodifiable [] methodIds;

    /// The coder properties in coder declaration order.
    private final byte @Unmodifiable [] @Unmodifiable [] properties;

    /// The number of input streams consumed by each coder.
    private final int @Unmodifiable [] inputStreamCounts;

    /// The number of output streams produced by each coder.
    private final int @Unmodifiable [] outputStreamCounts;

    /// The first folder input stream index owned by each coder.
    private final int @Unmodifiable [] firstInputStreamIndexes;

    /// The first folder output stream index owned by each coder.
    private final int @Unmodifiable [] firstOutputStreamIndexes;

    /// The bound output stream for each folder input stream, or `-1` for a packed input.
    private final int @Unmodifiable [] boundOutputByInput;

    /// The packed folder input stream indexes in physical pack-stream order.
    private final int @Unmodifiable [] packedInputStreamIndexes;

    /// The unpack size produced by every folder output stream.
    private final long @Unmodifiable [] unpackSizes;

    /// The coder that owns each folder output stream.
    private final int @Unmodifiable [] coderByOutput;

    /// The sole unbound folder output stream exposed as the folder result.
    private final int finalOutputStreamIndex;

    /// Creates a linear single-input, single-output coder pipeline.
    SevenZipFolderMethod(byte[][] methodIds, byte[][] properties, long[] unpackSizes) {
        this(
                methodIds,
                properties,
                ones(methodIds.length),
                ones(methodIds.length),
                linearBindInputs(methodIds.length),
                linearBindOutputs(methodIds.length),
                new int[]{0},
                unpackSizes
        );
    }

    /// Creates a complete 7z folder coder graph.
    private SevenZipFolderMethod(
            byte[][] methodIds,
            byte[][] properties,
            int[] inputStreamCounts,
            int[] outputStreamCounts,
            int[] bindPairInputs,
            int[] bindPairOutputs,
            int[] packedInputStreamIndexes,
            long[] unpackSizes
    ) {
        Objects.requireNonNull(methodIds, "methodIds");
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(inputStreamCounts, "inputStreamCounts");
        Objects.requireNonNull(outputStreamCounts, "outputStreamCounts");
        Objects.requireNonNull(bindPairInputs, "bindPairInputs");
        Objects.requireNonNull(bindPairOutputs, "bindPairOutputs");
        Objects.requireNonNull(packedInputStreamIndexes, "packedInputStreamIndexes");
        Objects.requireNonNull(unpackSizes, "unpackSizes");
        if (methodIds.length == 0) {
            throw new IllegalArgumentException("methodIds must not be empty");
        }
        if (methodIds.length != properties.length
                || methodIds.length != inputStreamCounts.length
                || methodIds.length != outputStreamCounts.length) {
            throw new IllegalArgumentException("coder arrays must have the same length");
        }
        if (bindPairInputs.length != bindPairOutputs.length) {
            throw new IllegalArgumentException("bind pair arrays must have the same length");
        }

        this.methodIds = cloneMatrix(methodIds);
        this.properties = cloneMatrix(properties);
        this.inputStreamCounts = inputStreamCounts.clone();
        this.outputStreamCounts = outputStreamCounts.clone();
        this.firstInputStreamIndexes = new int[methodIds.length];
        this.firstOutputStreamIndexes = new int[methodIds.length];

        int totalInputStreams = 0;
        int totalOutputStreams = 0;
        for (int coderIndex = 0; coderIndex < methodIds.length; coderIndex++) {
            int inputCount = this.inputStreamCounts[coderIndex];
            int outputCount = this.outputStreamCounts[coderIndex];
            if (inputCount <= 0 || outputCount <= 0) {
                throw new IllegalArgumentException("coder stream counts must be positive");
            }
            this.firstInputStreamIndexes[coderIndex] = totalInputStreams;
            this.firstOutputStreamIndexes[coderIndex] = totalOutputStreams;
            totalInputStreams = Math.addExact(totalInputStreams, inputCount);
            totalOutputStreams = Math.addExact(totalOutputStreams, outputCount);
        }
        if (bindPairInputs.length != totalOutputStreams - 1) {
            throw new IllegalArgumentException("a 7z folder must bind every output except one");
        }
        if (packedInputStreamIndexes.length != totalInputStreams - bindPairInputs.length) {
            throw new IllegalArgumentException("packed input count does not match the coder bindings");
        }
        if (unpackSizes.length != totalOutputStreams) {
            throw new IllegalArgumentException("unpackSizes must contain one value per output stream");
        }

        this.unpackSizes = unpackSizes.clone();
        for (long unpackSize : this.unpackSizes) {
            if (unpackSize < 0) {
                throw new IllegalArgumentException("unpackSizes must not contain negative values");
            }
        }

        int[] coderByOutput = new int[totalOutputStreams];
        for (int coderIndex = 0; coderIndex < methodIds.length; coderIndex++) {
            int firstOutput = this.firstOutputStreamIndexes[coderIndex];
            Arrays.fill(coderByOutput, firstOutput, firstOutput + this.outputStreamCounts[coderIndex], coderIndex);
        }
        this.coderByOutput = coderByOutput;

        int[] boundOutputByInput = new int[totalInputStreams];
        Arrays.fill(boundOutputByInput, -1);
        boolean[] outputIsBound = new boolean[totalOutputStreams];
        for (int index = 0; index < bindPairInputs.length; index++) {
            int inputStreamIndex = bindPairInputs[index];
            int outputStreamIndex = bindPairOutputs[index];
            requireIndex(inputStreamIndex, totalInputStreams, "bind pair input stream");
            requireIndex(outputStreamIndex, totalOutputStreams, "bind pair output stream");
            if (boundOutputByInput[inputStreamIndex] >= 0) {
                throw new IllegalArgumentException("bind pairs contain a duplicate input stream");
            }
            if (outputIsBound[outputStreamIndex]) {
                throw new IllegalArgumentException("bind pairs contain a duplicate output stream");
            }
            boundOutputByInput[inputStreamIndex] = outputStreamIndex;
            outputIsBound[outputStreamIndex] = true;
        }

        this.packedInputStreamIndexes = packedInputStreamIndexes.clone();
        boolean[] packedInputs = new boolean[totalInputStreams];
        for (int inputStreamIndex : this.packedInputStreamIndexes) {
            requireIndex(inputStreamIndex, totalInputStreams, "packed input stream");
            if (boundOutputByInput[inputStreamIndex] >= 0) {
                throw new IllegalArgumentException("a packed input stream must not be bound");
            }
            if (packedInputs[inputStreamIndex]) {
                throw new IllegalArgumentException("packed input streams contain a duplicate index");
            }
            packedInputs[inputStreamIndex] = true;
        }
        for (int inputStreamIndex = 0; inputStreamIndex < totalInputStreams; inputStreamIndex++) {
            if (boundOutputByInput[inputStreamIndex] < 0 && !packedInputs[inputStreamIndex]) {
                throw new IllegalArgumentException("packed input streams do not cover every unbound input");
            }
        }
        this.boundOutputByInput = boundOutputByInput;

        int finalOutput = -1;
        for (int outputStreamIndex = 0; outputStreamIndex < totalOutputStreams; outputStreamIndex++) {
            if (!outputIsBound[outputStreamIndex]) {
                if (finalOutput >= 0) {
                    throw new IllegalArgumentException("a 7z folder must expose exactly one output stream");
                }
                finalOutput = outputStreamIndex;
            }
        }
        if (finalOutput < 0) {
            throw new IllegalArgumentException("a 7z folder has no final output stream");
        }
        this.finalOutputStreamIndex = finalOutput;
        validateConnectedAcyclicGraph();
    }

    /// Creates a complete 7z folder coder graph.
    static SevenZipFolderMethod graph(
            byte[][] methodIds,
            byte[][] properties,
            int[] inputStreamCounts,
            int[] outputStreamCounts,
            int[] bindPairInputs,
            int[] bindPairOutputs,
            int[] packedInputStreamIndexes,
            long[] unpackSizes
    ) {
        return new SevenZipFolderMethod(
                methodIds,
                properties,
                inputStreamCounts,
                outputStreamCounts,
                bindPairInputs,
                bindPairOutputs,
                packedInputStreamIndexes,
                unpackSizes
        );
    }

    /// Creates a single-coder folder method.
    static SevenZipFolderMethod single(byte[] methodId, byte[] properties, long unpackSize) {
        return new SevenZipFolderMethod(new byte[][]{methodId}, new byte[][]{properties}, new long[]{unpackSize});
    }

    /// Returns a copy of the method ID for the given coder.
    byte[] methodId(int coderIndex) {
        return methodIds[coderIndex].clone();
    }

    /// Returns a copy of the properties for the given coder.
    byte[] properties(int coderIndex) {
        return properties[coderIndex].clone();
    }

    /// Returns the number of input streams consumed by the given coder.
    int inputStreamCount(int coderIndex) {
        return inputStreamCounts[coderIndex];
    }

    /// Returns the number of output streams produced by the given coder.
    int outputStreamCount(int coderIndex) {
        return outputStreamCounts[coderIndex];
    }

    /// Returns the first folder input stream index consumed by the given coder.
    int firstInputStreamIndex(int coderIndex) {
        return firstInputStreamIndexes[coderIndex];
    }

    /// Returns the first folder output stream index produced by the given coder.
    int firstOutputStreamIndex(int coderIndex) {
        return firstOutputStreamIndexes[coderIndex];
    }

    /// Returns the bound output stream for a folder input, or `-1` for a packed input.
    int boundOutputStreamIndex(int inputStreamIndex) {
        return boundOutputByInput[inputStreamIndex];
    }

    /// Returns the physical packed stream ordinal for an unbound folder input, or `-1` when bound.
    int packedStreamOrdinal(int inputStreamIndex) {
        for (int ordinal = 0; ordinal < packedInputStreamIndexes.length; ordinal++) {
            if (packedInputStreamIndexes[ordinal] == inputStreamIndex) {
                return ordinal;
            }
        }
        return -1;
    }

    /// Returns the number of physical packed streams consumed by this folder.
    int packedStreamCount() {
        return packedInputStreamIndexes.length;
    }

    /// Returns the coder that owns the given folder output stream.
    int coderIndexForOutput(int outputStreamIndex) {
        return coderByOutput[outputStreamIndex];
    }

    /// Returns the unpack size produced by the given folder output stream.
    long unpackSize(int outputStreamIndex) {
        return unpackSizes[outputStreamIndex];
    }

    /// Returns the sole unbound output stream exposed as the folder result.
    int finalOutputStreamIndex() {
        return finalOutputStreamIndex;
    }

    /// Returns the final unpack size produced by this folder graph.
    long finalUnpackSize() {
        return unpackSizes[finalOutputStreamIndex];
    }

    /// Returns a copy of the first declared method ID for compatibility with entry metadata APIs.
    byte[] firstMethodId() {
        return methodId(0);
    }

    /// Returns a copy of the first declared coder properties for compatibility with entry metadata APIs.
    byte[] firstProperties() {
        return properties(0);
    }

    /// Returns whether every coder in this graph uses the Copy method.
    boolean isCopyOnly() {
        for (byte[] methodId : methodIds) {
            if (!SevenZipLZMADecoder.isCopy(methodId)) {
                return false;
            }
        }
        return true;
    }

    /// Returns whether this graph contains the given method ID.
    boolean containsMethod(byte[] expectedMethodId) {
        for (byte[] methodId : methodIds) {
            if (Arrays.equals(methodId, expectedMethodId)) {
                return true;
            }
        }
        return false;
    }

    /// Returns an immutable public snapshot of this coder graph.
    SevenZipCoderGraph coderGraph() {
        ArrayList<SevenZipCoder> coders = new ArrayList<>(methodIds.length);
        for (int coderIndex = 0; coderIndex < methodIds.length; coderIndex++) {
            coders.add(new SevenZipCoder(
                    SevenZipCoderMethod.fromMethodId(methodIds[coderIndex]),
                    properties[coderIndex],
                    inputStreamCounts[coderIndex],
                    outputStreamCounts[coderIndex],
                    firstInputStreamIndexes[coderIndex],
                    firstOutputStreamIndexes[coderIndex]
            ));
        }
        int[] packedStreamOrdinalByInput = new int[boundOutputByInput.length];
        Arrays.fill(packedStreamOrdinalByInput, -1);
        for (int ordinal = 0; ordinal < packedInputStreamIndexes.length; ordinal++) {
            packedStreamOrdinalByInput[packedInputStreamIndexes[ordinal]] = ordinal;
        }
        return new SevenZipCoderGraph(
                List.copyOf(coders),
                boundOutputByInput,
                packedStreamOrdinalByInput,
                unpackSizes,
                finalOutputStreamIndex
        );
    }
    /// Validates that every coder and packed input contributes to the final output without cycles.
    private void validateConnectedAcyclicGraph() {
        byte[] coderStates = new byte[methodIds.length];
        boolean[] usedPackedStreams = new boolean[packedInputStreamIndexes.length];
        visitOutput(finalOutputStreamIndex, coderStates, usedPackedStreams);
        for (byte state : coderStates) {
            if (state != 2) {
                throw new IllegalArgumentException("the 7z folder coder graph is disconnected");
            }
        }
        for (boolean used : usedPackedStreams) {
            if (!used) {
                throw new IllegalArgumentException("the 7z folder has an unused packed input stream");
            }
        }
    }

    /// Visits the coder producing one output while validating graph ownership and cycles.
    private void visitOutput(int outputStreamIndex, byte[] coderStates, boolean[] usedPackedStreams) {
        int coderIndex = coderByOutput[outputStreamIndex];
        byte state = coderStates[coderIndex];
        if (state == 1) {
            throw new IllegalArgumentException("the 7z folder coder bindings contain a cycle");
        }
        if (state == 2) {
            return;
        }
        coderStates[coderIndex] = 1;
        int firstInput = firstInputStreamIndexes[coderIndex];
        int inputCount = inputStreamCounts[coderIndex];
        for (int index = 0; index < inputCount; index++) {
            int inputStreamIndex = firstInput + index;
            int boundOutput = boundOutputByInput[inputStreamIndex];
            if (boundOutput >= 0) {
                visitOutput(boundOutput, coderStates, usedPackedStreams);
            } else {
                int ordinal = packedStreamOrdinal(inputStreamIndex);
                if (ordinal < 0) {
                    throw new IllegalArgumentException("the 7z folder input has no source");
                }
                usedPackedStreams[ordinal] = true;
            }
        }
        coderStates[coderIndex] = 2;
    }

    /// Returns an array of the given length filled with ones.
    private static int[] ones(int length) {
        int[] result = new int[length];
        Arrays.fill(result, 1);
        return result;
    }

    /// Returns bind-pair input indexes for a linear coder pipeline.
    private static int[] linearBindInputs(int coderCount) {
        int[] result = new int[Math.max(0, coderCount - 1)];
        for (int index = 0; index < result.length; index++) {
            result[index] = index + 1;
        }
        return result;
    }

    /// Returns bind-pair output indexes for a linear coder pipeline.
    private static int[] linearBindOutputs(int coderCount) {
        int[] result = new int[Math.max(0, coderCount - 1)];
        for (int index = 0; index < result.length; index++) {
            result[index] = index;
        }
        return result;
    }

    /// Requires an index to address the given stream count.
    private static void requireIndex(int index, int count, String description) {
        if (index < 0 || index >= count) {
            throw new IllegalArgumentException(description + " index is out of range");
        }
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
