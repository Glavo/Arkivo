// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/// Verifies 7z reports precise capability failures when optional compression formats are absent.
@NotNullByDefault
public final class SevenZipOptionalFormatProbe {
    /// The compression formats excluded from the runtime class path used by this probe.
    private static final String @Unmodifiable [] OPTIONAL_FORMAT_NAMES = {
            "bzip2",
            "deflate",
            "deflate64",
            "zstd"
    };

    /// Creates no instances.
    private SevenZipOptionalFormatProbe() {
    }

    /// Checks both coder directions without optional compression formats on the runtime class path.
    public static void main(String @Unmodifiable [] arguments) throws Exception {
        for (String formatName : OPTIONAL_FORMAT_NAMES) {
            requireMissingFormat(formatName, () -> SevenZipCompressionFormats.newInputStream(
                    formatName,
                    new ByteArrayInputStream(new byte[0]),
                    0L,
                    ArchiveReadLimits.UNLIMITED
            ));
            requireMissingFormat(formatName, () -> SevenZipCompressionFormats.newOutputStream(
                    formatName,
                    1,
                    new ByteArrayOutputStream()
            ));
        }
        requireMissingFormat("ppmd", () -> SevenZipCompressionFormats.openPpmdDecoder(
                new ByteArrayInputStream(new byte[0]),
                4,
                1L << 20,
                0L,
                ArchiveReadLimits.UNLIMITED
        ));
        requireMissingFormat("ppmd", () -> SevenZipCompressionFormats.openPpmdEncoder(
                4,
                1L << 20,
                new ByteArrayOutputStream()
        ));
    }

    /// Requires one default-codec operation to fail with the stable missing-format diagnostic.
    private static void requireMissingFormat(String formatName, CheckedOperation operation) throws Exception {
        try {
            operation.run();
        } catch (IOException exception) {
            if (("Unknown compression format: " + formatName).equals(exception.getMessage())) {
                return;
            }
            throw new AssertionError("Unexpected missing-format diagnostic for " + formatName, exception);
        }
        throw new AssertionError("7z unexpectedly found the optional " + formatName + " compression format");
    }

    /// Runs one operation that may open a default codec context.
    @FunctionalInterface
    @NotNullByDefault
    private interface CheckedOperation {
        /// Runs the operation.
        void run() throws Exception;
    }
}
