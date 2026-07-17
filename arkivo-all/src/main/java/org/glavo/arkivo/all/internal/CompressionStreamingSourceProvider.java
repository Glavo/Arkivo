// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all.internal;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.spi.ArkivoStreamingSource;
import org.glavo.arkivo.archive.spi.ArkivoStreamingSourceProvider;
import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.CompressionFormats;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.DecompressionLimits;
import org.glavo.arkivo.codec.CompressionProbeResult;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Transparently decodes installed compression formats before archive detection.
@NotNullByDefault
public final class CompressionStreamingSourceProvider implements ArkivoStreamingSourceProvider {
    /// Creates a compression source provider for service loading.
    public CompressionStreamingSourceProvider() {
    }

    /// Probes the source and opens an owning decoder when an installed format matches.
    @Override
    public ArkivoStreamingSource probe(
            ReadableByteChannel source,
            ArchiveReadOptions options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        CompressionProbeResult probe = CompressionFormats.probe(source, ResourceOwnership.OWNED);
        @Nullable CompressionFormat format = probe.format();
        ReadableByteChannel replay = probe.takeChannel();
        if (format == null) {
            return new ArkivoStreamingSource(false, replay);
        }
        try {
            ArchiveReadLimits readLimits = options.limits();
            DecompressingReadableByteChannel decoder = format.defaultCodec().newReadableByteChannel(
                    replay,
                    new DecompressionLimits(
                            DecompressionLimits.UNLIMITED_SIZE,
                            readLimits.maximumCompressionWindowSize(),
                            readLimits.maximumDecoderMemorySize()
                    ),
                    ResourceOwnership.OWNED
            );
            return new ArkivoStreamingSource(true, decoder);
        } catch (IOException | RuntimeException | Error failure) {
            closeAfterFailure(replay, failure);
            throw failure;
        }
    }

    /// Closes a consumed source without hiding the primary setup failure.
    private static void closeAfterFailure(ReadableByteChannel source, Throwable failure) {
        try {
            source.close();
        } catch (IOException | RuntimeException | Error closeFailure) {
            if (failure != closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }
}
