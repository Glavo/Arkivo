// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Configures one 7z output preprocessing filter and its method-specific parameter.
///
/// The parameter is the Delta distance from 1 through 256 for DELTA and the unsigned 32-bit start offset for
/// single-stream BCJ filters. BCJ start offsets must satisfy the instruction alignment required by the selected
/// architecture. BCJ2 has no parameter and requires zero.
///
/// @param method the output filter method
/// @param parameter the validated method-specific parameter
@NotNullByDefault
public record SevenZipFilter(SevenZipFilterMethod method, long parameter) {
    /// The minimum and default Delta distance.
    public static final int MIN_DELTA_DISTANCE = 1;

    /// The maximum Delta distance.
    public static final int MAX_DELTA_DISTANCE = 256;

    /// The largest unsigned 32-bit BCJ start offset.
    public static final long MAX_BCJ_START_OFFSET = 0xffff_ffffL;

    /// Validates a 7z filter configuration.
    public SevenZipFilter {
        Objects.requireNonNull(method, "method");
        if (method == SevenZipFilterMethod.DELTA) {
            if (parameter < MIN_DELTA_DISTANCE || parameter > MAX_DELTA_DISTANCE) {
                throw new IllegalArgumentException("Delta distance must be between 1 and 256");
            }
        } else if (method == SevenZipFilterMethod.BCJ2) {
            if (parameter != 0L) {
                throw new IllegalArgumentException("BCJ2 does not accept a parameter");
            }
        } else {
            if (parameter < 0 || parameter > MAX_BCJ_START_OFFSET) {
                throw new IllegalArgumentException("BCJ start offset must be an unsigned 32-bit value");
            }
            int alignment = bcjAlignment(method);
            if ((parameter & (alignment - 1L)) != 0) {
                throw new IllegalArgumentException("BCJ start offset must be aligned to " + alignment);
            }
        }
    }

    /// Returns a default configuration for the given method.
    ///
    /// @param method the preprocessing filter method
    /// @return the method's default validated configuration
    public static SevenZipFilter of(SevenZipFilterMethod method) {
        Objects.requireNonNull(method, "method");
        return method == SevenZipFilterMethod.DELTA
                ? delta()
                : new SevenZipFilter(method, 0);
    }

    /// Returns a Delta filter with the default distance.
    ///
    /// @return a Delta filter using [#MIN_DELTA_DISTANCE]
    public static SevenZipFilter delta() {
        return delta(MIN_DELTA_DISTANCE);
    }

    /// Returns a Delta filter with the requested byte distance from 1 through 256.
    ///
    /// @param distance the preceding-byte distance from 1 through 256
    /// @return a validated Delta filter
    /// @throws IllegalArgumentException if `distance` is outside 1 through 256
    public static SevenZipFilter delta(int distance) {
        return new SevenZipFilter(SevenZipFilterMethod.DELTA, distance);
    }

    /// Returns an x86 BCJ filter.
    ///
    /// @return an x86 BCJ filter with start offset zero
    public static SevenZipFilter bcjX86() {
        return bcjX86(0);
    }

    /// Returns an x86 BCJ filter with the requested unsigned 32-bit start offset.
    ///
    /// @param startOffset the unsigned 32-bit logical start offset
    /// @return a validated x86 BCJ filter
    /// @throws IllegalArgumentException if `startOffset` is outside the unsigned 32-bit range
    public static SevenZipFilter bcjX86(long startOffset) {
        return new SevenZipFilter(SevenZipFilterMethod.BCJ_X86, startOffset);
    }

    /// Returns the four-stream x86 BCJ2 filter.
    ///
    /// @return the parameterless BCJ2 filter
    public static SevenZipFilter bcj2() {
        return new SevenZipFilter(SevenZipFilterMethod.BCJ2, 0L);
    }

    /// Returns a PowerPC BCJ filter.
    ///
    /// @return a PowerPC BCJ filter with start offset zero
    public static SevenZipFilter bcjPowerPC() {
        return bcjPowerPC(0);
    }

    /// Returns a PowerPC BCJ filter with the requested unsigned 32-bit start offset.
    ///
    /// @param startOffset the aligned unsigned 32-bit logical start offset
    /// @return a validated PowerPC BCJ filter
    /// @throws IllegalArgumentException if `startOffset` is out of range or not four-byte aligned
    public static SevenZipFilter bcjPowerPC(long startOffset) {
        return new SevenZipFilter(SevenZipFilterMethod.BCJ_POWERPC, startOffset);
    }

    /// Returns an IA-64 BCJ filter.
    ///
    /// @return an IA-64 BCJ filter with start offset zero
    public static SevenZipFilter bcjIa64() {
        return bcjIa64(0);
    }

