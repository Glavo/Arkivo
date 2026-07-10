// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Configures one 7z output preprocessing filter and its method-specific parameter.
///
/// `parameter` is the Delta distance from 1 through 256 for `DELTA` and zero for every BCJ filter. BCJ output starts
/// at address offset zero because the current encoder does not expose configurable BCJ start offsets.
///
/// @param method the output filter method
/// @param parameter the validated method-specific parameter
@NotNullByDefault
public record SevenZipFilter(SevenZipFilterMethod method, int parameter) {
    /// The minimum and default Delta distance.
    public static final int MIN_DELTA_DISTANCE = 1;

    /// The maximum Delta distance.
    public static final int MAX_DELTA_DISTANCE = 256;

    /// Validates a 7z filter configuration.
    public SevenZipFilter {
        Objects.requireNonNull(method, "method");
        if (method == SevenZipFilterMethod.DELTA) {
            if (parameter < MIN_DELTA_DISTANCE || parameter > MAX_DELTA_DISTANCE) {
                throw new IllegalArgumentException("Delta distance must be between 1 and 256");
            }
        } else if (parameter != 0) {
            throw new IllegalArgumentException("BCJ filter parameter must be zero");
        }
    }

    /// Returns a default configuration for the given method.
    public static SevenZipFilter of(SevenZipFilterMethod method) {
        Objects.requireNonNull(method, "method");
        return method == SevenZipFilterMethod.DELTA
                ? delta()
                : new SevenZipFilter(method, 0);
    }

    /// Returns a Delta filter with the default distance.
    public static SevenZipFilter delta() {
        return delta(MIN_DELTA_DISTANCE);
    }

    /// Returns a Delta filter with the requested byte distance from 1 through 256.
    public static SevenZipFilter delta(int distance) {
        return new SevenZipFilter(SevenZipFilterMethod.DELTA, distance);
    }

    /// Returns an x86 BCJ filter.
    public static SevenZipFilter bcjX86() {
        return new SevenZipFilter(SevenZipFilterMethod.BCJ_X86, 0);
    }

    /// Returns a PowerPC BCJ filter.
    public static SevenZipFilter bcjPpc() {
        return new SevenZipFilter(SevenZipFilterMethod.BCJ_PPC, 0);
    }

    /// Returns an IA-64 BCJ filter.
    public static SevenZipFilter bcjIa64() {
        return new SevenZipFilter(SevenZipFilterMethod.BCJ_IA64, 0);
    }

    /// Returns an ARM BCJ filter.
    public static SevenZipFilter bcjArm() {
        return new SevenZipFilter(SevenZipFilterMethod.BCJ_ARM, 0);
    }

    /// Returns an ARM-Thumb BCJ filter.
    public static SevenZipFilter bcjArmThumb() {
        return new SevenZipFilter(SevenZipFilterMethod.BCJ_ARM_THUMB, 0);
    }

    /// Returns a SPARC BCJ filter.
    public static SevenZipFilter bcjSparc() {
        return new SevenZipFilter(SevenZipFilterMethod.BCJ_SPARC, 0);
    }

    /// Returns the stable method name and parameter.
    @Override
    public String toString() {
        return method.optionName() + "(" + parameter + ")";
    }
}
