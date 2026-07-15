// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.spi.FileSystemProvider;
import java.util.Set;
import java.util.stream.Collectors;

/// Verifies Arkivo file system provider discovery in an isolated JVM.
@NotNullByDefault
public final class FileSystemProviderDiscoveryProbe {
    /// Expected Arkivo provider schemes.
    private static final Set<String> EXPECTED_SCHEMES =
            Set.of("arkivo+7z", "arkivo+ar", "arkivo+rar", "arkivo+tar", "arkivo+zip");

    /// Prevents instantiation.
    private FileSystemProviderDiscoveryProbe() {
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
    }
}
