// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/// Verifies ZIP reports precise capability failures when optional compression codecs are absent.
@NotNullByDefault
public final class ZipOptionalCodecProbe {
    /// The codec providers excluded from this probe's runtime class path.
    private static final String @Unmodifiable [] OPTIONAL_CODEC_NAMES = {
            "bzip2",
            "xz",
            "zstd"
    };

    /// Creates no instances.
    private ZipOptionalCodecProbe() {
    }

    /// Checks both entry directions without optional codec implementations on the runtime class path.
    public static void main(String @Unmodifiable [] arguments) throws Exception {
        requireDeflateFamilyCodecs();
        for (String codecName : OPTIONAL_CODEC_NAMES) {
            requireMissingCodec(codecName, () -> ZipCompressionFormats.openDecoder(
                    codecName,
                    new ByteArrayInputStream(new byte[0])
            ));
            requireMissingCodec(codecName, () -> ZipCompressionFormats.openEncoder(
                    codecName,
                    new ByteArrayOutputStream()
            ));
        }
        requireMissingCodec("lzma-raw", () -> ZipCompressionFormats.openRawLZMADecoder(
                new ByteArrayInputStream(new byte[0]),
                0x5d,
                1L << 20,
                0L
        ));
        requireMissingCodec("lzma-raw", () -> ZipCompressionFormats.openRawLZMAEncoder(
                1L << 20,
                true,
                new ByteArrayOutputStream()
        ));
    }

    /// Requires every codec bundled in the mandatory Deflate-family module.
    private static void requireDeflateFamilyCodecs() throws IOException {
        requireBundledCodec("deflate");
        requireBundledCodec("deflate64");
    }

    /// Requires one bundled codec to open both directions.
    private static void requireBundledCodec(String codecName) throws IOException {
        try (DecompressingReadableByteChannel ignoredDecoder = ZipCompressionFormats.openDecoder(
                codecName,
                new ByteArrayInputStream(new byte[0])
        ); CompressingWritableByteChannel ignoredEncoder = ZipCompressionFormats.openEncoder(
                codecName,
                new ByteArrayOutputStream()
        )) {
            // Opening both directions proves the provider remains in the stripped runtime image.
        }
    }

    /// Requires one optional-codec operation to fail with the stable missing-format diagnostic.
    private static void requireMissingCodec(String codecName, CheckedOperation operation) throws Exception {
        try {
            operation.run();
        } catch (IOException exception) {
            if (("Unknown compression format: " + codecName).equals(exception.getMessage())) {
                return;
            }
            throw new AssertionError("Unexpected missing-format diagnostic for " + codecName, exception);
        }
        throw new AssertionError("ZIP unexpectedly found the optional " + codecName + " codec");
    }

    /// Runs one operation that may open a codec context.
    @FunctionalInterface
    @NotNullByDefault
    private interface CheckedOperation {
        /// Runs the operation.
        void run() throws Exception;
    }
}
