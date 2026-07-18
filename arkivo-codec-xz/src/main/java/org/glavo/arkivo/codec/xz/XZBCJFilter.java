// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.xz;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Configures one architecture-specific XZ branch-conversion preprocessing filter.
///
/// The transform rewrites relative branch targets to improve subsequent compression without changing the byte count.
/// The start offset participates in address calculation and must meet the selected architecture's alignment.
///
/// @param architecture the instruction-set transform
/// @param startOffset the unsigned 32-bit stream start offset
@NotNullByDefault
public record XZBCJFilter(Architecture architecture, long startOffset) implements XZFilter {
    /// Validates the start offset and architecture alignment.
    ///
    /// @throws NullPointerException if {@code architecture} is {@code null}
    /// @throws IllegalArgumentException if {@code startOffset} is outside the unsigned 32-bit range or is misaligned
    public XZBCJFilter {
        Objects.requireNonNull(architecture, "architecture");
        if (startOffset < 0L || startOffset > 0xffff_ffffL) {
            throw new IllegalArgumentException(
                    "XZ BCJ start offset must be an unsigned 32-bit value: " + startOffset
            );
        }
        if ((startOffset & architecture.alignment() - 1L) != 0L) {
            throw new IllegalArgumentException(
                    "XZ " + architecture + " start offset must be aligned to " + architecture.alignment()
            );
        }
    }

    /// Creates a BCJ filter starting at stream offset zero.
    ///
    /// @param architecture the instruction-set transform
    /// @throws NullPointerException if {@code architecture} is {@code null}
    public XZBCJFilter(Architecture architecture) {
        this(architecture, 0L);
    }

    /// Identifies one standardized XZ branch-conversion filter.
    @NotNullByDefault
    public enum Architecture {
        /// The x86 CALL/JMP transform.
        X86(0x04L, 1L),

        /// The big-endian PowerPC branch transform.
        POWERPC(0x05L, 4L),

        /// The IA-64 branch-slot transform.
        IA64(0x06L, 16L),

        /// The little-endian ARM branch transform.
        ARM(0x07L, 4L),

        /// The little-endian ARM-Thumb branch transform.
        ARM_THUMB(0x08L, 2L),

        /// The big-endian SPARC CALL transform.
        SPARC(0x09L, 4L),

        /// The little-endian ARM64 branch transform.
        ARM64(0x0aL, 4L),

        /// The little-endian RISC-V branch transform.
        RISCV(0x0bL, 2L);

        /// The standardized XZ filter identifier.
        private final long identifier;

        /// The required start-offset alignment.
        private final long alignment;

        /// Creates one architecture descriptor.
        Architecture(long identifier, long alignment) {
            this.identifier = identifier;
            this.alignment = alignment;
        }

        /// Returns the standardized XZ filter identifier.
        ///
        /// @return the non-negative XZ filter identifier
        public long identifier() {
            return identifier;
        }

        /// Returns the required start-offset alignment.
        ///
        /// @return the positive power-of-two alignment
        public long alignment() {
            return alignment;
        }
    }
}
