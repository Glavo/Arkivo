// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionCodecs;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.spi.FileSystemProvider;
import java.util.Set;
import java.util.stream.Collectors;

/// Verifies Arkivo service-provider discovery in an isolated JVM.
@NotNullByDefault
public final class ServiceProviderDiscoveryProbe {
    /// Expected Arkivo file system provider schemes.
    private static final @Unmodifiable Set<String> EXPECTED_SCHEMES =
            Set.of("arkivo+7z", "arkivo+ar", "arkivo+rar", "arkivo+tar", "arkivo+zip");

    /// Expected default compression codec names.
    private static final @Unmodifiable Set<String> EXPECTED_CODEC_NAMES = Set.of(
            "bzip2",
            "deflate",
            "deflate64",
            "gzip",
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

        Set<String> actualCodecNames = CompressionCodecs.installed()
                .stream()
                .map(CompressionCodec::name)
                .collect(Collectors.toUnmodifiableSet());
        if (!EXPECTED_CODEC_NAMES.equals(actualCodecNames)) {
            throw new AssertionError(
                    "Installed Arkivo compression codecs " + actualCodecNames
                            + " differ from " + EXPECTED_CODEC_NAMES
            );
        }
    }
}