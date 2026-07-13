// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/// Resolves conventional numbered RAR split volume paths.
@NotNullByDefault
public final class RarSplitVolumePaths {
    /// The first modern numbered RAR volume.
    private static final int FIRST_MODERN_VOLUME_NUMBER = 1;

    /// The first modern continuation RAR volume.
    private static final int FIRST_MODERN_CONTINUATION_VOLUME_NUMBER = 2;

    /// The first legacy continuation RAR volume.
    private static final int FIRST_LEGACY_CONTINUATION_VOLUME_NUMBER = 0;

    /// The number of legacy volumes represented by one extension letter.
    private static final int LEGACY_VOLUMES_PER_EXTENSION_LETTER = 100;

    /// The maximum supported zero-based legacy continuation volume number.
    private static final int MAX_LEGACY_CONTINUATION_VOLUME_NUMBER =
            ('z' - 'r' + 1) * LEGACY_VOLUMES_PER_EXTENSION_LETTER - 1;

    /// The sentinel returned when a file name does not contain a volume number.
    private static final int NO_VOLUME_NUMBER = -1;

    /// Prevents instantiation.
    private RarSplitVolumePaths() {
    }

    /// Returns conventional split volume paths for a first-volume path, or `null` for a single-volume path.
    public static @Nullable @Unmodifiable List<Path> discover(Path firstVolumePath) throws IOException {
        if (!Files.exists(firstVolumePath)) {
            return null;
        }

        Path fileNamePath = firstVolumePath.getFileName();
        if (fileNamePath == null) {
            return null;
        }

        String fileName = fileNamePath.toString();
        @Nullable VolumePathResolver resolver = existingContinuationResolver(modernPartResolver(firstVolumePath, fileName));
        if (resolver == null) {
            resolver = existingContinuationResolver(plainRarPartResolver(firstVolumePath, fileName));
        }
        if (resolver == null) {
            resolver = existingContinuationResolver(legacyRarResolver(firstVolumePath, fileName));
        }
        if (resolver == null) {
            return null;
        }

        ArrayList<Path> paths = new ArrayList<>();
        paths.add(firstVolumePath);
        for (int volumeNumber = resolver.firstContinuationVolumeNumber(); ; volumeNumber++) {
            Path volumePath = resolver.path(volumeNumber);
            if (!Files.exists(volumePath)) {
                break;
            }
            paths.add(volumePath);
            if (volumeNumber == resolver.maximumVolumeNumber()) {
                throw new IOException("RAR split archive has too many conventional volumes");
            }
        }
        return List.copyOf(paths);
    }

    /// Returns the resolver only when its first continuation volume exists.
    private static @Nullable VolumePathResolver existingContinuationResolver(@Nullable VolumePathResolver resolver) {
        if (resolver == null || !Files.exists(resolver.path(resolver.firstContinuationVolumeNumber()))) {
            return null;
        }
        return resolver;
    }

    /// Returns a resolver for `name.part1.rar` style volume names.
    private static @Nullable NumberedPathResolver modernPartResolver(Path firstVolumePath, String fileName) {
        String lowerFileName = fileName.toLowerCase(Locale.ROOT);
        if (!lowerFileName.endsWith(".rar")) {
            return null;
        }

        int rarExtensionStart = fileName.length() - ".rar".length();
        int markerStart = lowerFileName.lastIndexOf(".part", rarExtensionStart);
        if (markerStart < 0) {
            return null;
        }
        int numberStart = markerStart + ".part".length();
        int volumeNumber = decimalNumber(fileName, numberStart, rarExtensionStart);
        if (volumeNumber != FIRST_MODERN_VOLUME_NUMBER) {
            return null;
        }

        String prefix = fileName.substring(0, numberStart);
        int width = rarExtensionStart - numberStart;
        String suffix = fileName.substring(rarExtensionStart);
        Path parent = firstVolumePath.getParent();
        return new NumberedPathResolver(
                parent,
                prefix,
                width,
                suffix,
                FIRST_MODERN_CONTINUATION_VOLUME_NUMBER,
                Integer.MAX_VALUE
        );
    }

    /// Returns a resolver for `name.rar`, `name.part2.rar` style volume names.
    private static @Nullable NumberedPathResolver plainRarPartResolver(Path firstVolumePath, String fileName) {
        String lowerFileName = fileName.toLowerCase(Locale.ROOT);
        if (!lowerFileName.endsWith(".rar") || lowerFileName.endsWith(".part1.rar")) {
            return null;
        }

        int rarExtensionStart = fileName.length() - ".rar".length();
        String prefix = fileName.substring(0, rarExtensionStart) + ".part";
        String suffix = fileName.substring(rarExtensionStart);
        Path parent = firstVolumePath.getParent();
        return new NumberedPathResolver(
                parent,
                prefix,
                1,
                suffix,
                FIRST_MODERN_CONTINUATION_VOLUME_NUMBER,
                Integer.MAX_VALUE
        );
    }

