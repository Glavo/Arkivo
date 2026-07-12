// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Objects;

/// Represents either a recognized standard RAR3 filter or a compiled custom VM program.
@NotNullByDefault
final class Rar3FilterProgram {
    /// The standard-filter identifier, or `-1` for a custom program.
    private final int standardIdentifier;
    /// The compiled custom program when this is not a standard filter.
    private final @Nullable Rar3Vm.Program customProgram;

    /// Compiles or recognizes one checksummed filter bytecode buffer.
    Rar3FilterProgram(byte[] code) throws IOException {
        Objects.requireNonNull(code, "code");
        standardIdentifier = Rar3StandardFilters.identify(code);
        customProgram = standardIdentifier < 0 ? Rar3Vm.compile(code) : null;
    }

    /// Applies this filter to one complete raw block.
    byte[] apply(
            byte[] input,
            int[] initialRegisters,
            int initialRegisterMask,
            byte[] initialGlobal,
            long outputOffset
    ) throws IOException {
        if (standardIdentifier >= 0) {
            return Rar3StandardFilters.apply(standardIdentifier, initialRegisters, input, outputOffset);
        }
        assert customProgram != null;
        return customProgram.execute(input, initialRegisters, initialRegisterMask, initialGlobal, outputOffset);
    }
}
