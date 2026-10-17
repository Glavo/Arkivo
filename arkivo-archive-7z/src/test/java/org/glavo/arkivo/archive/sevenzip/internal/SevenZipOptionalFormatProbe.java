// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/// Verifies core 7z codecs remain usable and optional codecs report precise failures when absent.
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
        requireCoreCodecs();
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

    /// Round-trips raw LZMA and LZMA2 without any optional codecs on the class path.
    private static void requireCoreCodecs() throws IOException {
        byte[] content = "7z core codec round trip ".repeat(32).getBytes(StandardCharsets.UTF_8);
        int dictionarySize = 1 << 16;
        for (boolean endMarker : new boolean[]{false, true}) {
            var encoded = new ByteArrayOutputStream();
            try (OutputStream output = SevenZipCompressionFormats.openRawLZMAEncoder(
                    dictionarySize, endMarker, encoded)) {
                output.write(content);
            }
            try (InputStream input = SevenZipCompressionFormats.openRawLZMADecoder(
                    new ByteArrayInputStream(encoded.toByteArray()), 0x5d, dictionarySize,
                    content.length, ArchiveReadLimits.UNLIMITED)) {
                if (!Arrays.equals(content, input.readAllBytes())) {
                    throw new AssertionError("Raw LZMA round trip failed without optional codecs");
                }
            }
        }
        var encoded = new ByteArrayOutputStream();
        try (OutputStream output = SevenZipCompressionFormats.openLZMA2Encoder(dictionarySize, encoded)) {
            output.write(content);
        }
        try (InputStream input = SevenZipCompressionFormats.openLZMA2Decoder(
                new ByteArrayInputStream(encoded.toByteArray()), dictionarySize,
                content.length, ArchiveReadLimits.UNLIMITED)) {
            if (!Arrays.equals(content, input.readAllBytes())) {
                throw new AssertionError("LZMA2 round trip failed without optional codecs");
            }
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
