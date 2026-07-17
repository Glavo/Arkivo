// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Identifies a ZIP compression method recognized by Arkivo.
@NotNullByDefault
public enum ZipMethod {
    /// Stores entry data without compression.
    STORED(0, "stored"),

    /// Compresses entry data with Deflate.
    DEFLATED(8, "deflated"),

    /// Compresses entry data with Deflate64.
    DEFLATE64(9, "deflate64"),

    /// Compresses entry data with BZIP2.
    BZIP2(12, "bzip2"),

    /// Compresses entry data with ZIP LZMA.
    LZMA(14, "lzma"),

    /// Compresses entry data with the deprecated Zstandard method identifier from APPNOTE 6.3.7.
    DEPRECATED_ZSTANDARD(20, "zstandard-deprecated"),

    /// Compresses entry data with Zstandard.
    ZSTANDARD(93, "zstandard"),

    /// Compresses entry data with XZ.
    XZ(95, "xz");

    /// The numeric ZIP compression method identifier.
    private final int id;

    /// The stable display name for this method.
    private final String displayName;

    /// Creates a ZIP compression method.
    ZipMethod(int id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /// Returns the recognized ZIP method for the given unsigned 16-bit identifier, or `null` when it is unknown.
    public static @Nullable ZipMethod fromId(int id) {
        if (id < 0 || id > 0xffff) {
            throw new IllegalArgumentException("ZIP compression method identifier must be between 0 and 65535: " + id);
        }
        return switch (id) {
            case 0 -> STORED;
            case 8 -> DEFLATED;
            case 9 -> DEFLATE64;
            case 12 -> BZIP2;
            case 14 -> LZMA;
            case 20 -> DEPRECATED_ZSTANDARD;
            case 93 -> ZSTANDARD;
            case 95 -> XZ;
            default -> null;
        };
    }

    /// Returns the numeric ZIP compression method identifier.
    public int id() {
        return id;
    }

    /// Returns the stable display name for this method.
    @Override
    public String toString() {
        return displayName;
    }
}
