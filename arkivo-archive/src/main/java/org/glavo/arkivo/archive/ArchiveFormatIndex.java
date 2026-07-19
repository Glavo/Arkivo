// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Indexes the official archive formats present in the runtime image.
///
/// The implementation list is intentionally closed. Classes that are absent represent official optional modules that
/// were not installed; arbitrary implementations of [ArkivoFormat] are never discovered.
@NotNullByDefault
final class ArchiveFormatIndex {
    /// Official format singleton classes in deterministic detection order.
    private static final @Unmodifiable List<String> FORMAT_CLASS_NAMES = List.of(
            "org.glavo.arkivo.archive.sevenzip.SevenZipArkivoFormat",
            "org.glavo.arkivo.archive.ar.ArArkivoFormat",
            "org.glavo.arkivo.archive.cpio.CPIOArkivoFormat",
            "org.glavo.arkivo.archive.rar.RarArkivoFormat",
            "org.glavo.arkivo.archive.tar.TarArkivoFormat",
            "org.glavo.arkivo.archive.zip.ZipArkivoFormat"
    );

    /// Formats in deterministic official order, with repeated logical identities included once.
    private final @Unmodifiable List<ArkivoFormat> formats;

    /// Formats indexed by normalized stable names and aliases.
    private final @Unmodifiable Map<String, ArkivoFormat> formatsByName;

    /// The largest preferred signature prefix requested by any format.
    private final int probeSize;

    /// Validates and indexes supplied format descriptors.
    private ArchiveFormatIndex(List<ArkivoFormat> formats) {
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

    /// Loads every present official format through its immutable singleton accessor.
    ///
    /// @return an immutable index of the official format modules present to this module's class loader
    static ArchiveFormatIndex loadBuiltins() {
        ArrayList<ArkivoFormat> formats = new ArrayList<>(FORMAT_CLASS_NAMES.size());
        for (String className : FORMAT_CLASS_NAMES) {
            @Nullable ArkivoFormat format = loadFormat(className);
            if (format != null) {
                formats.add(format);
            }
        }
        return new ArchiveFormatIndex(formats);
    }

    /// Creates an index from explicit descriptors for internal validation and tests.
    ///
    /// @param formats the descriptors to validate and index in preferred order
    /// @return an immutable index containing each logical identity at most once
    static ArchiveFormatIndex of(Iterable<? extends ArkivoFormat> formats) {
        Objects.requireNonNull(formats, "formats");
        ArrayList<ArkivoFormat> copiedFormats = new ArrayList<>();
        for (ArkivoFormat format : formats) {
            copiedFormats.add(Objects.requireNonNull(format, "archive format"));
        }
        return new ArchiveFormatIndex(copiedFormats);
    }

    /// Returns formats in deterministic official order.
    ///
    /// @return the immutable ordered format list
    @Unmodifiable List<ArkivoFormat> formats() {
        return formats;
    }

    /// Returns the named format or null when no stable name or alias matches.
    ///
    /// @param name the case-insensitive stable name or alias to look up
    /// @return the matching format, or `null` if no format matches
    @Nullable ArkivoFormat find(String name) {
        Objects.requireNonNull(name, "name");
        return formatsByName.get(normalizeName(name));
    }

    /// Returns the named format.
    ///
    /// @param name the case-insensitive stable name or alias to look up
    /// @return the matching format
    /// @throws IllegalArgumentException when no stable name or alias matches
    ArkivoFormat require(String name) {
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
    /// @return the best matching format, or `null` if no format recognizes the prefix
    @Nullable ArkivoFormat detect(ByteBuffer prefix) {
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

    /// Returns the largest preferred signature prefix requested by an installed official format.
    ///
    /// @return the maximum indexed [ArkivoFormat#probeSize()] value
    int probeSize() {
        return probeSize;
    }

    /// Loads one known singleton class, or returns null when its optional module is absent.
    private static @Nullable ArkivoFormat loadFormat(String className) {
        try {
            Class<?> formatClass = Class.forName(className);
            Method instanceMethod = formatClass.getMethod("instance");
            if (!Modifier.isStatic(instanceMethod.getModifiers())) {
                throw new IllegalStateException("Built-in archive format instance() is not static: " + className);
            }
            Object value = instanceMethod.invoke(null);
            if (!(value instanceof ArkivoFormat format)) {
                throw new IllegalStateException("Built-in archive format has an incompatible instance: " + className);
            }
            return format;
        } catch (ClassNotFoundException ignored) {
            return null;
        } catch (ReflectiveOperationException | LinkageError | SecurityException exception) {
            throw new IllegalStateException("Failed to load built-in archive format: " + className, exception);
        }
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

    /// Identifies one logical official format.
    ///
    /// @param implementation the concrete format implementation class
    /// @param name           the normalized stable format name
    @NotNullByDefault
    private record FormatIdentity(
            Class<? extends ArkivoFormat> implementation,
            String name
    ) {
        /// Validates one logical format identity.
        private FormatIdentity {
            Objects.requireNonNull(implementation, "implementation");
            Objects.requireNonNull(name, "name");
        }
    }
}
