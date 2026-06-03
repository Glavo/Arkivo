// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

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

    /// The stored ZIP method.
    private static final ZipMethod STORED = new ZipMethod(STORED_ID, "stored");

    /// The deflated ZIP method.
    private static final ZipMethod DEFLATED = new ZipMethod(DEFLATED_ID, "deflated");

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

    /// Returns a ZIP method for the given numeric identifier.
    public static ZipMethod of(int id) {
        return switch (id) {
            case STORED_ID -> STORED;
            case DEFLATED_ID -> DEFLATED;
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
