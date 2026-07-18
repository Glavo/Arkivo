// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;

/// Stores an immutable validated set of discoverable archive formats.
@NotNullByDefault
public final class ArchiveFormatRegistry {
    /// Formats in discovery or caller-supplied order, with repeated logical providers included once.
    private final @Unmodifiable List<ArkivoFormat> formats;

    /// Formats indexed by normalized stable names and aliases.
    private final @Unmodifiable Map<String, ArkivoFormat> formatsByName;

    /// The largest preferred signature prefix requested by any format.
    private final int probeSize;

    /// Validates and indexes supplied format descriptors.
    private ArchiveFormatRegistry(List<ArkivoFormat> formats) {
        ArrayList<ArkivoFormat> copiedFormats = new ArrayList<>(formats.size());
        Set<FormatIdentity> seenFormats = new HashSet<>();
        LinkedHashMap<String, ArkivoFormat> formatsByName = new LinkedHashMap<>();
        int maximumProbeSize = 0;
        for (ArkivoFormat format : formats) {
            ArkivoFormat checkedFormat = Objects.requireNonNull(format, "archive format");
            FormatIdentity identity = new FormatIdentity(
                    checkedFormat.getClass(),
                    normalizeAndValidateName(checkedFormat.name())
            );
            if (!seenFormats.add(identity)) {
                continue;
            }

            registerName(formatsByName, checkedFormat.name(), checkedFormat);
            for (String alias : checkedFormat.aliases()) {
                registerName(formatsByName, alias, checkedFormat);
            }
            int formatProbeSize = checkedFormat.probeSize();
            if (formatProbeSize < 0) {
                throw new IllegalStateException(
                        "Archive format probe size must not be negative: " + checkedFormat.name()
                );
            }
            maximumProbeSize = Math.max(maximumProbeSize, formatProbeSize);
            copiedFormats.add(checkedFormat);
        }
        this.formats = List.copyOf(copiedFormats);
        this.formatsByName = Map.copyOf(formatsByName);
        this.probeSize = maximumProbeSize;
    }

    /// Loads formats visible to the current thread context class loader.
    ///
    /// @return an immutable registry containing the discovered formats
    public static ArchiveFormatRegistry load() {
        return fromFormats(ServiceLoader.load(ArkivoFormat.class));
    }

    /// Loads formats visible to the given class loader.
    ///
    /// @param loader the class loader from which providers are discovered
    /// @return an immutable registry containing the discovered formats
    public static ArchiveFormatRegistry load(ClassLoader loader) {
        Objects.requireNonNull(loader, "loader");
        return fromFormats(ServiceLoader.load(ArkivoFormat.class, loader));
    }

    /// Loads formats visible to the given module layer.
    ///
    /// @param layer the module layer from which providers are discovered
    /// @return an immutable registry containing the discovered formats
    public static ArchiveFormatRegistry load(ModuleLayer layer) {
        Objects.requireNonNull(layer, "layer");
        return fromFormats(ServiceLoader.load(layer, ArkivoFormat.class));
    }

    /// Creates a registry from explicit format descriptors.
    ///
    /// @param formats the descriptors to validate and index, in preferred discovery order
    /// @return an immutable registry containing each logical provider at most once
    /// @throws IllegalStateException if a descriptor has an invalid probe size or formats claim the same name
    public static ArchiveFormatRegistry fromFormats(Iterable<? extends ArkivoFormat> formats) {
        Objects.requireNonNull(formats, "formats");
        ArrayList<ArkivoFormat> copiedFormats = new ArrayList<>();
        for (ArkivoFormat format : formats) {
            copiedFormats.add(Objects.requireNonNull(format, "archive format"));
        }
        return new ArchiveFormatRegistry(copiedFormats);
    }

    /// Returns formats in discovery or caller-supplied order, with repeated logical providers included once.
    ///
    /// @return the immutable ordered format list
    public @Unmodifiable List<ArkivoFormat> formats() {
        return formats;
    }

    /// Returns the named format or null when no stable name or alias matches.
    ///
    /// @param name the case-insensitive stable name or alias to look up
    /// @return the matching format, or {@code null} if no format matches
    public @Nullable ArkivoFormat find(String name) {
        Objects.requireNonNull(name, "name");
        return formatsByName.get(normalizeName(name));
    }

    /// Returns the named format.
    ///
    /// @param name the case-insensitive stable name or alias to look up
    /// @return the matching format
    /// @throws IllegalArgumentException when no stable name or alias matches
    public ArkivoFormat require(String name) {
        @Nullable ArkivoFormat format = find(name);
        if (format == null) {
            throw new IllegalArgumentException("Unknown archive format: " + name);
        }
        return format;
    }

    /// Returns the matching format with the largest preferred probe size.
    ///
    /// The supplied buffer is not modified.
    ///
    /// @param prefix the archive prefix to test, from its current position to its limit
    /// @return the best matching format, or {@code null} if no format recognizes the prefix
    public @Nullable ArkivoFormat detect(ByteBuffer prefix) {
        Objects.requireNonNull(prefix, "prefix");
        @Nullable ArkivoFormat detected = null;
        int detectedProbeSize = -1;
        for (ArkivoFormat format : formats) {
            if (format.matches(prefix.asReadOnlyBuffer()) && format.probeSize() > detectedProbeSize) {
                detected = format;
                detectedProbeSize = format.probeSize();
            }
        }
        return detected;
    }

    /// Returns the largest preferred signature prefix requested by any format.
    ///
    /// @return the maximum value returned by a registered format's {@link ArkivoFormat#probeSize()} method
    public int probeSize() {
        return probeSize;
    }

    /// Registers one stable name or alias after validating it for unambiguous lookup.
    private static void registerName(
            Map<String, ArkivoFormat> formatsByName,
            String name,
            ArkivoFormat format
    ) {
        String normalizedName = normalizeAndValidateName(name);
        @Nullable ArkivoFormat previous = formatsByName.putIfAbsent(normalizedName, format);
        if (previous != null && previous != format) {
            throw new IllegalStateException(
                    "Ambiguous archive format name or alias " + name
                            + ": " + previous.name() + " and " + format.name()
            );
        }
    }

    /// Normalizes one lookup name without using the process locale.
    private static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /// Validates and normalizes one stable name or alias.
    private static String normalizeAndValidateName(String name) {
        Objects.requireNonNull(name, "format name");
        if (name.isBlank()) {
            throw new IllegalStateException("Archive format names and aliases must not be blank");
        }
        return normalizeName(name);
    }

    /// Identifies one logical provider across module-path and classpath service discovery.
    ///
    /// @param implementation the concrete provider implementation class
    /// @param name the normalized stable format name
    private record FormatIdentity(
            Class<? extends ArkivoFormat> implementation,
            String name
    ) {
        /// Validates one logical provider identity.
        private FormatIdentity {
            Objects.requireNonNull(implementation, "implementation");
            Objects.requireNonNull(name, "name");
        }
    }
}
