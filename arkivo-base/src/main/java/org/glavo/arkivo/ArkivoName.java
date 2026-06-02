package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Represents a normalized item name inside an archive.
@NotNullByDefault
public final class ArkivoName {
    /// The normalized slash-separated name.
    private final String value;

    /// Creates a normalized archive name.
    private ArkivoName(String value) {
        this.value = value;
    }

    /// Creates a name from the given archive path text.
    public static ArkivoName of(String value) {
        return new ArkivoName(value.replace('\\', '/'));
    }

    /// Returns the normalized slash-separated name.
    public String value() {
        return value;
    }

    /// Returns the normalized slash-separated name.
    @Override
    public String toString() {
        return value;
    }

    /// Compares this name with another object.
    @Override
    public boolean equals(@Nullable Object object) {
        return object instanceof ArkivoName that && value.equals(that.value);
    }

    /// Returns the hash code of the normalized name.
    @Override
    public int hashCode() {
        return Objects.hash(value);
    }
}
