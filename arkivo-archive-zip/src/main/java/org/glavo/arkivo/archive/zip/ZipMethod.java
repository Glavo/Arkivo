// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Identifies a ZIP compression method.
@NotNullByDefault
public final class ZipMethod {
    /// The stored method identifier.
    public static final int STORED_ID = 0;

    /// The deflated method identifier.
    public static final int DEFLATED_ID = 8;

    /// The Deflate64 method identifier.
    public static final int DEFLATE64_ID = 9;

    /// The BZIP2 method identifier.
    public static final int BZIP2_ID = 12;

    /// The LZMA method identifier.
    public static final int LZMA_ID = 14;

    /// The deprecated Zstandard method identifier from APPNOTE 6.3.7.
    public static final int DEPRECATED_ZSTANDARD_ID = 20;

    /// The Zstandard method identifier.
    public static final int ZSTANDARD_ID = 93;

    /// The XZ method identifier.
    public static final int XZ_ID = 95;

    /// The stored ZIP method.
    private static final ZipMethod STORED = new ZipMethod(STORED_ID, "stored");

    /// The deflated ZIP method.
    private static final ZipMethod DEFLATED = new ZipMethod(DEFLATED_ID, "deflated");

    /// The Deflate64 ZIP method.
    private static final ZipMethod DEFLATE64 = new ZipMethod(DEFLATE64_ID, "deflate64");

    /// The BZIP2 ZIP method.
    private static final ZipMethod BZIP2 = new ZipMethod(BZIP2_ID, "bzip2");

    /// The LZMA ZIP method.
    private static final ZipMethod LZMA = new ZipMethod(LZMA_ID, "lzma");

    /// The deprecated Zstandard ZIP method.
    private static final ZipMethod DEPRECATED_ZSTANDARD =
            new ZipMethod(DEPRECATED_ZSTANDARD_ID, "zstandard-deprecated");

    /// The Zstandard ZIP method.
    private static final ZipMethod ZSTANDARD = new ZipMethod(ZSTANDARD_ID, "zstandard");

    /// The XZ ZIP method.
    private static final ZipMethod XZ = new ZipMethod(XZ_ID, "xz");

    /// The numeric ZIP compression method identifier.
    private final int id;

    /// The stable display name for the method.
    private final String name;

    /// Creates a ZIP compression method.
    private ZipMethod(int id, String name) {
        this.id = id;
        this.name = name;
    }

    /// Returns the stored ZIP method.
    public static ZipMethod stored() {
        return STORED;
    }

    /// Returns the deflated ZIP method.
    public static ZipMethod deflated() {
        return DEFLATED;
    }

    /// Returns the Deflate64 ZIP method.
    public static ZipMethod deflate64() {
        return DEFLATE64;
    }

    /// Returns the BZIP2 ZIP method.
    public static ZipMethod bzip2() {
        return BZIP2;
    }

    /// Returns the LZMA ZIP method.
    public static ZipMethod lzma() {
        return LZMA;
    }

    /// Returns the deprecated Zstandard ZIP method from APPNOTE 6.3.7.
    public static ZipMethod deprecatedZstandard() {
        return DEPRECATED_ZSTANDARD;
    }

    /// Returns the Zstandard ZIP method.
    public static ZipMethod zstandard() {
        return ZSTANDARD;
    }

    /// Returns the XZ ZIP method.
    public static ZipMethod xz() {
        return XZ;
    }

    /// Returns a ZIP method for the given numeric identifier.
    public static ZipMethod of(int id) {
        return switch (id) {
            case STORED_ID -> STORED;
            case DEFLATED_ID -> DEFLATED;
            case DEFLATE64_ID -> DEFLATE64;
            case BZIP2_ID -> BZIP2;
            case LZMA_ID -> LZMA;
            case DEPRECATED_ZSTANDARD_ID -> DEPRECATED_ZSTANDARD;
            case ZSTANDARD_ID -> ZSTANDARD;
            case XZ_ID -> XZ;
            default -> new ZipMethod(id, "method-" + id);
        };
    }

    /// Returns the numeric ZIP compression method identifier.
    public int id() {
        return id;
    }

    /// Returns the stable display name for the method.
    public String name() {
        return name;
    }

    /// Compares this method with another object.
    @Override
    public boolean equals(@Nullable Object object) {
        return object instanceof ZipMethod that && id == that.id && name.equals(that.name);
    }

    /// Returns the hash code for this method.
    @Override
    public int hashCode() {
        return Objects.hash(id, name);
    }

    /// Returns the stable display name for this method.
    @Override
    public String toString() {
        return name;
    }
}