    /// Returns an IA-64 BCJ filter with the requested unsigned 32-bit start offset.
    ///
    /// @param startOffset the aligned unsigned 32-bit logical start offset
    /// @return a validated IA-64 BCJ filter
    /// @throws IllegalArgumentException if `startOffset` is out of range or not 16-byte aligned
    public static SevenZipFilter bcjIa64(long startOffset) {
        return new SevenZipFilter(SevenZipFilterMethod.BCJ_IA64, startOffset);
    }

    /// Returns an ARM BCJ filter.
    ///
    /// @return an ARM BCJ filter with start offset zero
    public static SevenZipFilter bcjArm() {
        return bcjArm(0);
    }

    /// Returns an ARM BCJ filter with the requested unsigned 32-bit start offset.
    ///
    /// @param startOffset the aligned unsigned 32-bit logical start offset
    /// @return a validated ARM BCJ filter
    /// @throws IllegalArgumentException if `startOffset` is out of range or not four-byte aligned
    public static SevenZipFilter bcjArm(long startOffset) {
        return new SevenZipFilter(SevenZipFilterMethod.BCJ_ARM, startOffset);
    }

    /// Returns an ARM-Thumb BCJ filter.
    ///
    /// @return an ARM-Thumb BCJ filter with start offset zero
    public static SevenZipFilter bcjArmThumb() {
        return bcjArmThumb(0);
    }

    /// Returns an ARM-Thumb BCJ filter with the requested unsigned 32-bit start offset.
    ///
    /// @param startOffset the aligned unsigned 32-bit logical start offset
    /// @return a validated ARM-Thumb BCJ filter
    /// @throws IllegalArgumentException if `startOffset` is out of range or not two-byte aligned
    public static SevenZipFilter bcjArmThumb(long startOffset) {
        return new SevenZipFilter(SevenZipFilterMethod.BCJ_ARM_THUMB, startOffset);
    }

    /// Returns a SPARC BCJ filter.
    ///
    /// @return a SPARC BCJ filter with start offset zero
    public static SevenZipFilter bcjSparc() {
        return bcjSparc(0);
    }

    /// Returns a SPARC BCJ filter with the requested unsigned 32-bit start offset.
    ///
    /// @param startOffset the aligned unsigned 32-bit logical start offset
    /// @return a validated SPARC BCJ filter
    /// @throws IllegalArgumentException if `startOffset` is out of range or not four-byte aligned
    public static SevenZipFilter bcjSparc(long startOffset) {
        return new SevenZipFilter(SevenZipFilterMethod.BCJ_SPARC, startOffset);
    }

    /// Returns an ARM64 BCJ filter.
    ///
    /// @return an ARM64 BCJ filter with start offset zero
    public static SevenZipFilter bcjArm64() {
        return bcjArm64(0);
    }

    /// Returns an ARM64 BCJ filter with the requested unsigned 32-bit start offset.
    ///
    /// @param startOffset the aligned unsigned 32-bit logical start offset
    /// @return a validated ARM64 BCJ filter
    /// @throws IllegalArgumentException if `startOffset` is out of range or not four-byte aligned
    public static SevenZipFilter bcjArm64(long startOffset) {
        return new SevenZipFilter(SevenZipFilterMethod.BCJ_ARM64, startOffset);
    }

    /// Returns a RISC-V BCJ filter.
    ///
    /// @return a RISC-V BCJ filter with start offset zero
    public static SevenZipFilter bcjRiscV() {
        return bcjRiscV(0);
    }

    /// Returns a RISC-V BCJ filter with the requested unsigned 32-bit start offset.
    ///
    /// @param startOffset the aligned unsigned 32-bit logical start offset
    /// @return a validated RISC-V BCJ filter
    /// @throws IllegalArgumentException if `startOffset` is out of range or not two-byte aligned
    public static SevenZipFilter bcjRiscV(long startOffset) {
        return new SevenZipFilter(SevenZipFilterMethod.BCJ_RISCV, startOffset);
    }

    /// Returns the required start-offset alignment for a BCJ method.
    private static int bcjAlignment(SevenZipFilterMethod method) {
        return switch (method) {
            case DELTA -> throw new IllegalArgumentException("Delta is not a BCJ filter");
            case BCJ2 -> throw new IllegalArgumentException("BCJ2 has no start offset");
            case BCJ_X86 -> 1;
            case BCJ_POWERPC, BCJ_ARM, BCJ_SPARC, BCJ_ARM64 -> 4;
            case BCJ_IA64 -> 16;
            case BCJ_ARM_THUMB, BCJ_RISCV -> 2;
        };
    }

    /// Returns the stable method name and parameter.
    @Override
    public String toString() {
        return method == SevenZipFilterMethod.BCJ2
                ? method.optionName()
                : method.optionName() + "(" + parameter + ")";
    }
}
