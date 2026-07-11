// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    /// Returns the matching installed archive format with the largest requested probe size.
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

    /// Detects and opens an archive from a forward-only input stream.
    ///
    /// After argument validation, this method takes ownership of the source. The returned reader receives every source
    /// byte, including the bytes consumed for detection. The source is closed if detection or reader setup fails.
    public static ArkivoStreamingReader openStreamingReader(InputStream source) throws IOException {
        return openStreamingReader(source, Map.of());
    }

    /// Detects and opens an archive from a forward-only input stream with environment options.
    ///
    /// After argument validation, this method takes ownership of the source. The returned reader receives every source
    /// byte, including the bytes consumed for detection. The source is closed if detection or reader setup fails.
    public static ArkivoStreamingReader openStreamingReader(
            InputStream source,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(environment, "environment");
        @Nullable Throwable failure = null;
        try {
            @Unmodifiable List<ArkivoFormat> formats = installed();
            byte[] prefix = new byte[maxProbeSize(formats)];
            int prefixSize = 0;
            while (prefixSize < prefix.length) {
                int count = source.read(prefix, prefixSize, prefix.length - prefixSize);
                if (count < 0) {
                    break;
                }
                if (count == 0) {
                    throw new IOException("Archive stream probe made no progress");
                }
                prefixSize += count;
            }
            @Nullable ArkivoFormat format = detect(ByteBuffer.wrap(prefix, 0, prefixSize), formats);
            if (format == null) {
                throw new IOException("Unrecognized archive format");
            }
            if (!(format instanceof ArkivoStreamingFormat streamingFormat)) {
                throw new UnsupportedOperationException(
                        "Archive format does not support forward-only reading: " + format.name()
                );
            }

            InputStream replay = new SequenceInputStream(
                    new ByteArrayInputStream(prefix, 0, prefixSize),
                    source
            );
            return streamingFormat.openStreamingReader(replay, environment);
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            if (failure != null) {
                closeAfterFailedOpen(source, failure);
            }
        }
    }

    /// Detects and opens an archive from a forward-only readable channel.
    public static ArkivoStreamingReader openStreamingReader(ReadableByteChannel source) throws IOException {
        return openStreamingReader(source, Map.of());
    }

    /// Detects and opens an archive from a forward-only readable channel with environment options.
    public static ArkivoStreamingReader openStreamingReader(
            ReadableByteChannel source,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(environment, "environment");
        return openStreamingReader(Channels.newInputStream(source), environment);
    }

    /// Returns the matching format with the largest requested probe size.
    private static @Nullable ArkivoFormat detect(
            ByteBuffer prefix,
            @Unmodifiable List<ArkivoFormat> formats
    ) {
        @Nullable ArkivoFormat detected = null;
        int detectedProbeSize = -1;
        for (ArkivoFormat format : formats) {
            int probeSize = format.probeSize();
            if (probeSize < 0) {
                throw new IllegalStateException("Archive format probe size must not be negative: " + format.name());
            }
            if (format.matches(prefix.asReadOnlyBuffer())) {
                if (detected == null || probeSize > detectedProbeSize) {
                    detected = format;
                    detectedProbeSize = probeSize;
                }
            }
        }
        return detected;
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

    /// Closes a consumed source after setup fails without hiding the primary failure.
    private static void closeAfterFailedOpen(InputStream source, Throwable failure) {
        try {
            source.close();
        } catch (IOException | RuntimeException | Error exception) {
            failure.addSuppressed(exception);
        }
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
