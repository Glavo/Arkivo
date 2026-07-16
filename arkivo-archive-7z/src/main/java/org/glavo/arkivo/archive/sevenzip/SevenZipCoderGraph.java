// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/// Provides an immutable snapshot of a complete 7z folder coder graph.
@NotNullByDefault
public final class SevenZipCoderGraph {
    /// The coders in 7z declaration order.
    private final @Unmodifiable List<SevenZipCoder> coders;

    /// The bound output stream for each graph input, or `-1` for a packed input.
    private final int @Unmodifiable [] boundOutputByInput;

    /// The physical packed-stream ordinal for each graph input, or `-1` for a bound input.
    private final int @Unmodifiable [] packedStreamOrdinalByInput;

    /// The unpack size produced by every graph output.
    private final long @Unmodifiable [] unpackSizes;

    /// The number of physical packed input streams.
    private final int packedStreamCount;

    /// The sole unbound graph output exposed as the folder result.
    private final int finalOutputStreamIndex;

    /// Creates and validates an immutable coder graph snapshot.
    public SevenZipCoderGraph(
            List<SevenZipCoder> coders,
            int[] boundOutputByInput,
            int[] packedStreamOrdinalByInput,
            long[] unpackSizes,
            int finalOutputStreamIndex
    ) {
        this.coders = List.copyOf(Objects.requireNonNull(coders, "coders"));
        this.boundOutputByInput = Objects.requireNonNull(boundOutputByInput, "boundOutputByInput").clone();
        this.packedStreamOrdinalByInput = Objects.requireNonNull(
                packedStreamOrdinalByInput,
                "packedStreamOrdinalByInput"
        ).clone();
        this.unpackSizes = Objects.requireNonNull(unpackSizes, "unpackSizes").clone();
        if (this.coders.isEmpty()) {
            throw new IllegalArgumentException("coders must not be empty");
        }

        int inputStreamCount = 0;
        int outputStreamCount = 0;
        for (SevenZipCoder coder : this.coders) {
            if (coder.firstInputStreamIndex() != inputStreamCount
                    || coder.firstOutputStreamIndex() != outputStreamCount) {
                throw new IllegalArgumentException("Coder stream ranges must be contiguous in declaration order");
            }
            inputStreamCount = Math.addExact(inputStreamCount, coder.inputStreamCount());
            outputStreamCount = Math.addExact(outputStreamCount, coder.outputStreamCount());
        }
        if (this.boundOutputByInput.length != inputStreamCount
                || this.packedStreamOrdinalByInput.length != inputStreamCount) {
            throw new IllegalArgumentException("Input source arrays must contain one value per graph input");
        }
        if (this.unpackSizes.length != outputStreamCount) {
            throw new IllegalArgumentException("unpackSizes must contain one value per graph output");
        }
        Objects.checkIndex(finalOutputStreamIndex, outputStreamCount);

        boolean[] boundOutputs = new boolean[outputStreamCount];
        int maximumPackedOrdinal = -1;
        for (int inputIndex = 0; inputIndex < inputStreamCount; inputIndex++) {
            int boundOutput = this.boundOutputByInput[inputIndex];
            int packedOrdinal = this.packedStreamOrdinalByInput[inputIndex];
            if (boundOutput < -1 || packedOrdinal < -1 || packedOrdinal >= inputStreamCount) {
                throw new IllegalArgumentException("Input source indexes are out of range");
            }
            if ((boundOutput >= 0) == (packedOrdinal >= 0)) {
                throw new IllegalArgumentException("Every graph input must be either bound or packed");
            }
            if (boundOutput >= 0) {
                Objects.checkIndex(boundOutput, outputStreamCount);
                if (boundOutputs[boundOutput]) {
                    throw new IllegalArgumentException("A graph output cannot be bound more than once");
                }
                boundOutputs[boundOutput] = true;
            } else {
                maximumPackedOrdinal = Math.max(maximumPackedOrdinal, packedOrdinal);
            }
        }
        if (maximumPackedOrdinal < 0) {
            throw new IllegalArgumentException("A 7z coder graph must have at least one packed input");
        }
        boolean[] packedOrdinals = new boolean[maximumPackedOrdinal + 1];
        for (int packedOrdinal : this.packedStreamOrdinalByInput) {
            if (packedOrdinal >= 0) {
                if (packedOrdinals[packedOrdinal]) {
                    throw new IllegalArgumentException("Packed-stream ordinals must be unique");
                }
                packedOrdinals[packedOrdinal] = true;
            }
        }
        for (boolean present : packedOrdinals) {
            if (!present) {
                throw new IllegalArgumentException("Packed-stream ordinals must be contiguous");
            }
        }
        for (int outputIndex = 0; outputIndex < outputStreamCount; outputIndex++) {
            if (boundOutputs[outputIndex] == (outputIndex == finalOutputStreamIndex)) {
                throw new IllegalArgumentException("A 7z coder graph must expose exactly its declared final output");
            }
            if (this.unpackSizes[outputIndex] < 0) {
                throw new IllegalArgumentException("Unpack sizes must be non-negative");
            }
        }
        int[] coderByOutput = new int[outputStreamCount];
        for (int coderIndex = 0; coderIndex < this.coders.size(); coderIndex++) {
            SevenZipCoder coder = this.coders.get(coderIndex);
            Arrays.fill(
                    coderByOutput,
                    coder.firstOutputStreamIndex(),
                    coder.firstOutputStreamIndex() + coder.outputStreamCount(),
                    coderIndex
            );
        }
        validateConnectedAcyclicGraph(coderByOutput, packedOrdinals.length, finalOutputStreamIndex);
        this.packedStreamCount = packedOrdinals.length;
        this.finalOutputStreamIndex = finalOutputStreamIndex;
    }

