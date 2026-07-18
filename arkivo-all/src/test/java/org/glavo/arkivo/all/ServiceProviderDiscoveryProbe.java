// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArkivoFormat;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.CompressionFormats;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.spi.FileSystemProvider;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/// Verifies Arkivo service-provider discovery in an isolated JVM.
@NotNullByDefault
public final class ServiceProviderDiscoveryProbe {
    /// Expected Arkivo file system provider schemes.
    private static final @Unmodifiable Set<String> EXPECTED_SCHEMES =
            Set.of("arkivo+7z", "arkivo+ar", "arkivo+rar", "arkivo+tar", "arkivo+zip");

    /// Expected archive format names, including formats without an NIO file-system provider.
    private static final @Unmodifiable Set<String> EXPECTED_ARCHIVE_FORMAT_NAMES =
            Set.of("7z", "ar", "cpio", "rar", "tar", "zip");

    /// Expected compression format names.
    private static final @Unmodifiable Set<String> EXPECTED_FORMAT_NAMES = Set.of(
            "bzip2",
            "compress",
            "deflate",
            "deflate64",
            "gzip",
            "lz4",
            "lz4-block",
            "lzip",
            "lzma",
            "lzma-raw",
            "lzma2",
            "ppmd",
            "xz",
            "zlib",
            "zstd"
    );

    /// Prevents instantiation.
    private ServiceProviderDiscoveryProbe() {
    }

    /// Verifies the providers visible to the current runtime configuration.
    public static void main(String[] arguments) {
        Set<String> actualSchemes = FileSystemProvider.installedProviders()
                .stream()
                .map(FileSystemProvider::getScheme)
                .filter(scheme -> scheme.startsWith("arkivo+"))
                .collect(Collectors.toUnmodifiableSet());
        if (!EXPECTED_SCHEMES.equals(actualSchemes)) {
            throw new AssertionError(
                    "Installed Arkivo file system provider schemes " + actualSchemes
                            + " differ from " + EXPECTED_SCHEMES
            );
        }

        @Unmodifiable List<ArkivoFormat> archiveFormats = ArkivoFormats.installed();
        Set<String> actualArchiveFormatNames = archiveFormats.stream()
                .map(ArkivoFormat::name)
                .collect(Collectors.toUnmodifiableSet());
        if (!EXPECTED_ARCHIVE_FORMAT_NAMES.equals(actualArchiveFormatNames)) {
            throw new AssertionError(
                    "Installed Arkivo archive formats " + actualArchiveFormatNames
                            + " differ from " + EXPECTED_ARCHIVE_FORMAT_NAMES
            );
        }

        @Unmodifiable List<CompressionFormat> formats = CompressionFormats.installed();
        for (CompressionFormat format : formats) {
            if (format.defaultCodec().format() != format) {
                throw new AssertionError(
                        "Compression format " + format.name() + " is not its default codec identity"
                );
            }
        }
        Set<String> actualFormatNames = formats.stream()
                .map(CompressionFormat::name)
                .collect(Collectors.toUnmodifiableSet());
        if (!EXPECTED_FORMAT_NAMES.equals(actualFormatNames)) {
            throw new AssertionError(
                    "Installed Arkivo compression formats " + actualFormatNames
                            + " differ from " + EXPECTED_FORMAT_NAMES
            );
        }
    }
}
