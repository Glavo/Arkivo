// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.encoding.detector.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

/// Shares language weights with the Western and Central European single-byte models.
/// Characters outside those models, including DOS drawing characters, have no trained pair weights.
@NotNullByDefault
final class DosModels {
    /// Byte classification tables derived from installed JDK character mappings.
    static final SingleByteData @Unmodifiable [] VALUES = {
            Models.SINGLE_BYTE_DATA[7].remap("IBM437"),
            Models.SINGLE_BYTE_DATA[7].remap("IBM850"),
            Models.SINGLE_BYTE_DATA[1].remap("IBM852")
    };

    /// Prevents instantiation.
    private DosModels() {
    }
}
