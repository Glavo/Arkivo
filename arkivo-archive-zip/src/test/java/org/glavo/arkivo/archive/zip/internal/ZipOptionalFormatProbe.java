// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/// Verifies ZIP reports precise capability failures when optional compression formats are absent.
@NotNullByDefault
public final class ZipOptionalFormatProbe {
    /// The compression formats excluded from the runtime class path used by this probe.
    private static final String @Unmodifiable [] OPTIONAL_FORMAT_NAMES = {
            "bzip2",
            "xz",
            "zstd"
    };

    /// Creates no instances.
    private ZipOptionalFormatProbe() {
    }

    /// Checks both entry directions without optional compression formats on the runtime class path.
    public static void main(String @Unmodifiable [] arguments) throws Exception {
        requireDeflateFamilyFormats();
        for (String formatName : OPTIONAL_FORMAT_NAMES) {
            requireMissingFormat(formatName, () -> ZipCompressionFormats.openDecoder(
                    formatName,
                    new ByteArrayInputStream(new byte[0])
            ));
            requireMissingFormat(formatName, () -> ZipCompressionFormats.openEncoder(
                    formatName,
                    new ByteArrayOutputStream()
            ));
        }
        requireMissingFormat("lzma-raw", () -> ZipCompressionFormats.openRawLZMADecoder(
                new ByteArrayInputStream(new byte[0]),
                0x5d,
                1L << 20,
                0L
        ));
        requireMissingFormat("lzma-raw", () -> ZipCompressionFormats.openRawLZMAEncoder(
                1L << 20,
                true,
                new ByteArrayOutputStream()
        ));
    }

    /// Requires every format bundled in the mandatory Deflate-family module.
    private static void requireDeflateFamilyFormats() throws IOException {
        requireBundledFormat("deflate");
        requireBundledFormat("deflate64");
    }

    /// Requires the default codec for one bundled format to open both directions.
    private static void requireBundledFormat(String formatName) throws IOException {
        try (DecompressingReadableByteChannel ignoredDecoder = ZipCompressionFormats.openDecoder(
                formatName,
                new ByteArrayInputStream(new byte[0])
        ); CompressingWritableByteChannel ignoredEncoder = ZipCompressionFormats.openEncoder(
                formatName,
                new ByteArrayOutputStream()
        )) {
            // Opening both directions proves the default codec remains in the stripped runtime image.
        }
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
        throw new AssertionError("ZIP unexpectedly found the optional " + formatName + " compression format");
    }

    /// Runs one operation that may open a default codec context.
    @FunctionalInterface
    @NotNullByDefault
    private interface CheckedOperation {
        /// Runs the operation.
        void run() throws Exception;
    }
}
