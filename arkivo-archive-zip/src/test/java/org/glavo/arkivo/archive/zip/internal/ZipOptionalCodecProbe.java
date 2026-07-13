// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

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
            "deflate64",
            "xz",
            "zstd"
    };

    /// Creates no instances.
    private ZipOptionalCodecProbe() {
    }

    /// Checks both entry directions without optional codec implementations on the runtime class path.
    public static void main(String @Unmodifiable [] arguments) throws Exception {
        for (String codecName : OPTIONAL_CODEC_NAMES) {
            requireMissingCodec(codecName, () -> ZipCompressionCodecs.openDecoder(
                    codecName,
                    new ByteArrayInputStream(new byte[0])
            ));
            requireMissingCodec(codecName, () -> ZipCompressionCodecs.openEncoder(
                    codecName,
                    new ByteArrayOutputStream()
            ));
        }
    }

    /// Requires one optional-codec operation to fail with the stable missing-codec diagnostic.
    private static void requireMissingCodec(String codecName, CheckedOperation operation) throws Exception {
        try {
            operation.run();
        } catch (IOException exception) {
            if (("Unknown compression codec: " + codecName).equals(exception.getMessage())) {
                return;
            }
            throw new AssertionError("Unexpected missing-codec diagnostic for " + codecName, exception);
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