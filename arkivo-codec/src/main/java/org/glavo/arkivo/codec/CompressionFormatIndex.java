// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Indexes the official compression formats present in the runtime image.
///
/// The implementation list is intentionally closed. Classes that are absent represent official optional modules that
/// were not installed; arbitrary implementations of [CompressionFormat] are never discovered.
@NotNullByDefault
final class CompressionFormatIndex {
    /// Official format singleton classes in deterministic detection order.
    private static final @Unmodifiable List<String> FORMAT_CLASS_NAMES = List.of(
            "org.glavo.arkivo.codec.bzip2.BZip2Format",
            "org.glavo.arkivo.codec.compress.UnixCompressFormat",
            "org.glavo.arkivo.codec.deflate.DeflateFormat",
            "org.glavo.arkivo.codec.deflate.Deflate64Format",
            "org.glavo.arkivo.codec.deflate.GzipFormat",
            "org.glavo.arkivo.codec.deflate.ZlibFormat",
            "org.glavo.arkivo.codec.lz4.LZ4Format",
            "org.glavo.arkivo.codec.lz4.LZ4BlockFormat",
            "org.glavo.arkivo.codec.lzip.LzipFormat",
            "org.glavo.arkivo.codec.lzma.LZMAFormat",
            "org.glavo.arkivo.codec.lzma.RawLZMAFormat",
            "org.glavo.arkivo.codec.lzma.LZMA2Format",
            "org.glavo.arkivo.codec.ppmd.PPMdFormat",
            "org.glavo.arkivo.codec.xz.XZFormat",
            "org.glavo.arkivo.codec.zstd.ZstdFormat"
    );

    /// Canonical formats in deterministic official order.
    private final @Unmodifiable List<CompressionFormat> formats;

    /// Formats indexed by normalized stable names and aliases.
    private final @Unmodifiable Map<String, CompressionFormat> formatsByName;

    /// The largest preferred signature prefix requested by any format.
    private final int probeSize;

    /// Creates and validates an index by normalizing supplied descriptors to canonical format identities.
    private CompressionFormatIndex(List<CompressionFormat> formats) {
        ArrayList<CompressionFormat> copiedFormats = new ArrayList<>(formats.size());
        Set<CompressionFormat> seenFormats = Collections.newSetFromMap(new IdentityHashMap<>());
        LinkedHashMap<String, CompressionFormat> formatsByName = new LinkedHashMap<>();
        int maximumProbeSize = 0;
        for (CompressionFormat format : formats) {
            CompressionFormat checkedFormat = canonicalizeFormat(format);
            if (!seenFormats.add(checkedFormat)) {
                continue;
            }

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
            maximumProbeSize = Math.max(maximumProbeSize, formatProbeSize);
            copiedFormats.add(checkedFormat);
        }

        this.formats = List.copyOf(copiedFormats);
        this.formatsByName = Map.copyOf(formatsByName);
        this.probeSize = maximumProbeSize;
    }

    /// Loads every present official format through its immutable singleton accessor.
    ///
    /// @return an immutable index of the official format modules present to this module's class loader
    static CompressionFormatIndex loadBuiltins() {
        ArrayList<CompressionFormat> formats = new ArrayList<>(FORMAT_CLASS_NAMES.size());
        for (String className : FORMAT_CLASS_NAMES) {
            @Nullable CompressionFormat format = loadFormat(className);
            if (format != null) {
                formats.add(format);
            }
        }
        return new CompressionFormatIndex(formats);
    }

    /// Creates an index from explicit descriptors for internal validation and tests.
    ///
    /// @param formats the descriptors to canonicalize and index in preferred order
    /// @return an immutable index containing each canonical identity at most once
    static CompressionFormatIndex of(Iterable<? extends CompressionFormat> formats) {
        Objects.requireNonNull(formats, "formats");
        ArrayList<CompressionFormat> copiedFormats = new ArrayList<>();
        for (CompressionFormat format : formats) {
            copiedFormats.add(Objects.requireNonNull(format, "compression format"));
        }
        return new CompressionFormatIndex(copiedFormats);
    }

    /// Returns canonical formats in deterministic official order.
    ///
    /// @return the immutable ordered canonical format list
    @Unmodifiable List<CompressionFormat> formats() {
        return formats;
    }

    /// Returns the format with the given stable name or alias, ignoring case.
    ///
    /// @param name the stable format name or alias
    /// @return the matching format, or `null` if none matches
    @Nullable CompressionFormat find(String name) {
        Objects.requireNonNull(name, "name");
        return formatsByName.get(normalizeName(name));
    }

    /// Returns the format with the given stable name or alias.
    ///
    /// @param name the stable format name or alias
    /// @return the matching format
    /// @throws IllegalArgumentException when no matching official format is installed
    CompressionFormat require(String name) {
        @Nullable CompressionFormat format = find(name);
        if (format == null) {
            throw new IllegalArgumentException("Unknown compression format: " + name);
        }
        return format;
    }

    /// Returns the matching format with the largest preferred probe size.
    ///
    /// The supplied buffer is not modified.
    ///
    /// @param prefix the stream prefix to inspect, from its current position to its limit
    /// @return the best matching format, or `null` if no format recognizes the prefix
    @Nullable CompressionFormat detect(ByteBuffer prefix) {
        Objects.requireNonNull(prefix, "prefix");
        @Nullable CompressionFormat detected = null;
        int detectedProbeSize = -1;
        for (CompressionFormat format : formats) {
            if (format.matches(prefix.asReadOnlyBuffer()) && format.probeSize() > detectedProbeSize) {
                detected = format;
                detectedProbeSize = format.probeSize();
            }
        }
        return detected;
    }

    /// Returns the largest preferred signature prefix requested by an installed official format.
    ///
    /// @return the maximum indexed [CompressionFormat#probeSize()] value
    int probeSize() {
        return probeSize;
    }

    /// Loads one known singleton class, or returns null when its optional module is absent.
    private static @Nullable CompressionFormat loadFormat(String className) {
        try {
            Class<?> formatClass = Class.forName(className);
            Method instanceMethod = formatClass.getMethod("instance");
            if (!Modifier.isStatic(instanceMethod.getModifiers())) {
                throw new IllegalStateException("Built-in compression format instance() is not static: " + className);
            }
            Object value = instanceMethod.invoke(null);
            if (!(value instanceof CompressionFormat format)) {
                throw new IllegalStateException(
                        "Built-in compression format has an incompatible instance: " + className
                );
            }
            return format;
        } catch (ClassNotFoundException ignored) {
            return null;
        } catch (ReflectiveOperationException | LinkageError | SecurityException exception) {
            throw new IllegalStateException("Failed to load built-in compression format: " + className, exception);
        }
    }

    /// Resolves a descriptor to the canonical identity exposed by its default codec.
    private static CompressionFormat canonicalizeFormat(CompressionFormat format) {
        CompressionFormat suppliedFormat = Objects.requireNonNull(format, "compression format");
        CompressionCodec<?> defaultCodec = Objects.requireNonNull(
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
        CompressionCodec<?> canonicalDefaultCodec = Objects.requireNonNull(
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
