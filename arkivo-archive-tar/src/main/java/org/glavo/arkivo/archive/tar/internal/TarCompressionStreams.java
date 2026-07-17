// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar.internal;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.DecompressionLimits;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/// Opens optional compression wrappers around TAR streams.
@NotNullByDefault
public final class TarCompressionStreams {
    /// Creates no instances.
    private TarCompressionStreams() {
    }

    /// Opens a decoded TAR input stream and closes the source if decoder setup fails.
    public static InputStream openArchiveInput(
            InputStream source,
            @Nullable CompressionCodec<?> compressionCodec,
            ArchiveReadLimits readLimits
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(readLimits, "readLimits");
        if (compressionCodec == null) {
            return source;
        }
        try {
            return compressionCodec.newInputStream(
                    source,
                    decompressionLimits(readLimits),
                    ResourceOwnership.OWNED
            );
        } catch (IOException | RuntimeException | Error exception) {
            closeAfterOpenFailure(source, exception);
            throw exception;
        }
    }

    /// Opens a decoded TAR channel and closes the source if decoder setup fails.
    public static ReadableByteChannel openArchiveInput(
            ReadableByteChannel source,
            @Nullable CompressionCodec<?> compressionCodec,
            ArchiveReadLimits readLimits
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(readLimits, "readLimits");
        if (compressionCodec == null) {
            return source;
        }
        try {
            return compressionCodec.newReadableByteChannel(
                    source,
                    decompressionLimits(readLimits),
                    ResourceOwnership.OWNED
            );
        } catch (IOException | RuntimeException | Error exception) {
            closeAfterOpenFailure(source, exception);
            throw exception;
        }
    }

    /// Opens an encoded TAR output stream and closes the target if encoder setup fails.
    public static OutputStream openArchiveOutput(
            OutputStream target,
            @Nullable CompressionCodec<?> compressionCodec
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        if (compressionCodec == null) {
            return target;
        }
        try {
            return compressionCodec.newOutputStream(target, ResourceOwnership.OWNED);
        } catch (IOException | RuntimeException | Error exception) {
            closeAfterOpenFailure(target, exception);
            throw exception;
        }
    }

    /// Opens an encoded TAR channel and closes the target if encoder setup fails.
    public static WritableByteChannel openArchiveOutput(
            WritableByteChannel target,
            @Nullable CompressionCodec<?> compressionCodec
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        if (compressionCodec == null) {
            return target;
        }
        try {
            return compressionCodec.newWritableByteChannel(target, ResourceOwnership.OWNED);
        } catch (IOException | RuntimeException | Error exception) {
            closeAfterOpenFailure(target, exception);
            throw exception;
        }
    }

    /// Converts archive resource limits to the limits understood by compression codecs.
    private static DecompressionLimits decompressionLimits(ArchiveReadLimits readLimits) {
        return new DecompressionLimits(
                DecompressionLimits.UNLIMITED_SIZE,
                readLimits.maximumCompressionWindowSize(),
                readLimits.maximumDecoderMemorySize()
        );
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
