// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Locale;

/// Identifies how an Arkivo file system accesses archive storage.
@NotNullByDefault
public enum ArkivoStorageAccess {
    /// Allows random-access reads from archive storage.
    RANDOM_READ("random-read", true, false, true),

    /// Allows random-access writes to archive storage.
    RANDOM_WRITE("random-write", false, true, true),

    /// Allows forward-only streaming reads from archive storage.
    STREAM_READ("stream-read", true, false, false),

    /// Allows forward-only streaming writes to archive storage.
    STREAM_WRITE("stream-write", false, true, false);

    /// The stable environment string for this storage access value.
    private final String optionName;

    /// Whether this access value supports reading archive entries.
    private final boolean readable;

    /// Whether this access value supports writing archive entries.
    private final boolean writable;

    /// Whether this access value requires random access to archive storage.
    private final boolean randomAccess;

    /// Creates an Arkivo storage access value.
    ArkivoStorageAccess(String optionName, boolean readable, boolean writable, boolean randomAccess) {
        this.optionName = optionName;
        this.readable = readable;
        this.writable = writable;
        this.randomAccess = randomAccess;
    }

    /// Parses an Arkivo storage access string.
    public static ArkivoStorageAccess parse(String value) {
        String normalizedValue = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (ArkivoStorageAccess access : values()) {
            if (access.optionName.equals(normalizedValue)) {
                return access;
            }
        }
        throw new IllegalArgumentException("Unknown Arkivo storage access: " + value);
    }

    /// Returns the stable environment string for this storage access value.
    public String optionName() {
        return optionName;
    }

    /// Returns whether this access value supports reading archive entries.
    public boolean readable() {
        return readable;
    }

    /// Returns whether this access value supports writing archive entries.
    public boolean writable() {
        return writable;
    }

    /// Returns whether this access value requires random access to archive storage.
    public boolean randomAccess() {
        return randomAccess;
    }

    /// Returns whether this access value is forward-only streaming.
    public boolean streaming() {
        return !randomAccess;
    }

    /// Returns the stable environment string for this storage access value.
    @Override
    public String toString() {
        return optionName;
    }
}
