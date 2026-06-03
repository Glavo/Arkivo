// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/// Stores a non-empty set of Arkivo file system open capabilities.
@NotNullByDefault
public final class ArkivoFileSystemOpenModes {
    /// Opens existing archive storage for random-access reads.
    public static final ArkivoFileSystemOpenModes RANDOM_READ = of(ArkivoFileSystemOpenMode.RANDOM_READ);

    /// Opens archive storage for random-access writes.
    public static final ArkivoFileSystemOpenModes RANDOM_WRITE = of(ArkivoFileSystemOpenMode.RANDOM_WRITE);

    /// Opens archive storage for forward-only streaming reads.
    public static final ArkivoFileSystemOpenModes STREAM_READ = of(ArkivoFileSystemOpenMode.STREAM_READ);

    /// Opens archive storage for forward-only streaming writes.
    public static final ArkivoFileSystemOpenModes STREAM_WRITE = of(ArkivoFileSystemOpenMode.STREAM_WRITE);

    /// The immutable open capability set.
    private final @Unmodifiable Set<ArkivoFileSystemOpenMode> modes;

    /// Creates an immutable open capability set.
    private ArkivoFileSystemOpenModes(Set<ArkivoFileSystemOpenMode> modes) {
        if (modes.isEmpty()) {
            throw new IllegalArgumentException("modes must not be empty");
        }
        EnumSet<ArkivoFileSystemOpenMode> copiedModes = EnumSet.copyOf(modes);
        this.modes = Collections.unmodifiableSet(copiedModes);
    }

    /// Returns an open capability set containing the given mode.
    public static ArkivoFileSystemOpenModes of(ArkivoFileSystemOpenMode mode) {
        return new ArkivoFileSystemOpenModes(EnumSet.of(Objects.requireNonNull(mode, "mode")));
    }

    /// Returns an open capability set containing the given modes.
    public static ArkivoFileSystemOpenModes of(
            ArkivoFileSystemOpenMode firstMode,
            ArkivoFileSystemOpenMode secondMode,
            ArkivoFileSystemOpenMode... additionalModes
    ) {
        EnumSet<ArkivoFileSystemOpenMode> modes = EnumSet.of(
                Objects.requireNonNull(firstMode, "firstMode"),
                Objects.requireNonNull(secondMode, "secondMode")
        );
        Collections.addAll(modes, additionalModes);
        return new ArkivoFileSystemOpenModes(modes);
    }

    /// Returns an open capability set containing the given modes.
    public static ArkivoFileSystemOpenModes of(Set<ArkivoFileSystemOpenMode> modes) {
        return new ArkivoFileSystemOpenModes(Objects.requireNonNull(modes, "modes"));
    }

    /// Parses a comma-, plus-, or whitespace-separated open capability list.
    public static ArkivoFileSystemOpenModes parse(String value) {
        String normalizedValue = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (normalizedValue.isEmpty()) {
            throw new IllegalArgumentException("openModes must not be empty");
        }
        if ("random-read-write".equals(normalizedValue)) {
            return of(ArkivoFileSystemOpenMode.RANDOM_READ, ArkivoFileSystemOpenMode.RANDOM_WRITE);
        }
        if ("stream-read-write".equals(normalizedValue)) {
            return of(ArkivoFileSystemOpenMode.STREAM_READ, ArkivoFileSystemOpenMode.STREAM_WRITE);
        }

        EnumSet<ArkivoFileSystemOpenMode> modes = EnumSet.noneOf(ArkivoFileSystemOpenMode.class);
        for (String token : normalizedValue.split("[,+\\s]+")) {
            if (!token.isEmpty()) {
                modes.add(ArkivoFileSystemOpenMode.parse(token));
            }
        }
        return new ArkivoFileSystemOpenModes(modes);
    }

    /// Returns the immutable open capability set.
    public @Unmodifiable Set<ArkivoFileSystemOpenMode> modes() {
        return modes;
    }

    /// Returns whether this set contains the given mode.
    public boolean contains(ArkivoFileSystemOpenMode mode) {
        return modes.contains(mode);
    }

    /// Returns whether this set allows any archive entry reads.
    public boolean readable() {
        return contains(ArkivoFileSystemOpenMode.RANDOM_READ) || contains(ArkivoFileSystemOpenMode.STREAM_READ);
    }

    /// Returns whether this set allows any archive entry writes.
    public boolean writable() {
        return contains(ArkivoFileSystemOpenMode.RANDOM_WRITE) || contains(ArkivoFileSystemOpenMode.STREAM_WRITE);
    }

    /// Returns whether this set allows random-access reads.
    public boolean randomReadable() {
        return contains(ArkivoFileSystemOpenMode.RANDOM_READ);
    }

    /// Returns whether this set allows random-access writes.
    public boolean randomWritable() {
        return contains(ArkivoFileSystemOpenMode.RANDOM_WRITE);
    }

    /// Returns whether this set allows forward-only streaming reads.
    public boolean streamReadable() {
        return contains(ArkivoFileSystemOpenMode.STREAM_READ);
    }

    /// Returns whether this set allows forward-only streaming writes.
    public boolean streamWritable() {
        return contains(ArkivoFileSystemOpenMode.STREAM_WRITE);
    }

    /// Compares this open capability set with another object.
    @Override
    public boolean equals(@Nullable Object object) {
        return object instanceof ArkivoFileSystemOpenModes that && modes.equals(that.modes);
    }

    /// Returns the hash code for this open capability set.
    @Override
    public int hashCode() {
        return modes.hashCode();
    }

    /// Returns a stable comma-separated string for this open capability set.
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        for (ArkivoFileSystemOpenMode mode : modes) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(mode.optionName());
        }
        return builder.toString();
    }
}
