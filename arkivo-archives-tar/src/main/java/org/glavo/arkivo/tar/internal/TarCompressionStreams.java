// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.tar.internal;

import org.glavo.arkivo.compress.CompressionCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/// Opens compression wrappers around TAR streams and validates codec capabilities.
@NotNullByDefault
public final class TarCompressionStreams {
    /// Creates no instances.
    private TarCompressionStreams() {
    }

    /// Opens a decoded TAR input stream and closes the source if decoder setup fails.
    public static InputStream openArchiveInput(
            InputStream source,
            @Nullable CompressionCodec compressionCodec
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        if (compressionCodec == null) {
            return source;
        }
        try {
            requireDecompression(compressionCodec);
            return compressionCodec.decompressFrom(source);
        } catch (IOException | RuntimeException | Error exception) {
            closeAfterOpenFailure(source, exception);
            throw exception;
        }
    }

    /// Opens an encoded TAR output stream and closes the target if encoder setup fails.
    public static OutputStream openArchiveOutput(
            OutputStream target,
            @Nullable CompressionCodec compressionCodec
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        if (compressionCodec == null) {
            return target;
        }
        try {
            requireCompression(compressionCodec);
            return compressionCodec.compressTo(target);
        } catch (IOException | RuntimeException | Error exception) {
            closeAfterOpenFailure(target, exception);
            throw exception;
        }
    }

    /// Requires the selected codec to support compression.
    public static void requireCompression(@Nullable CompressionCodec compressionCodec) {
        if (compressionCodec != null && !compressionCodec.canCompress()) {
            throw new UnsupportedOperationException(
                    "Compression codec does not support compression: " + compressionCodec.name()
            );
        }
    }

    /// Requires the selected codec to support decompression.
    public static void requireDecompression(@Nullable CompressionCodec compressionCodec) {
        if (compressionCodec != null && !compressionCodec.canDecompress()) {
            throw new UnsupportedOperationException(
                    "Compression codec does not support decompression: " + compressionCodec.name()
            );
        }
    }

    /// Closes a stream after wrapper setup fails without hiding the primary failure.
    private static void closeAfterOpenFailure(Closeable closeable, Throwable failure) {
        try {
            closeable.close();
        } catch (IOException | RuntimeException | Error exception) {
            failure.addSuppressed(exception);
        }
    }
}
