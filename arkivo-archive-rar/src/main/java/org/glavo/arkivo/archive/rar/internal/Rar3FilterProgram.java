// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Objects;

/// Represents either a recognized standard RAR3 filter or a compiled custom VM program.
@NotNullByDefault
final class Rar3FilterProgram implements AutoCloseable {
    /// The session budget for the program and its invocation results.
    private final RarDecoderMemoryBudget memory;
    /// The standard-filter identifier, or `-1` for a custom program.
    private final int standardIdentifier;
    /// The compiled custom program when this is not a standard filter.
    private final @Nullable Rar3Vm.Program customProgram;

    /// Compiles or recognizes one checksummed filter bytecode buffer.
    Rar3FilterProgram(byte[] code, RarDecoderMemoryBudget memory) throws IOException {
        Objects.requireNonNull(code, "code");
        this.memory = memory;
        standardIdentifier = Rar3StandardFilters.identify(code);
        customProgram = standardIdentifier < 0 ? Rar3Vm.compile(code, memory) : null;
    }

    /// Borrows the invocation arrays and returns a newly reserved result owned by the caller.
    byte[] apply(
            byte[] input,
            int[] initialRegisters,
            int initialRegisterMask,
            byte[] initialGlobal,
            long outputOffset
    ) throws IOException {
        if (standardIdentifier >= 0) {
            int scratch = standardIdentifier == 5 ? Rar3StandardFilters.AUDIO_MEMORY_SIZE : 0;
            long reservation = (long) input.length + scratch;
            memory.reserve(reservation);
            boolean transferred = false;
            try {
                byte[] result = Rar3StandardFilters.apply(standardIdentifier, initialRegisters, input, outputOffset);
                transferred = true;
                return result;
            } finally {
                memory.release(transferred ? scratch : reservation);
            }
        }
        assert customProgram != null;
        return customProgram.execute(input, initialRegisters, initialRegisterMask, initialGlobal, outputOffset);
    }

    /// Releases retained custom-program arrays; repeated calls have no effect.
    @Override
    public void close() {
        if (customProgram != null) customProgram.close();
    }
}
