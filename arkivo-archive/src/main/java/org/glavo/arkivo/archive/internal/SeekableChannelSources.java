// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.util.Objects;

/// Applies archive open operations to an owned single-channel source.
@NotNullByDefault
public final class SeekableChannelSources {
    /// Creates no instances.
    private SeekableChannelSources() {
    }

    /// Adapts and transfers ownership of one channel to an archive open operation.
    ///
    /// @param <T> the opened archive resource type
    /// @param channel the seekable channel whose ownership is transferred after argument validation
    /// @param opener the operation that assumes ownership of the repeatable source on success
    /// @return the opened archive resource
    /// @throws IOException if source adaptation or the archive open operation fails
    public static <T> T open(SeekableByteChannel channel, Opener<T> opener) throws IOException {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(opener, "opener");
        ArkivoSeekableChannelSource source = ArkivoSeekableChannelSource.of(channel);
        try {
            return Objects.requireNonNull(opener.open(source), "opened archive");
        } catch (IOException | RuntimeException | Error exception) {
            try {
                source.close();
            } catch (IOException | RuntimeException | Error closeException) {
                if (exception != closeException) {
                    exception.addSuppressed(closeException);
                }
            }
            throw exception;
        }
    }

    /// Opens an archive from a repeatable logical source.
    ///
    /// @param <T> the opened archive resource type
    @FunctionalInterface
    @NotNullByDefault
    public interface Opener<T> {
        /// Performs one archive open operation that assumes ownership of the source on success.
        ///
        /// @param source the repeatable source whose ownership transfers on success
        /// @return the opened archive resource
        /// @throws IOException if the archive cannot be opened
        T open(ArkivoSeekableChannelSource source) throws IOException;
    }
}