    /// Validates that every coder and packed input contributes to the final output without cycles.
    private void validateConnectedAcyclicGraph(
            int[] coderByOutput,
            int packedStreamCount,
            int finalOutputStreamIndex
    ) {
        byte[] coderStates = new byte[coders.size()];
        boolean[] usedPackedStreams = new boolean[packedStreamCount];
        visitOutput(finalOutputStreamIndex, coderByOutput, coderStates, usedPackedStreams);
        for (byte state : coderStates) {
            if (state != 2) {
                throw new IllegalArgumentException("The 7z coder graph is disconnected");
            }
        }
        for (boolean used : usedPackedStreams) {
            if (!used) {
                throw new IllegalArgumentException("The 7z coder graph has an unused packed input");
            }
        }
    }

    /// Visits the coder producing one output while validating graph ownership and cycles.
    private void visitOutput(
            int outputStreamIndex,
            int[] coderByOutput,
            byte[] coderStates,
            boolean[] usedPackedStreams
    ) {
        int coderIndex = coderByOutput[outputStreamIndex];
        byte state = coderStates[coderIndex];
        if (state == 1) {
            throw new IllegalArgumentException("The 7z coder graph contains a cycle");
        }
        if (state == 2) {
            return;
        }
        coderStates[coderIndex] = 1;
        SevenZipCoder coder = coders.get(coderIndex);
        int firstInput = coder.firstInputStreamIndex();
        for (int offset = 0; offset < coder.inputStreamCount(); offset++) {
            int inputStreamIndex = firstInput + offset;
            int boundOutput = boundOutputByInput[inputStreamIndex];
            if (boundOutput >= 0) {
                visitOutput(boundOutput, coderByOutput, coderStates, usedPackedStreams);
            } else {
                usedPackedStreams[packedStreamOrdinalByInput[inputStreamIndex]] = true;
            }
        }
        coderStates[coderIndex] = 2;
    }

    /// Returns whether another graph has identical coders, bindings, packed inputs, sizes, and final output.
    @Override
    public boolean equals(@Nullable Object object) {
        return object instanceof SevenZipCoderGraph other
                && coders.equals(other.coders)
                && Arrays.equals(boundOutputByInput, other.boundOutputByInput)
                && Arrays.equals(packedStreamOrdinalByInput, other.packedStreamOrdinalByInput)
                && Arrays.equals(unpackSizes, other.unpackSizes)
                && finalOutputStreamIndex == other.finalOutputStreamIndex;
    }

    /// Returns a content-based hash code.
    @Override
    public int hashCode() {
        int result = Objects.hash(coders, finalOutputStreamIndex);
        result = 31 * result + Arrays.hashCode(boundOutputByInput);
        result = 31 * result + Arrays.hashCode(packedStreamOrdinalByInput);
        return 31 * result + Arrays.hashCode(unpackSizes);
    }

    /// Returns a readable coder graph representation.
    @Override
    public String toString() {
        return "SevenZipCoderGraph[coders=" + coders
                + ", boundOutputByInput=" + Arrays.toString(boundOutputByInput)
                + ", packedStreamOrdinalByInput=" + Arrays.toString(packedStreamOrdinalByInput)
                + ", unpackSizes=" + Arrays.toString(unpackSizes)
                + ", finalOutputStreamIndex=" + finalOutputStreamIndex
                + "]";
    }

    /// Returns coders in 7z declaration order.
    public @Unmodifiable List<SevenZipCoder> coders() {
        return coders;
    }

    /// Returns the total number of graph input streams.
    public int inputStreamCount() {
        return boundOutputByInput.length;
    }

    /// Returns the total number of graph output streams.
    public int outputStreamCount() {
        return unpackSizes.length;
    }

    /// Returns the number of physical packed input streams.
    public int packedStreamCount() {
        return packedStreamCount;
    }

    /// Returns the bound output for a graph input, or `-1` when the input is physically packed.
    public int boundOutputStreamIndex(int inputStreamIndex) {
        return boundOutputByInput[Objects.checkIndex(inputStreamIndex, boundOutputByInput.length)];
    }

    /// Returns the packed-stream ordinal for a graph input, or `-1` when the input is bound.
    public int packedStreamOrdinal(int inputStreamIndex) {
        return packedStreamOrdinalByInput[Objects.checkIndex(inputStreamIndex, packedStreamOrdinalByInput.length)];
    }

    /// Returns the unpack size produced by a graph output.
    public long unpackSize(int outputStreamIndex) {
        return unpackSizes[Objects.checkIndex(outputStreamIndex, unpackSizes.length)];
    }

    /// Returns the sole unbound output exposed as the folder result.
    public int finalOutputStreamIndex() {
        return finalOutputStreamIndex;
    }

    /// Returns the final decoded folder size.
    public long finalUnpackSize() {
        return unpackSizes[finalOutputStreamIndex];
    }
}
