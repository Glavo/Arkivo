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

/// Stores a non-empty set of Arkivo storage access values.
@NotNullByDefault
public final class ArkivoStorageAccessSet {
    /// Opens existing archive storage for random-access reads.
    public static final ArkivoStorageAccessSet RANDOM_READ = of(ArkivoStorageAccess.RANDOM_READ);

    /// Opens archive storage for random-access writes.
    public static final ArkivoStorageAccessSet RANDOM_WRITE = of(ArkivoStorageAccess.RANDOM_WRITE);

    /// Opens archive storage for forward-only streaming reads.
    public static final ArkivoStorageAccessSet STREAM_READ = of(ArkivoStorageAccess.STREAM_READ);

    /// Opens archive storage for forward-only streaming writes.
    public static final ArkivoStorageAccessSet STREAM_WRITE = of(ArkivoStorageAccess.STREAM_WRITE);

    /// The immutable storage access set.
    private final @Unmodifiable Set<ArkivoStorageAccess> access;

    /// Creates an immutable storage access set.
    private ArkivoStorageAccessSet(Set<ArkivoStorageAccess> access) {
        if (access.isEmpty()) {
            throw new IllegalArgumentException("access must not be empty");
        }
        EnumSet<ArkivoStorageAccess> copiedAccess = EnumSet.copyOf(access);
        this.access = Collections.unmodifiableSet(copiedAccess);
    }

    /// Returns a storage access set containing the given value.
    public static ArkivoStorageAccessSet of(ArkivoStorageAccess access) {
        return new ArkivoStorageAccessSet(EnumSet.of(Objects.requireNonNull(access, "access")));
    }

    /// Returns a storage access set containing the given values.
    public static ArkivoStorageAccessSet of(
            ArkivoStorageAccess firstAccess,
            ArkivoStorageAccess secondAccess,
            ArkivoStorageAccess... additionalAccess
    ) {
        EnumSet<ArkivoStorageAccess> access = EnumSet.of(
                Objects.requireNonNull(firstAccess, "firstAccess"),
                Objects.requireNonNull(secondAccess, "secondAccess")
        );
        Collections.addAll(access, additionalAccess);
        return new ArkivoStorageAccessSet(access);
    }

    /// Returns a storage access set containing the given values.
    public static ArkivoStorageAccessSet of(Set<ArkivoStorageAccess> access) {
        return new ArkivoStorageAccessSet(Objects.requireNonNull(access, "access"));
    }

    /// Parses a comma-, plus-, or whitespace-separated storage access list.
    public static ArkivoStorageAccessSet parse(String value) {
        String normalizedValue = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (normalizedValue.isEmpty()) {
            throw new IllegalArgumentException("storageAccess must not be empty");
        }
        if ("random-read-write".equals(normalizedValue)) {
            return of(ArkivoStorageAccess.RANDOM_READ, ArkivoStorageAccess.RANDOM_WRITE);
        }
        if ("stream-read-write".equals(normalizedValue)) {
            return of(ArkivoStorageAccess.STREAM_READ, ArkivoStorageAccess.STREAM_WRITE);
        }

        EnumSet<ArkivoStorageAccess> access = EnumSet.noneOf(ArkivoStorageAccess.class);
        for (String token : normalizedValue.split("[,+\\s]+")) {
            if (!token.isEmpty()) {
                access.add(ArkivoStorageAccess.parse(token));
            }
        }
        return new ArkivoStorageAccessSet(access);
    }

    /// Returns the immutable storage access set.
    public @Unmodifiable Set<ArkivoStorageAccess> access() {
        return access;
    }

    /// Returns whether this set contains the given storage access value.
    public boolean contains(ArkivoStorageAccess access) {
        return this.access.contains(access);
    }

    /// Returns whether this set allows any archive entry reads.
    public boolean readable() {
        return contains(ArkivoStorageAccess.RANDOM_READ) || contains(ArkivoStorageAccess.STREAM_READ);
    }

    /// Returns whether this set allows any archive entry writes.
    public boolean writable() {
        return contains(ArkivoStorageAccess.RANDOM_WRITE) || contains(ArkivoStorageAccess.STREAM_WRITE);
    }

    /// Returns whether this set allows random-access reads.
    public boolean randomReadable() {
        return contains(ArkivoStorageAccess.RANDOM_READ);
    }

    /// Returns whether this set allows random-access writes.
    public boolean randomWritable() {
        return contains(ArkivoStorageAccess.RANDOM_WRITE);
    }

    /// Returns whether this set allows forward-only streaming reads.
    public boolean streamReadable() {
        return contains(ArkivoStorageAccess.STREAM_READ);
    }

    /// Returns whether this set allows forward-only streaming writes.
    public boolean streamWritable() {
        return contains(ArkivoStorageAccess.STREAM_WRITE);
    }

    /// Compares this storage access set with another object.
    @Override
    public boolean equals(@Nullable Object object) {
        return object instanceof ArkivoStorageAccessSet that && access.equals(that.access);
    }

    /// Returns the hash code for this storage access set.
    @Override
    public int hashCode() {
        return access.hashCode();
    }

    /// Returns a stable comma-separated string for this storage access set.
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        for (ArkivoStorageAccess value : access) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(value.optionName());
        }
        return builder.toString();
    }
}
