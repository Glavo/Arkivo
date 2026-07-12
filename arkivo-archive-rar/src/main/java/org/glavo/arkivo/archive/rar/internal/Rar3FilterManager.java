// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Parses RAR3 filter descriptors and retains filter definitions across solid entries.
@NotNullByDefault
final class Rar3FilterManager {
    /// The largest accepted filter program.
    private static final int MAX_CODE_SIZE = 0x10000;
    /// The largest number of definitions in one filter sequence.
    private static final int MAX_FILTER_COUNT = 1024;
    /// The known filter definitions indexed by archive filter number.
    private final List<Rar3FilterProgram> programs = new ArrayList<>();
    /// The most recently declared block length for every filter number.
    private final List<Integer> blockLengths = new ArrayList<>();
    /// The filter number selected by the latest descriptor that specified one.
    private int currentFilter;

    /// Creates an empty filter-definition sequence.
    Rar3FilterManager() {
    }

    /// Clears all definitions and selected-filter state.
    void reset() {
        programs.clear();
        blockLengths.clear();
        currentFilter = 0;
    }

    /// Parses one complete descriptor including its flags byte.
    Descriptor parse(byte[] descriptor) throws IOException {
        Objects.requireNonNull(descriptor, "descriptor");
        if (descriptor.length == 0) throw new IOException("Empty RAR3 filter descriptor");
        int flags = descriptor[0] & 0xff;
        Rar3BitReader reader = new Rar3BitReader(descriptor, 1);
        boolean resetQueuedFilters = false;
        if ((flags & 0x80) != 0) {
            long encodedFilter = reader.readEncodedUint32();
            if (encodedFilter == 0) {
                reset();
                resetQueuedFilters = true;
            } else {
                encodedFilter--;
                if (encodedFilter > MAX_FILTER_COUNT || encodedFilter > programs.size()) {
                    throw new IOException("Invalid RAR3 filter number");
                }
                currentFilter = (int) encodedFilter;
            }
        }
        if (currentFilter > programs.size()) throw new IOException("Unavailable RAR3 filter number");

        long relativeOffset = reader.readEncodedUint32();
        if ((flags & 0x40) != 0) relativeOffset += 258L;
        if (relativeOffset > Integer.MAX_VALUE) throw new IOException("RAR3 filter offset is too large");

        while (blockLengths.size() <= currentFilter) blockLengths.add(0);
        if ((flags & 0x20) != 0) {
            long blockLength = reader.readEncodedUint32();
            if (blockLength <= 0 || blockLength > Rar3Vm.MEMORY_MASK) {
                throw new IOException("Invalid RAR3 filter block length");
            }
            blockLengths.set(currentFilter, (int) blockLength);
        }
        int blockLength = blockLengths.get(currentFilter);
        if (blockLength <= 0) throw new IOException("RAR3 filter block length is unavailable");

        int[] registers = new int[7];
        int registerMask = 0;
        if ((flags & 0x10) != 0) {
            registerMask = reader.readBits(7);
            for (int register = 0; register < registers.length; register++) {
                if ((registerMask & 1 << register) != 0) {
                    registers[register] = (int) reader.readEncodedUint32();
                }
            }
        }

        if (currentFilter == programs.size()) {
            long codeSize = reader.readEncodedUint32();
            if (codeSize <= 0 || codeSize > MAX_CODE_SIZE) {
                throw new IOException("Invalid RAR3 filter program size");
            }
            if (programs.size() >= MAX_FILTER_COUNT) throw new IOException("Too many RAR3 filter definitions");
            programs.add(new Rar3FilterProgram(reader.readBytes((int) codeSize)));
        }

        byte[] globalData = new byte[0];
        if ((flags & 0x08) != 0) {
            long globalSize = reader.readEncodedUint32();
            if (globalSize > Rar3Vm.GLOBAL_SIZE - Rar3Vm.FIXED_GLOBAL_SIZE) {
                throw new IOException("RAR3 filter global data is too large");
            }
            globalData = reader.readBytes((int) globalSize);
        }
        return new Descriptor(
                resetQueuedFilters,
                (int) relativeOffset,
                blockLength,
                programs.get(currentFilter),
                registers,
                registerMask,
                globalData
        );
    }

    /// Describes one scheduled invocation parsed from an archive descriptor.
    ///
    /// @param resetQueuedFilters whether previously queued invocations must be discarded
    /// @param relativeOffset raw bytes between the descriptor and filter block
    /// @param blockLength the number of raw bytes consumed by the filter
    /// @param program the selected filter definition
    /// @param initialRegisters descriptor-provided register values
    /// @param initialRegisterMask bits identifying provided register values
    /// @param initialGlobal descriptor-provided global bytes
    @NotNullByDefault
    record Descriptor(
            boolean resetQueuedFilters,
            int relativeOffset,
            int blockLength,
            Rar3FilterProgram program,
            int @Unmodifiable [] initialRegisters,
            int initialRegisterMask,
            byte @Unmodifiable [] initialGlobal
    ) {
        /// Makes mutable descriptor inputs immutable after parsing.
        Descriptor {
            initialRegisters = initialRegisters.clone();
            initialGlobal = initialGlobal.clone();
        }
    }
}
