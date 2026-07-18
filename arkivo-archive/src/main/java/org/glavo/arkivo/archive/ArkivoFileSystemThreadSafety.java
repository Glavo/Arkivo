// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Locale;

/// Defines the thread-safety strategy requested for an archive file system instance.
@NotNullByDefault
public enum ArkivoFileSystemThreadSafety {
    /// Performs no Arkivo lifecycle synchronization or resource wrapping beyond each implementation's invariants.
    /// Callers must coordinate concurrent operations and close any entry resources before closing the file system.
    NONE("none"),

    /// Allows read-only operations to run concurrently while serializing mutations and file system close.
    /// Entry streams, channels, and directory streams reject new operations after file system close but remain the
    /// caller's responsibility to close.
    CONCURRENT_READ("concurrent-read"),

    /// Uses concurrent-read coordination and force-closes active entry streams, channels, and directory streams before
    /// closing the archive backing resources.
    STRICT("strict");

    /// The stable environment option name.
    private final String optionName;

    /// Creates a thread-safety strategy.
    ArkivoFileSystemThreadSafety(String optionName) {
        this.optionName = optionName;
    }

    /// Parses a stable thread-safety option name.
    ///
    /// @param value the option name, ignoring surrounding whitespace, case, and underscore-versus-hyphen spelling
    /// @return the matching strategy
    /// @throws IllegalArgumentException if {@code value} does not name a strategy
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
    ///
    /// @return the lowercase hyphenated option name
    public String optionName() {
        return optionName;
    }
}
