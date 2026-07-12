// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

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

/// Discovers compression codecs provided by Arkivo codec modules.
@NotNullByDefault
public final class CompressionCodecs {
    /// Creates no instances.
    private CompressionCodecs() {
    }

    /// Returns all compression codecs visible to the current class loader.
    public static @Unmodifiable List<CompressionCodec> installed() {
        ArrayList<CompressionCodec> codecs = new ArrayList<>();
        for (CompressionCodec codec : ServiceLoader.load(CompressionCodec.class)) {
            codecs.add(codec);
        }
        return List.copyOf(codecs);
    }

    /// Returns the first compression codec with the given stable name or alias, ignoring ASCII case.
    public static @Nullable CompressionCodec find(String name) {
        Objects.requireNonNull(name, "name");
        for (CompressionCodec codec : ServiceLoader.load(CompressionCodec.class)) {
            if (codec.name().equalsIgnoreCase(name)) {
                return codec;
            }
            for (String alias : codec.aliases()) {
                if (alias.equalsIgnoreCase(name)) {
                    return codec;
                }
            }
        }
        return null;
    }

    /// Returns the first installed codec matching the remaining prefix bytes.
    ///
    /// The supplied buffer is not modified.
    public static @Nullable CompressionCodec detect(ByteBuffer prefix) {
        Objects.requireNonNull(prefix, "prefix");
        return detect(prefix, installed());
    }

    /// Detects an installed codec from bytes at the channel's current position.
    ///
    /// The channel position is restored before this method returns or throws.
    public static @Nullable CompressionCodec detect(SeekableByteChannel channel) throws IOException {
        Objects.requireNonNull(channel, "channel");
        @Unmodifiable List<CompressionCodec> codecs = installed();
        int probeSize = maxProbeSize(codecs);
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
                    throw new IOException("Compression codec probe made no progress");
                }
            }
            prefix.flip();
            return detect(prefix, codecs);
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            restorePosition(channel, originalPosition, failure);
        }
    }

    /// Detects an installed codec from the beginning of the given path.
    public static @Nullable CompressionCodec detect(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
            return detect(channel);
        }
    }

    /// Returns the first codec matching a protected view of the prefix.
    private static @Nullable CompressionCodec detect(
            ByteBuffer prefix,
            @Unmodifiable List<CompressionCodec> codecs
    ) {
        for (CompressionCodec codec : codecs) {
            if (codec.matches(prefix.asReadOnlyBuffer())) {
                return codec;
            }
        }
        return null;
    }

    /// Returns the largest preferred probe size declared by the installed codecs.
    private static int maxProbeSize(@Unmodifiable List<CompressionCodec> codecs) {
        int maximum = 0;
        for (CompressionCodec codec : codecs) {
            int size = codec.probeSize();
            if (size < 0) {
                throw new IllegalStateException("Compression codec probe size must not be negative: " + codec.name());
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
