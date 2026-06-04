// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Locale;

/// Defines the thread-safety strategy requested for an archive file system instance.
@NotNullByDefault
public enum ArkivoFileSystemThreadSafety {
    /// Performs no additional synchronization beyond each implementation's internal invariants.
    NONE("none"),

    /// Supports concurrent read-only file system operations.
    CONCURRENT_READ("concurrent-read"),

    /// Coordinates file system operations and close semantics more strictly.
    STRICT("strict");

    /// The stable environment option name.
    private final String optionName;

    /// Creates a thread-safety strategy.
    ArkivoFileSystemThreadSafety(String optionName) {
        this.optionName = optionName;
    }

    /// Parses a stable thread-safety option name.
    public static ArkivoFileSystemThreadSafety parse(String value) {
        String normalizedValue = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (ArkivoFileSystemThreadSafety threadSafety : values()) {
            if (threadSafety.optionName.equals(normalizedValue)) {
                return threadSafety;
            }
        }
        throw new IllegalArgumentException("Unknown file system thread-safety strategy: " + value);
    }

    /// Returns the stable environment option name.
    public String optionName() {
        return optionName;
    }
}
