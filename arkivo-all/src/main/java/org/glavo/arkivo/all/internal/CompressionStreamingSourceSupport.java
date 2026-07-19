// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all.internal;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.internal.ArkivoStreamingSource;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.CompressionFormats;
import org.glavo.arkivo.codec.CompressionProbeResult;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.ResourceOwnership;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Transparently decodes an installed official compression format before archive detection.
@NotNullByDefault
@SuppressWarnings("unused")
public final class CompressionStreamingSourceSupport {
    /// Creates no instances.
    private CompressionStreamingSourceSupport() {
    }

    /// Probes the source and opens an owning decoder when an official compression format matches.
    ///
    /// @param source  the channel whose ownership is transferred after argument validation
    /// @param options the archive-wide read limits and lifecycle options
    /// @return an owning result containing the replaying or decoded logical source
    /// @throws IOException if compression probing or decoder setup fails
    public static ArkivoStreamingSource probe(
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
            DecompressingReadableByteChannel decoder = format.defaultCodec()
                    .withMaximumWindowSize(readLimits.maximumCompressionWindowSize())
                    .withMaximumMemorySize(readLimits.maximumDecoderMemorySize())
                    .newReadableByteChannel(replay, ResourceOwnership.OWNED);
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
