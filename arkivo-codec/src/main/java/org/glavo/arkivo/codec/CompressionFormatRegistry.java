// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;

/// Stores an immutable, validated set of discovered compression formats.
///
/// A registry contains canonical format identities. Each format exposes one immutable default codec from which callers
/// derive immutable configured codec values.
@NotNullByDefault
public final class CompressionFormatRegistry {
    /// Formats in service discovery order.
    private final @Unmodifiable List<CompressionFormat> formats;

    /// Formats indexed by normalized stable names and aliases.
    private final @Unmodifiable Map<String, CompressionFormat> formatsByName;

    /// The largest preferred signature prefix requested by any format.
    private final int probeSize;

    /// Creates and validates a registry by normalizing supplied descriptors to canonical format identities.
    private CompressionFormatRegistry(List<CompressionFormat> formats) {
        ArrayList<CompressionFormat> copiedFormats = new ArrayList<>(formats.size());
        LinkedHashMap<String, CompressionFormat> formatsByName = new LinkedHashMap<>();
        int probeSize = 0;
        for (CompressionFormat format : formats) {
            CompressionFormat checkedFormat = canonicalizeFormat(format);

            registerName(formatsByName, checkedFormat.name(), checkedFormat);
            for (String alias : checkedFormat.aliases()) {
                registerName(formatsByName, alias, checkedFormat);
            }

            int formatProbeSize = checkedFormat.probeSize();
            if (formatProbeSize < 0) {
                throw new IllegalStateException(
                        "Compression format probe size must not be negative: " + checkedFormat.name()
                );
            }
            probeSize = Math.max(probeSize, formatProbeSize);
            copiedFormats.add(checkedFormat);
        }

        this.formats = List.copyOf(copiedFormats);
        this.formatsByName = Map.copyOf(formatsByName);
        this.probeSize = probeSize;
    }

    /// Loads formats visible to the current thread's context class loader.
    public static CompressionFormatRegistry load() {
        return fromFormats(ServiceLoader.load(CompressionFormat.class));
    }

    /// Loads formats visible to the given class loader.
    public static CompressionFormatRegistry load(ClassLoader loader) {
        Objects.requireNonNull(loader, "loader");
        return fromFormats(ServiceLoader.load(CompressionFormat.class, loader));
    }

    /// Loads formats visible to the given module layer.
    public static CompressionFormatRegistry load(ModuleLayer layer) {
        Objects.requireNonNull(layer, "layer");
        return fromFormats(ServiceLoader.load(layer, CompressionFormat.class));
    }

    /// Creates a registry from explicit format descriptors.
    public static CompressionFormatRegistry fromFormats(
            Iterable<? extends CompressionFormat> formats
    ) {
        Objects.requireNonNull(formats, "formats");
        ArrayList<CompressionFormat> copiedFormats = new ArrayList<>();
        for (CompressionFormat format : formats) {
            copiedFormats.add(Objects.requireNonNull(format, "compression format"));
        }
        return new CompressionFormatRegistry(copiedFormats);
    }

    /// Returns formats in service discovery order.
    public @Unmodifiable List<CompressionFormat> formats() {
        return formats;
    }

    /// Returns the format with the given stable name or alias, ignoring case.
    public @Nullable CompressionFormat find(String name) {
        Objects.requireNonNull(name, "name");
        return formatsByName.get(normalizeName(name));
    }

    /// Returns the format with the given stable name or alias.
    ///
    /// @throws IllegalArgumentException when no matching format is installed
    public CompressionFormat require(String name) {
        @Nullable CompressionFormat format = find(name);
        if (format == null) {
            throw new IllegalArgumentException("Unknown compression format: " + name);
        }
        return format;
    }

    /// Returns the first format matching the remaining prefix bytes.
    ///
    /// The supplied buffer is not modified.
    public @Nullable CompressionFormat detect(ByteBuffer prefix) {
        Objects.requireNonNull(prefix, "prefix");
        for (CompressionFormat format : formats) {
            if (format.matches(prefix.asReadOnlyBuffer())) {
                return format;
            }
        }
        return null;
    }

    /// Returns the largest preferred signature prefix requested by any format.
    public int probeSize() {
        return probeSize;
    }

    /// Resolves a service-created descriptor to the canonical identity exposed by its default codec.
    private static CompressionFormat canonicalizeFormat(CompressionFormat format) {
        CompressionFormat suppliedFormat = Objects.requireNonNull(format, "compression format");
        CompressionCodec defaultCodec = Objects.requireNonNull(
                suppliedFormat.defaultCodec(),
                "format default codec"
        );
        CompressionFormat canonicalFormat = Objects.requireNonNull(
                defaultCodec.format(),
                "default codec format"
        );
        if (canonicalFormat != suppliedFormat
                && canonicalFormat.getClass() != suppliedFormat.getClass()) {
            throw new IllegalStateException(
                    "Compression format " + suppliedFormat.name()
                            + " does not match its default codec format " + canonicalFormat.name()
            );
        }
        CompressionCodec canonicalDefaultCodec = Objects.requireNonNull(
                canonicalFormat.defaultCodec(),
                "canonical format default codec"
        );
        if (canonicalDefaultCodec.format() != canonicalFormat) {
            throw new IllegalStateException(
                    "Compression format " + canonicalFormat.name()
                            + " is not the identity returned by its default codec"
            );
        }
        return canonicalFormat;
    }
    /// Registers one stable name or alias after validating it for unambiguous lookup.
    private static void registerName(
            Map<String, CompressionFormat> formatsByName,
            String name,
            CompressionFormat format
    ) {
        Objects.requireNonNull(name, "format name");
        if (name.isBlank()) {
            throw new IllegalStateException("Compression format names and aliases must not be blank");
        }
        String normalizedName = normalizeName(name);
        @Nullable CompressionFormat previous = formatsByName.putIfAbsent(normalizedName, format);
        if (previous != null && previous != format) {
            throw new IllegalStateException(
                    "Ambiguous compression format name or alias " + name
                            + ": " + previous.name() + " and " + format.name()
            );
        }
    }

    /// Normalizes one format lookup name without using the process locale.
    private static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
