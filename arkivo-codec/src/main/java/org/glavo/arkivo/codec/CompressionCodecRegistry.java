// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.glavo.arkivo.codec.spi.CompressionCodecProvider;
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

/// Stores an immutable, validated set of discovered compression codecs.
///
/// A registry contains only default immutable codec configurations. Callers derive configured codec values from a
/// selected default without mutating or updating the registry.
@NotNullByDefault
public final class CompressionCodecRegistry {
    /// Default codecs in provider discovery order.
    private final @Unmodifiable List<CompressionCodec> codecs;

    /// Codecs indexed by normalized stable names and aliases.
    private final @Unmodifiable Map<String, CompressionCodec> codecsByName;

    /// The largest preferred signature prefix requested by any codec.
    private final int probeSize;

    /// Creates and validates a registry from default codecs.
    private CompressionCodecRegistry(List<CompressionCodec> codecs) {
        ArrayList<CompressionCodec> copiedCodecs = new ArrayList<>(codecs.size());
        LinkedHashMap<String, CompressionCodec> codecsByName = new LinkedHashMap<>();
        int probeSize = 0;
        for (CompressionCodec codec : codecs) {
            CompressionCodec checkedCodec = Objects.requireNonNull(codec, "provider default codec");
            registerName(codecsByName, checkedCodec.name(), checkedCodec);
            for (String alias : checkedCodec.aliases()) {
                registerName(codecsByName, alias, checkedCodec);
            }

            int codecProbeSize = checkedCodec.probeSize();
            if (codecProbeSize < 0) {
                throw new IllegalStateException(
                        "Compression codec probe size must not be negative: " + checkedCodec.name()
                );
            }
            probeSize = Math.max(probeSize, codecProbeSize);
            copiedCodecs.add(checkedCodec);
        }

        this.codecs = List.copyOf(copiedCodecs);
        this.codecsByName = Map.copyOf(codecsByName);
        this.probeSize = probeSize;
    }

    /// Loads providers visible to the current thread's context class loader.
    public static CompressionCodecRegistry load() {
        return fromProviders(ServiceLoader.load(CompressionCodecProvider.class));
    }

    /// Loads providers visible to the given class loader.
    public static CompressionCodecRegistry load(ClassLoader loader) {
        Objects.requireNonNull(loader, "loader");
        return fromProviders(ServiceLoader.load(CompressionCodecProvider.class, loader));
    }

    /// Loads providers visible to the given module layer.
    public static CompressionCodecRegistry load(ModuleLayer layer) {
        Objects.requireNonNull(layer, "layer");
        return fromProviders(ServiceLoader.load(layer, CompressionCodecProvider.class));
    }

    /// Creates a registry from explicit providers.
    public static CompressionCodecRegistry fromProviders(
            Iterable<? extends CompressionCodecProvider> providers
    ) {
        Objects.requireNonNull(providers, "providers");
        ArrayList<CompressionCodec> codecs = new ArrayList<>();
        for (CompressionCodecProvider provider : providers) {
            CompressionCodecProvider checkedProvider = Objects.requireNonNull(provider, "provider");
            codecs.add(Objects.requireNonNull(checkedProvider.defaultCodec(), "provider default codec"));
        }
        return new CompressionCodecRegistry(codecs);
    }

    /// Returns default codecs in provider discovery order.
    public @Unmodifiable List<CompressionCodec> codecs() {
        return codecs;
    }

    /// Returns the default codec with the given stable name or alias, ignoring case.
    public @Nullable CompressionCodec find(String name) {
        Objects.requireNonNull(name, "name");
        return codecsByName.get(normalizeName(name));
    }

    /// Returns the default codec with the given stable name or alias.
    ///
    /// @throws IllegalArgumentException when no matching codec is installed
    public CompressionCodec require(String name) {
        @Nullable CompressionCodec codec = find(name);
        if (codec == null) {
            throw new IllegalArgumentException("Unknown compression codec: " + name);
        }
        return codec;
    }

    /// Returns the default codec with the given name or alias and verifies its concrete API type.
    ///
    /// @param <C> the required codec type
    /// @throws IllegalArgumentException when no matching codec is installed or its type does not match
    public <C extends CompressionCodec> C require(String name, Class<C> type) {
        Objects.requireNonNull(type, "type");
        CompressionCodec codec = require(name);
        if (!type.isInstance(codec)) {
            throw new IllegalArgumentException(
                    "Compression codec " + codec.name() + " does not implement " + type.getName()
            );
        }
        return type.cast(codec);
    }

    /// Returns the first default codec matching the remaining prefix bytes.
    ///
    /// The supplied buffer is not modified.
    public @Nullable CompressionCodec detect(ByteBuffer prefix) {
        Objects.requireNonNull(prefix, "prefix");
        for (CompressionCodec codec : codecs) {
            if (codec.matches(prefix.asReadOnlyBuffer())) {
                return codec;
            }
        }
        return null;
    }

    /// Returns the largest preferred signature prefix requested by any codec.
    public int probeSize() {
        return probeSize;
    }

    /// Registers one stable name or alias after validating it for unambiguous lookup.
    private static void registerName(
            Map<String, CompressionCodec> codecsByName,
            String name,
            CompressionCodec codec
    ) {
        Objects.requireNonNull(name, "codec name");
        if (name.isBlank()) {
            throw new IllegalStateException("Compression codec names and aliases must not be blank");
        }
        String normalizedName = normalizeName(name);
        @Nullable CompressionCodec previous = codecsByName.putIfAbsent(normalizedName, codec);
        if (previous != null && previous != codec) {
            throw new IllegalStateException(
                    "Ambiguous compression codec name or alias " + name
                            + ": " + previous.name() + " and " + codec.name()
            );
        }
    }

    /// Normalizes one codec lookup name without using the process locale.
    private static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
