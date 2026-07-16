// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all.internal;

import org.glavo.arkivo.archive.spi.ArkivoStreamingSource;
import org.glavo.arkivo.archive.spi.ArkivoStreamingSourceProvider;
import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionCodecs;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.CompressionProbeResult;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.util.Map;
import java.util.Objects;

/// Transparently decodes installed compression formats before archive detection.
@NotNullByDefault
public final class CompressionStreamingSourceProvider implements ArkivoStreamingSourceProvider {
    /// Creates a compression source provider for service loading.
    public CompressionStreamingSourceProvider() {
    }

    /// Probes the source and opens an owning decoder when an installed codec matches.
    @Override
    public ArkivoStreamingSource probe(
            ReadableByteChannel source,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(environment, "environment");
        CompressionProbeResult probe = CompressionCodecs.probe(source, ChannelOwnership.CLOSE);
        @Nullable CompressionCodec codec = probe.codec();
        if (codec == null) {
            return new ArkivoStreamingSource(false, probe.channel());
        }
        try {
            DecompressingReadableByteChannel decoder = codec.openDecoder(
                    probe.channel(),
                    ChannelOwnership.CLOSE
            );
            return new ArkivoStreamingSource(true, decoder);
        } catch (IOException | RuntimeException | Error failure) {
            closeAfterFailure(probe.channel(), failure);
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