    /// Returns a resolver for `name.rar`, `name.r00` style volume names.
    private static @Nullable LegacyPathResolver legacyRarResolver(Path firstVolumePath, String fileName) {
        String lowerFileName = fileName.toLowerCase(Locale.ROOT);
        if (!lowerFileName.endsWith(".rar")) {
            return null;
        }

        String prefix = fileName.substring(0, fileName.length() - ".rar".length()) + ".";
        return new LegacyPathResolver(firstVolumePath.getParent(), prefix);
    }

    /// Returns the decimal number encoded in the given string range, or `NO_VOLUME_NUMBER`.
    private static int decimalNumber(String text, int start, int end) {
        if (start >= end) {
            return NO_VOLUME_NUMBER;
        }

        int number = 0;
        for (int index = start; index < end; index++) {
            int digit = Character.digit(text.charAt(index), 10);
            if (digit < 0 || number > (Integer.MAX_VALUE - digit) / 10) {
                return NO_VOLUME_NUMBER;
            }
            number = number * 10 + digit;
        }
        return number;
    }

    /// Returns a positive volume number padded to the requested minimum width.
    private static String paddedNumber(int volumeNumber, int minimumWidth) {
        String text = Integer.toString(volumeNumber);
        if (text.length() >= minimumWidth) {
            return text;
        }

        StringBuilder builder = new StringBuilder(minimumWidth);
        for (int index = text.length(); index < minimumWidth; index++) {
            builder.append('0');
        }
        return builder.append(text).toString();
    }

    /// Resolves paths from numeric continuation volume numbers.
    @NotNullByDefault
    private interface VolumePathResolver {
        /// Returns the first continuation volume number.
        int firstContinuationVolumeNumber();

        /// Returns the maximum supported volume number.
        int maximumVolumeNumber();

        /// Returns the path for a continuation volume number.
        Path path(int volumeNumber);
    }

    /// Resolves modern numbered `partN.rar` volume paths.
    @NotNullByDefault
    private static final class NumberedPathResolver implements VolumePathResolver {
        /// The parent directory for resolved volume paths, or `null` for relative file names.
        private final @Nullable Path parent;

        /// The file-name prefix before the numeric volume number.
        private final String prefix;

        /// The minimum numeric width.
        private final int width;

        /// The file-name suffix after the numeric volume number.
        private final String suffix;

        /// The first continuation volume number.
        private final int firstContinuationVolumeNumber;

        /// The maximum supported volume number.
        private final int maximumVolumeNumber;

        /// Creates a modern numbered volume resolver.
        private NumberedPathResolver(
                @Nullable Path parent,
                String prefix,
                int width,
                String suffix,
                int firstContinuationVolumeNumber,
                int maximumVolumeNumber
        ) {
            this.parent = parent;
            this.prefix = Objects.requireNonNull(prefix, "prefix");
            this.width = width;
            this.suffix = Objects.requireNonNull(suffix, "suffix");
            this.firstContinuationVolumeNumber = firstContinuationVolumeNumber;
            this.maximumVolumeNumber = maximumVolumeNumber;
        }

        /// Returns the first continuation volume number.
        @Override
        public int firstContinuationVolumeNumber() {
            return firstContinuationVolumeNumber;
        }

        /// Returns the maximum supported volume number.
        @Override
        public int maximumVolumeNumber() {
            return maximumVolumeNumber;
        }

        /// Returns the path for a continuation volume number.
        @Override
        public Path path(int volumeNumber) {
            Path fileName = Path.of(prefix + paddedNumber(volumeNumber, width) + suffix);
            return parent != null ? parent.resolve(fileName) : fileName;
        }
    }

    /// Resolves legacy `r00`, `r01`, ..., `z99` continuation volume paths.
    @NotNullByDefault
    private static final class LegacyPathResolver implements VolumePathResolver {
        /// The parent directory for resolved volume paths, or `null` for relative file names.
        private final @Nullable Path parent;

        /// The file-name prefix before the legacy continuation extension.
        private final String prefix;

        /// Creates a legacy volume resolver.
        private LegacyPathResolver(@Nullable Path parent, String prefix) {
            this.parent = parent;
            this.prefix = Objects.requireNonNull(prefix, "prefix");
        }

        /// Returns the first continuation volume number.
        @Override
        public int firstContinuationVolumeNumber() {
            return FIRST_LEGACY_CONTINUATION_VOLUME_NUMBER;
        }

        /// Returns the maximum supported volume number.
        @Override
        public int maximumVolumeNumber() {
            return MAX_LEGACY_CONTINUATION_VOLUME_NUMBER;
        }

        /// Returns the path for a continuation volume number.
        @Override
        public Path path(int volumeNumber) {
            char extensionPrefix = (char) ('r' + volumeNumber / LEGACY_VOLUMES_PER_EXTENSION_LETTER);
            int extensionNumber = volumeNumber % LEGACY_VOLUMES_PER_EXTENSION_LETTER;
            Path fileName = Path.of(prefix + extensionPrefix + paddedNumber(extensionNumber, 2));
            return parent != null ? parent.resolve(fileName) : fileName;
        }
    }
}
