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
    ///
    /// The returned list begins with `firstVolumePath` and contains each consecutively existing continuation volume.
    /// Discovery recognizes `name.part1.rar`, `name.rar` followed by `name.part2.rar`, and legacy `name.r00` naming.
    /// It does not open the discovered files or validate their RAR headers.
    ///
    /// @param firstVolumePath the candidate first-volume path
    /// @return the immutable ordered volume paths, or `null` if no conventional continuation volume exists
    /// @throws IOException if the conventional sequence exceeds the supported volume-number range
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
        return new NumberedPathResolver(
                firstVolumePath,
                prefix,
                width,
                suffix
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
        return new NumberedPathResolver(
                firstVolumePath,
                prefix,
                1,
                suffix
        );
    }

    /// Returns a resolver for `name.rar`, `name.r00` style volume names.
    private static @Nullable LegacyPathResolver legacyRarResolver(Path firstVolumePath, String fileName) {
        String lowerFileName = fileName.toLowerCase(Locale.ROOT);
        if (!lowerFileName.endsWith(".rar")) {
            return null;
        }

        String prefix = fileName.substring(0, fileName.length() - ".rar".length()) + ".";
        return new LegacyPathResolver(firstVolumePath, prefix);
    }

    /// Returns the ASCII decimal number encoded in the given string range, or `NO_VOLUME_NUMBER`.
    private static int decimalNumber(String text, int start, int end) {
        if (start >= end) {
            return NO_VOLUME_NUMBER;
        }

        int number = 0;
        for (int index = start; index < end; index++) {
            char character = text.charAt(index);
            if (character < '0' || character > '9') {
                return NO_VOLUME_NUMBER;
            }
            int digit = character - '0';
            if (number > (Integer.MAX_VALUE - digit) / 10) {
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
    ///
    /// @param firstVolumePath the first volume whose sibling paths retain its file-system provider
    /// @param prefix the file-name prefix before the numeric volume number
    /// @param width the minimum numeric width
    /// @param suffix the file-name suffix after the numeric volume number
    @NotNullByDefault
    private record NumberedPathResolver(
            Path firstVolumePath,
            String prefix,
            int width,
            String suffix
    ) implements VolumePathResolver {
        /// Creates a modern numbered volume resolver.
        private NumberedPathResolver {
            Objects.requireNonNull(prefix, "prefix");
            Objects.requireNonNull(suffix, "suffix");
        }

        /// Returns the first continuation volume number.
        @Override
        public int firstContinuationVolumeNumber() {
            return FIRST_MODERN_CONTINUATION_VOLUME_NUMBER;
        }

        /// Returns the maximum supported volume number.
        @Override
        public int maximumVolumeNumber() {
            return Integer.MAX_VALUE;
        }

        /// Returns the path for a continuation volume number.
        @Override
        public Path path(int volumeNumber) {
            return firstVolumePath.resolveSibling(prefix + paddedNumber(volumeNumber, width) + suffix);
        }
    }

    /// Resolves legacy `r00`, `r01`, ..., `z99` continuation volume paths.
    ///
    /// @param firstVolumePath the first volume whose sibling paths retain its file-system provider
    /// @param prefix the file-name prefix before the legacy continuation extension
    @NotNullByDefault
    private record LegacyPathResolver(Path firstVolumePath, String prefix) implements VolumePathResolver {
        /// Creates a legacy volume resolver.
        private LegacyPathResolver {
            Objects.requireNonNull(prefix, "prefix");
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
            return firstVolumePath.resolveSibling(prefix + extensionPrefix + paddedNumber(extensionNumber, 2));
        }
    }
}
