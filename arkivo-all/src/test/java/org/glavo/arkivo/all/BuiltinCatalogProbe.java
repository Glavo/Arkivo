// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArkivoFormat;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.CompressionFormats;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.spi.FileSystemProvider;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/// Verifies Arkivo's closed built-in catalogs and required JDK file-system providers in an isolated JVM.
@NotNullByDefault
public final class BuiltinCatalogProbe {
    /// Expected Arkivo file system provider schemes.
    private static final @Unmodifiable Set<String> EXPECTED_SCHEMES =
            Set.of("arkivo+7z", "arkivo+ar", "arkivo+rar", "arkivo+tar", "arkivo+zip");

    /// Expected archive format names, including formats without an NIO file-system provider.
    private static final @Unmodifiable List<String> EXPECTED_ARCHIVE_FORMAT_NAMES =
            List.of("7z", "ar", "cpio", "rar", "tar", "zip");

    /// Expected compression format names in deterministic detection order.
    private static final @Unmodifiable List<String> EXPECTED_FORMAT_NAMES = List.of(
            "bzip2",
            "compress",
            "deflate",
            "deflate64",
            "gzip",
            "zlib",
            "lz4",
            "lz4-block",
            "lzip",
            "lzma",
            "lzma-raw",
            "lzma2",
            "ppmd",
            "xz",
            "zstd"
    );

    /// Prevents instantiation.
    private BuiltinCatalogProbe() {
    }

    /// Verifies the official formats and NIO providers visible to the current runtime configuration.
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
        @Unmodifiable List<String> actualArchiveFormatNames = archiveFormats.stream()
                .map(ArkivoFormat::name)
                .toList();
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
        @Unmodifiable List<String> actualFormatNames = formats.stream()
                .map(CompressionFormat::name)
                .toList();
        if (!EXPECTED_FORMAT_NAMES.equals(actualFormatNames)) {
            throw new AssertionError(
                    "Installed Arkivo compression formats " + actualFormatNames
                            + " differ from " + EXPECTED_FORMAT_NAMES
            );
        }

        verifyCompressionBridge();
    }

    /// Verifies archive core can invoke the known optional compression bridge in this runtime configuration.
    private static void verifyCompressionBridge() {
        try (var ignored = ArkivoFormats.openStreamingReader(
                new ByteArrayInputStream(new byte[]{0x1f, (byte) 0x8b, 0x08, 0x00})
        )) {
            throw new AssertionError("Truncated gzip input was unexpectedly accepted as an archive");
        } catch (IOException exception) {
            if ("Unrecognized archive format".equals(exception.getMessage())) {
                throw new AssertionError("The official compression bridge was not invoked", exception);
            }
        }
    }
}
