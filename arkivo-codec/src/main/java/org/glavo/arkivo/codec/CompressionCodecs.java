// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.glavo.arkivo.codec.internal.CodecTransferSupport;
import org.glavo.arkivo.codec.internal.PrefixReplayReadableByteChannel;
import org.glavo.arkivo.codec.internal.StreamChannelAdapters;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
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

    /// Detects an installed codec from a forward-only channel while preserving every source byte for later reads.
    ///
    /// The returned channel must replace the original channel in the caller's read pipeline. Closing it leaves the
    /// original channel open.
    public static CompressionProbeResult probe(ReadableByteChannel source) throws IOException {
        return probe(source, 0, ChannelOwnership.RETAIN);
    }

    /// Detects an installed codec from a forward-only channel with explicit source ownership.
    public static CompressionProbeResult probe(
            ReadableByteChannel source,
            ChannelOwnership ownership
    ) throws IOException {
        return probe(source, 0, ownership);
    }

    /// Detects an installed codec while retaining at least the requested prefix and applying source ownership.
    ///
    /// The returned channel replays all retained bytes before continuing from the source. A larger prefix allows a
    /// container parser to reject signature collisions without reading the original channel twice. With `CLOSE`, the
    /// source is closed when the replay channel closes or when probing fails after argument validation.
    public static CompressionProbeResult probe(
            ReadableByteChannel source,
            long minimumPrefixSize,
            ChannelOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(ownership, "ownership");
        if (minimumPrefixSize < 0L || minimumPrefixSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "minimumPrefixSize must be between zero and " + Integer.MAX_VALUE
            );
        }
        try {
            @Unmodifiable List<CompressionCodec> codecs = installed();
            int probeSize = Math.max(Math.toIntExact(minimumPrefixSize), maxProbeSize(codecs));
            ByteBuffer prefix = ByteBuffer.allocate(probeSize);
            while (prefix.hasRemaining()) {
                int read = source.read(prefix);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    throw new IOException("Compression codec probe made no progress");
                }
            }
            prefix.flip();
            @Nullable CompressionCodec codec = detect(prefix, codecs);
            return new CompressionProbeResult(
                    codec,
                    prefix.asReadOnlyBuffer(),
                    new PrefixReplayReadableByteChannel(prefix, source, ownership)
            );
        } catch (IOException | RuntimeException | Error exception) {
            if (ownership == ChannelOwnership.CLOSE) {
                closeAfterProbeFailure(source, exception);
            }
            throw exception;
        }
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

    /// Opens a default encoder for the named installed codec while retaining the target channel.
    public static CompressionEncoder openEncoder(
            String codecName,
            WritableByteChannel target
    ) throws IOException {
        return openEncoder(codecName, target, CodecOptions.EMPTY, ChannelOwnership.RETAIN);
    }

    /// Opens a default encoder for the named installed codec with explicit target ownership.
    public static CompressionEncoder openEncoder(
            String codecName,
            WritableByteChannel target,
            ChannelOwnership ownership
    ) throws IOException {
        return openEncoder(codecName, target, CodecOptions.EMPTY, ownership);
    }

    /// Opens a configured encoder for the named installed codec while retaining the target channel.
    public static CompressionEncoder openEncoder(
            String codecName,
            WritableByteChannel target,
            CodecOptions options
    ) throws IOException {
        return openEncoder(codecName, target, options, ChannelOwnership.RETAIN);
    }

    /// Opens a configured encoder for the named installed codec with explicit target ownership.
    ///
    /// After argument validation, CLOSE transfers ownership before codec lookup and setup.
    public static CompressionEncoder openEncoder(
            String codecName,
            WritableByteChannel target,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(codecName, "codecName");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(ownership, "ownership");
        try {
            CompressionCodec codec = requireCodec(
                    codecName,
                    CompressionFeature.COMPRESSION,
                    "compression"
            );
            return codec.openEncoder(target, options, ownership);
        } catch (IOException | RuntimeException | Error exception) {
            if (ownership == ChannelOwnership.CLOSE) {
                closeAfterOpenFailure(target, exception);
            }
            throw exception;
        }
    }

    /// Opens a default decoder for the named installed codec while retaining the source channel.
    public static CompressionDecoder openDecoder(
            String codecName,
            ReadableByteChannel source
    ) throws IOException {
        return openDecoder(codecName, source, CodecOptions.EMPTY, ChannelOwnership.RETAIN);
    }

    /// Opens a default decoder for the named installed codec with explicit source ownership.
    public static CompressionDecoder openDecoder(
            String codecName,
            ReadableByteChannel source,
            ChannelOwnership ownership
    ) throws IOException {
        return openDecoder(codecName, source, CodecOptions.EMPTY, ownership);
    }

    /// Opens a configured decoder for the named installed codec while retaining the source channel.
    public static CompressionDecoder openDecoder(
            String codecName,
            ReadableByteChannel source,
            CodecOptions options
    ) throws IOException {
        return openDecoder(codecName, source, options, ChannelOwnership.RETAIN);
    }

    /// Opens a configured decoder for the named installed codec with explicit source ownership.
    ///
    /// This method bypasses signature detection. After argument validation, CLOSE transfers ownership before codec
    /// lookup and setup.
    public static CompressionDecoder openDecoder(
            String codecName,
            ReadableByteChannel source,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(codecName, "codecName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(ownership, "ownership");
        try {
            CompressionCodec codec = requireCodec(
                    codecName,
                    CompressionFeature.DECOMPRESSION,
                    "decompression"
            );
            return codec.openDecoder(source, options, ownership);
        } catch (IOException | RuntimeException | Error exception) {
            if (ownership == ChannelOwnership.CLOSE) {
                closeAfterOpenFailure(source, exception);
            }
            throw exception;
        }
    }

    /// Detects a signed stream and opens a default decoder while retaining the source channel.
    public static CompressionDecoder openDecoder(ReadableByteChannel source) throws IOException {
        return openDecoder(source, CodecOptions.EMPTY, ChannelOwnership.RETAIN);
    }

    /// Detects a signed stream and opens a configured decoder while retaining the source channel.
    public static CompressionDecoder openDecoder(
            ReadableByteChannel source,
            CodecOptions options
    ) throws IOException {
        return openDecoder(source, options, ChannelOwnership.RETAIN);
    }

    /// Detects a signed stream and opens a default decoder with explicit source ownership.
    public static CompressionDecoder openDecoder(
            ReadableByteChannel source,
            ChannelOwnership ownership
    ) throws IOException {
        return openDecoder(source, CodecOptions.EMPTY, ownership);
    }

    /// Detects a signed stream and opens a configured decoder with explicit source ownership.
    ///
    /// The decoder receives every byte consumed by detection. Codecs without a reliable signature must be opened by
    /// name. Closing the decoder closes its replay channel; that channel applies the requested ownership to the original
    /// source.
    public static CompressionDecoder openDecoder(
            ReadableByteChannel source,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(ownership, "ownership");

        CompressionProbeResult probe = probe(source, ownership);
        ReadableByteChannel replay = probe.channel();
        try {
            @Nullable CompressionCodec codec = probe.codec();
            if (codec == null) {
                throw new IOException("Unrecognized compression codec");
            }
            if (!codec.canDecompress()) {
                throw new UnsupportedOperationException(
                        "Compression codec does not support decompression: " + codec.name()
                );
            }
            return codec.openDecoder(replay, options, ChannelOwnership.CLOSE);
        } catch (IOException | RuntimeException | Error exception) {
            closeAfterOpenFailure(replay, exception);
            throw exception;
        }
    }

    /// Compresses all source bytes with the named codec while retaining both channels.
    public static CodecTransferResult compress(
            String codecName,
            ReadableByteChannel source,
            WritableByteChannel target
    ) throws IOException {
        return compress(codecName, source, target, CodecOptions.EMPTY);
    }

    /// Compresses all source bytes with the configured named codec while retaining both channels.
    public static CodecTransferResult compress(
            String codecName,
            ReadableByteChannel source,
            WritableByteChannel target,
            CodecOptions options
    ) throws IOException {
        Objects.requireNonNull(codecName, "codecName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        CompressionCodec codec = requireCodec(
                codecName,
                CompressionFeature.COMPRESSION,
                "compression"
        );
        return codec.compress(source, target, options);
    }

    /// Decompresses all source bytes with the named codec while retaining both channels.
    public static CodecTransferResult decompress(
            String codecName,
            ReadableByteChannel source,
            WritableByteChannel target
    ) throws IOException {
        return decompress(codecName, source, target, CodecOptions.EMPTY);
    }

    /// Decompresses all source bytes with the configured named codec while retaining both channels.
    public static CodecTransferResult decompress(
            String codecName,
            ReadableByteChannel source,
            WritableByteChannel target,
            CodecOptions options
    ) throws IOException {
        Objects.requireNonNull(codecName, "codecName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        CompressionCodec codec = requireCodec(
                codecName,
                CompressionFeature.DECOMPRESSION,
                "decompression"
        );
        return codec.decompress(source, target, options);
    }

    /// Detects a signed stream and decompresses all bytes while retaining both channels.
    public static CodecTransferResult decompress(
            ReadableByteChannel source,
            WritableByteChannel target
    ) throws IOException {
        return decompress(source, target, CodecOptions.EMPTY);
    }

    /// Detects a signed stream and decompresses all bytes with options while retaining both channels.
    public static CodecTransferResult decompress(
            ReadableByteChannel source,
            WritableByteChannel target,
            CodecOptions options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        try (CompressionDecoder decoder = openDecoder(
                source,
                options,
                ChannelOwnership.RETAIN
        )) {
            return CodecTransferSupport.decompress(decoder, target);
        }
    }

    /// Opens an owning compression output stream for the named codec.
    public static OutputStream compressTo(String codecName, OutputStream target) throws IOException {
        return compressTo(codecName, target, CodecOptions.EMPTY);
    }

    /// Opens an owning configured compression output stream for the named codec.
    public static OutputStream compressTo(
            String codecName,
            OutputStream target,
            CodecOptions options
    ) throws IOException {
        Objects.requireNonNull(codecName, "codecName");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        return StreamChannelAdapters.outputStream(
                openEncoder(
                        codecName,
                        StreamChannelAdapters.writableChannel(target),
                        options,
                        ChannelOwnership.CLOSE
                )
        );
    }

    /// Opens an owning decompression input stream for the named codec.
    public static InputStream decompressFrom(String codecName, InputStream source) throws IOException {
        return decompressFrom(codecName, source, CodecOptions.EMPTY);
    }

    /// Opens an owning configured decompression input stream for the named codec.
    public static InputStream decompressFrom(
            String codecName,
            InputStream source,
            CodecOptions options
    ) throws IOException {
        Objects.requireNonNull(codecName, "codecName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return StreamChannelAdapters.inputStream(
                openDecoder(
                        codecName,
                        StreamChannelAdapters.readableChannel(source),
                        options,
                        ChannelOwnership.CLOSE
                )
        );
    }

    /// Detects a signed stream and opens an owning decompression input stream.
    public static InputStream decompressFrom(InputStream source) throws IOException {
        return decompressFrom(source, CodecOptions.EMPTY);
    }

    /// Detects a signed stream and opens an owning configured decompression input stream.
    public static InputStream decompressFrom(
            InputStream source,
            CodecOptions options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return StreamChannelAdapters.inputStream(
                openDecoder(
                        StreamChannelAdapters.readableChannel(source),
                        options,
                        ChannelOwnership.CLOSE
                )
        );
    }

    /// Returns the named installed codec after requiring one operation capability.
    private static CompressionCodec requireCodec(
            String codecName,
            CompressionFeature feature,
            String operation
    ) throws IOException {
        @Nullable CompressionCodec codec = find(codecName);
        if (codec == null) {
            throw new IOException("Unknown compression codec: " + codecName);
        }
        if (!codec.capabilities().supports(feature)) {
            throw new UnsupportedOperationException(
                    "Compression codec does not support " + operation + ": " + codec.name()
            );
        }
        return codec;
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

    /// Closes one still-open channel after context setup fails without hiding the primary failure.
    private static void closeAfterOpenFailure(Channel channel, Throwable failure) {
        if (!channel.isOpen()) {
            return;
        }
        try {
            channel.close();
        } catch (IOException | RuntimeException | Error exception) {
            if (failure != exception) {
                failure.addSuppressed(exception);
            }
        }
    }

    /// Closes an owned source after probe failure without hiding the primary exception.
    private static void closeAfterProbeFailure(ReadableByteChannel source, Throwable failure) {
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
