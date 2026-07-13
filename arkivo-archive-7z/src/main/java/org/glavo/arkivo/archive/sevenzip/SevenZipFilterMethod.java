// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Locale;

/// Identifies a preprocessing filter supported by 7z output.
@NotNullByDefault
public enum SevenZipFilterMethod {
    /// Applies a byte-distance Delta filter.
    DELTA("delta"),

    /// Converts relative x86 branch addresses before compression.
    BCJ_X86("bcj-x86"),

    /// Converts relative PowerPC branch addresses before compression.
    BCJ_PPC("bcj-ppc"),

    /// Converts relative IA-64 branch addresses before compression.
    BCJ_IA64("bcj-ia64"),

    /// Converts relative ARM branch addresses before compression.
    BCJ_ARM("bcj-arm"),

    /// Converts relative ARM-Thumb branch addresses before compression.
    BCJ_ARM_THUMB("bcj-arm-thumb"),

    /// Converts relative SPARC branch addresses before compression.
    BCJ_SPARC("bcj-sparc"),

    /// Converts relative ARM64 branch addresses before compression.
    BCJ_ARM64("bcj-arm64"),

    /// Converts relative RISC-V branch addresses before compression.
    BCJ_RISCV("bcj-riscv");

    /// The stable environment and display name.
    private final String optionName;

    /// Creates a filter method with its stable option name.
    SevenZipFilterMethod(String optionName) {
        this.optionName = optionName;
    }

    /// Parses a stable case-insensitive filter method name.
    public static SevenZipFilterMethod parse(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (SevenZipFilterMethod method : values()) {
            if (method.optionName.equals(normalized)) {
                return method;
            }
        }
        throw new IllegalArgumentException("Unknown 7z filter method: " + value);
    }

    /// Returns the stable environment and display name.
    public String optionName() {
        return optionName;
    }

    /// Returns the stable display name.
    @Override
    public String toString() {
        return optionName;
    }
}
