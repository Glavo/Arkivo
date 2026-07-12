// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Objects;

/// Buffers raw RAR3 output until scheduled virtual-machine filters can be applied in order.
@NotNullByDefault
final class Rar3OutputPipeline {
    /// The largest number of pending invocations accepted from one stream.
    private static final int MAX_PENDING_FILTERS = 8192;
    /// The caller-owned extracted output.
    private final OutputStream output;
    /// The declared extracted size.
    private final long expectedSize;
    /// Pending filter invocations ordered by raw block start.
    private final Deque<Invocation> pending = new ArrayDeque<>();
    /// Raw bytes not yet written or consumed by a filter.
    private byte[] rawBuffer = new byte[64 * 1024];
    /// The number of bytes currently held in {@link #rawBuffer}.
    private int rawBufferSize;
    /// The total number of raw bytes accepted for this entry.
    private long rawAccepted;
    /// The total number of raw bytes written or consumed for this entry.
    private long rawConsumed;
    /// The total number of final bytes written for this entry.
    private long written;

    /// Creates one size-bounded output pipeline.
    Rar3OutputPipeline(OutputStream output, long expectedSize) {
        this.output = Objects.requireNonNull(output, "output");
        this.expectedSize = expectedSize;
    }

    /// Appends one unfiltered byte produced by the LZ or PPM decoder.
    void accept(int value) throws IOException {
        ensureRawCapacity(rawBufferSize + 1);
        rawBuffer[rawBufferSize++] = (byte) value;
        rawAccepted++;
        processAvailable();
    }

    /// Schedules one descriptor relative to the current raw write position.
    void schedule(Rar3FilterManager.Descriptor descriptor) throws IOException {
        if (descriptor.resetQueuedFilters()) pending.clear();
        if (pending.size() >= MAX_PENDING_FILTERS) throw new IOException("Too many queued RAR3 filters");
        long start = rawAccepted + descriptor.relativeOffset();
        Invocation previous = pending.peekLast();
        if (previous != null && start < previous.rawStart) {
            throw new IOException("RAR3 filter blocks are not ordered");
        }
        pending.addLast(new Invocation(start, descriptor));
        processAvailable();
    }

    /// Returns whether all declared final output has been produced with no pending raw data.
    boolean isComplete() {
        return written == expectedSize && rawBufferSize == 0 && pending.isEmpty();
    }

    /// Finishes the entry and validates all raw, filtered, and final sizes.
    void finish() throws IOException {
        processAvailable();
        if (!pending.isEmpty()) throw new IOException("RAR3 stream ended before a filter block was available");
        flushRawPrefix(rawBufferSize);
        if (written != expectedSize) {
            throw new IOException("RAR4 decompressor produced " + written + " bytes; expected " + expectedSize);
        }
    }

    /// Writes all currently resolvable raw prefixes and filter blocks.
    private void processAvailable() throws IOException {
        while (rawBufferSize != 0) {
            Invocation invocation = pending.peekFirst();
            if (invocation == null) {
                flushRawPrefix(rawBufferSize);
                return;
            }
            if (invocation.rawStart < rawConsumed) {
                throw new IOException("RAR3 filter starts inside consumed output");
            }
            if (invocation.rawStart > rawConsumed) {
                int prefix = (int) Math.min(invocation.rawStart - rawConsumed, rawBufferSize);
                flushRawPrefix(prefix);
                if (invocation.rawStart > rawConsumed) return;
            }
            int blockLength = invocation.descriptor.blockLength();
            if (rawBufferSize < blockLength) return;

            byte[] filtered = Arrays.copyOf(rawBuffer, blockLength);
            removeRawPrefix(blockLength);
            long filterOffset = written;
            long rawStart = invocation.rawStart;
            do {
                pending.removeFirst();
                if (invocation.descriptor.blockLength() != filtered.length) {
                    throw new IOException("RAR3 chained filter length does not match prior output");
                }
                filtered = invocation.descriptor.program().apply(
                        filtered,
                        invocation.descriptor.initialRegisters(),
                        invocation.descriptor.initialRegisterMask(),
                        invocation.descriptor.initialGlobal(),
                        filterOffset
                );
                invocation = pending.peekFirst();
            } while (invocation != null && invocation.rawStart == rawStart);
            writeFinal(filtered, filtered.length);
        }
    }

    /// Writes and removes one unfiltered raw prefix.
    private void flushRawPrefix(int count) throws IOException {
        if (count == 0) return;
        writeFinal(rawBuffer, count);
        removeRawPrefix(count);
    }

    /// Removes a consumed prefix and advances the absolute raw position.
    private void removeRawPrefix(int count) {
        rawBufferSize -= count;
        if (rawBufferSize != 0) System.arraycopy(rawBuffer, count, rawBuffer, 0, rawBufferSize);
        rawConsumed += count;
    }

    /// Writes final bytes while enforcing the entry's declared unpacked size.
    private void writeFinal(byte[] data, int length) throws IOException {
        if (length > expectedSize - written) {
            throw new IOException("RAR4 decompressor exceeded the declared unpacked size");
        }
        output.write(data, 0, length);
        written += length;
    }

    /// Grows the raw staging buffer without losing pending bytes.
    private void ensureRawCapacity(int required) {
        if (required <= rawBuffer.length) return;
        int capacity = Math.max(required, Math.min(Rar3Vm.MEMORY_SIZE, rawBuffer.length * 2));
        rawBuffer = Arrays.copyOf(rawBuffer, capacity);
    }

    /// Associates one parsed descriptor with its absolute raw block start.
    ///
    /// @param rawStart the entry-relative unfiltered start offset
    /// @param descriptor the parsed filter invocation
    @NotNullByDefault
    private record Invocation(long rawStart, Rar3FilterManager.Descriptor descriptor) {
    }
}
