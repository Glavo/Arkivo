// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;

/// Discovers archive formats provided by Arkivo format modules.
@NotNullByDefault
public final class ArkivoFormats {
    /// Creates no instances.
    private ArkivoFormats() {
    }

    /// Returns all archive formats visible to the current class loader.
    public static @Unmodifiable List<ArkivoFormat> installed() {
        ArrayList<ArkivoFormat> formats = new ArrayList<>();
        for (ArkivoFormat format : ServiceLoader.load(ArkivoFormat.class)) {
            formats.add(format);
        }
        return List.copyOf(formats);
    }

    /// Returns the first archive format with the given stable name or alias, ignoring ASCII case.
    public static @Nullable ArkivoFormat find(String name) {
        Objects.requireNonNull(name, "name");
        for (ArkivoFormat format : ServiceLoader.load(ArkivoFormat.class)) {
            if (format.name().equalsIgnoreCase(name)) {
                return format;
            }
            for (String alias : format.aliases()) {
                if (alias.equalsIgnoreCase(name)) {
                    return format;
                }
            }
        }
        return null;
    }

    /// Returns the first installed archive format matching the remaining prefix bytes.
    ///
    /// The supplied buffer is not modified.
    public static @Nullable ArkivoFormat detect(ByteBuffer prefix) {
        Objects.requireNonNull(prefix, "prefix");
        return detect(prefix, installed());
    }

    /// Detects an installed archive format from bytes at the channel's current position.
    ///
    /// The channel position is restored before this method returns or throws.
    public static @Nullable ArkivoFormat detect(SeekableByteChannel channel) throws IOException {
        Objects.requireNonNull(channel, "channel");
        @Unmodifiable List<ArkivoFormat> formats = installed();
        int probeSize = maxProbeSize(formats);
        long originalPosition = channel.position();
        @Nullable Throwable failure = null;
        try {
            ByteBuffer prefix = ByteBuffer.allocate(probeSize);
            while (prefix.hasRemaining()) {
                int read = channel.read(prefix);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    throw new IOException("Archive format probe made no progress");
                }
            }
            prefix.flip();
            return detect(prefix, formats);
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            restorePosition(channel, originalPosition, failure);
        }
    }

    /// Detects an installed archive format from the beginning of the given path.
    public static @Nullable ArkivoFormat detect(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
            return detect(channel);
        }
    }

    /// Returns the first format matching a protected view of the prefix.
    private static @Nullable ArkivoFormat detect(
            ByteBuffer prefix,
            @Unmodifiable List<ArkivoFormat> formats
    ) {
        for (ArkivoFormat format : formats) {
            if (format.matches(prefix.asReadOnlyBuffer())) {
                return format;
            }
        }
        return null;
    }

    /// Returns the largest preferred probe size declared by the installed formats.
    private static int maxProbeSize(@Unmodifiable List<ArkivoFormat> formats) {
        int maximum = 0;
        for (ArkivoFormat format : formats) {
            int size = format.probeSize();
            if (size < 0) {
                throw new IllegalStateException("Archive format probe size must not be negative: " + format.name());
            }
            maximum = Math.max(maximum, size);
        }
        return maximum;
    }

    /// Restores a probed channel position without hiding an earlier probe failure.
    private static void restorePosition(
            SeekableByteChannel channel,
            long position,
            @Nullable Throwable failure
    ) throws IOException {
        try {
            channel.position(position);
        } catch (IOException | RuntimeException | Error exception) {
            if (failure != null) {
                failure.addSuppressed(exception);
                return;
            }
            throw exception;
        }
    }
}
