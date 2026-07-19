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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Discovers installed official compression formats and uses their default immutable codecs for convenience operations.
///
/// A forward-only probe's replay channel preserves [InterruptibleChannel] exactly when the supplied source implements
/// it. An automatically detected decoder retains that capability when the selected codec's channel factory does, as the
/// [CompressionCodec] default implementation does.
@NotNullByDefault
public final class CompressionFormats {
    /// Immutable catalog of official format modules present when this class is initialized.
    private static final Catalog INSTALLED_FORMATS = Catalog.loadBuiltins();

    /// Creates no instances.
    private CompressionFormats() {
    }

    /// Returns all installed official compression formats.
    ///
    /// The result is fixed when this class is initialized. Implementations not listed by Arkivo are ignored even if they
    /// implement [CompressionFormat].
    ///
    /// @return an immutable list in deterministic official order
    public static @Unmodifiable List<CompressionFormat> installed() {
        return INSTALLED_FORMATS.formats();
    }

    /// Returns the first format with the given stable name or alias, ignoring case.
    ///
    /// @param name the stable format name or alias to find
    /// @return the matching installed format, or `null` when none is installed
    public static @Nullable CompressionFormat find(String name) {
        return INSTALLED_FORMATS.find(name);
    }

    /// Returns the installed format with the given stable name or alias, ignoring case.
    ///
    /// @param name the stable format name or alias to require
    /// @return the matching installed format
    /// @throws IllegalArgumentException when no matching format is installed
    public static CompressionFormat require(String name) {
        return INSTALLED_FORMATS.require(name);
    }

    /// Returns the matching installed format with the largest preferred probe size.
    ///
    /// The supplied buffer is not modified.
    ///
    /// @param prefix the buffer whose remaining bytes contain the candidate prefix
    /// @return the best matching installed format, or `null` when no signature matches
    public static @Nullable CompressionFormat detect(ByteBuffer prefix) {
        return INSTALLED_FORMATS.detect(prefix);
    }

    /// Detects an installed format from a forward-only channel while preserving every source byte for later reads.
    ///
    /// The result owns a replay channel until `takeChannel()` transfers it into the caller's read pipeline. Ordinary idle
    /// closure leaves the borrowed original channel open. Interruption or concurrent close during an active replay read
    /// closes the source to unblock that read.
    ///
    /// @param source the forward-only source to probe without closing
    /// @return a probe result containing the detected format, retained prefix, and replay channel
    /// @throws IOException if source reading or probe setup fails or the source makes no progress
    public static CompressionProbeResult probe(ReadableByteChannel source) throws IOException {
        return probe(source, 0L, ResourceOwnership.BORROWED);
    }

    /// Detects an installed format from a forward-only channel with explicit source ownership.
    ///
    /// @param source    the forward-only source to probe
    /// @param ownership whether the probe result ultimately owns `source`
    /// @return a probe result containing the detected format, retained prefix, and replay channel
    /// @throws IOException if source reading, probe setup, or owned-source cleanup fails
    public static CompressionProbeResult probe(
            ReadableByteChannel source,
            ResourceOwnership ownership
    ) throws IOException {
        return probe(source, 0L, ownership);
    }

    /// Detects an installed format while retaining at least the requested prefix and applying source ownership.
    ///
    /// The result owns a channel that replays all retained bytes before continuing from the source. A larger prefix
    /// allows a container parser to reject signature collisions without reading the original channel twice. Call
    /// `takeChannel()` to transfer the replay channel; otherwise close the result. With OWNED, the source is closed when
    /// the owning result or transferred replay channel closes, or when probing fails after argument validation.
    ///
    /// @param source            the forward-only source to probe
    /// @param minimumPrefixSize the minimum number of prefix bytes to retain, between zero and [Integer#MAX_VALUE]
    /// @param ownership         whether the probe result ultimately owns `source`
    /// @return a probe result containing the detected format, retained prefix, and replay channel
    /// @throws IOException              if source reading, probe setup, or owned-source cleanup fails
    /// @throws IllegalArgumentException if `minimumPrefixSize` is negative or exceeds [Integer#MAX_VALUE]
    public static CompressionProbeResult probe(
            ReadableByteChannel source,
            long minimumPrefixSize,
            ResourceOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(ownership, "ownership");
        if (minimumPrefixSize < 0L || minimumPrefixSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "minimumPrefixSize must be between zero and " + Integer.MAX_VALUE
            );
        }
        try {
            int probeSize = Math.max(Math.toIntExact(minimumPrefixSize), INSTALLED_FORMATS.probeSize());
            ByteBuffer prefix = ByteBuffer.allocate(probeSize);
            while (prefix.hasRemaining()) {
                int read = source.read(prefix);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    throw new IOException("Compression format probe made no progress");
                }
            }
            prefix.flip();
            @Nullable CompressionFormat format = INSTALLED_FORMATS.detect(prefix);
            return new CompressionProbeResult(
                    format,
                    prefix.asReadOnlyBuffer(),
                    PrefixReplayReadableByteChannel.create(prefix, source, ownership)
            );
        } catch (IOException | RuntimeException | Error exception) {
            if (ownership == ResourceOwnership.OWNED) {
                closeAfterProbeFailure(source, exception);
            }
            throw exception;
        }
    }

    /// Detects an installed format from bytes at the channel's current position.
    ///
    /// The channel position is restored before this method returns or throws.
    ///
    /// @param channel the seekable channel to probe without closing
    /// @return the matching installed format, or `null` when no signature matches
    /// @throws IOException if reading fails, the channel makes no progress, or its position cannot be restored
    public static @Nullable CompressionFormat detect(SeekableByteChannel channel) throws IOException {
        Objects.requireNonNull(channel, "channel");
        int probeSize = INSTALLED_FORMATS.probeSize();
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
                    throw new IOException("Compression format probe made no progress");
                }
            }
            prefix.flip();
            return INSTALLED_FORMATS.detect(prefix);
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            restorePosition(channel, originalPosition, failure);
        }
    }

    /// Detects an installed format from the beginning of the given path.
    ///
    /// @param path the path whose initial bytes are probed
    /// @return the matching installed format, or `null` when no signature matches
    /// @throws IOException if the path cannot be opened or read
    public static @Nullable CompressionFormat detect(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
            return detect(channel);
        }
    }

    /// Creates a compressing channel for the named format using default options and borrowing the target.
    ///
    /// @param formatName the stable name or alias of an installed format
    /// @param target     the channel that receives compressed bytes and remains open after the returned channel closes
    /// @return a new compressing channel using the format's default codec
    /// @throws IOException if the encoder cannot be initialized
    /// @throws IllegalArgumentException if `formatName` does not name an installed format
    public static CompressingWritableByteChannel newWritableByteChannel(
            String formatName,
            WritableByteChannel target
    ) throws IOException {
        return newWritableByteChannel(
                formatName,
                target,
                EncodingOptions.DEFAULT,
                ResourceOwnership.BORROWED
        );
    }

    /// Creates a compressing channel for the named format using options and explicit target ownership.
    ///
    /// After argument validation, `OWNED` transfers ownership before codec lookup and setup.
    ///
    /// @param formatName the stable name or alias of an installed format
    /// @param target     the channel that receives compressed bytes
    /// @param options    the parameters for this encoding session
    /// @param ownership  whether closing the returned channel also closes `target`
    /// @return a new compressing channel using the format's default codec
    /// @throws IOException if the encoder cannot be initialized or ownership cleanup fails
    /// @throws IllegalArgumentException if `formatName` does not name an installed format
    public static CompressingWritableByteChannel newWritableByteChannel(
            String formatName,
            WritableByteChannel target,
            EncodingOptions options,
            ResourceOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(ownership, "ownership");
        try {
            return requireDefaultCodec(formatName).newWritableByteChannel(target, options, ownership);
        } catch (IOException | RuntimeException | Error exception) {
            if (ownership == ResourceOwnership.OWNED) {
                closeAfterOpenFailure(target, exception);
            }
            throw exception;
        }
    }

    /// Creates a decompressing channel for the named format while borrowing the source.
    ///
    /// @param formatName the stable name or alias of an installed format
    /// @param source     the channel supplying compressed bytes and remaining open after the returned channel closes
    /// @return a new decompressing channel using the format's default codec
    /// @throws IOException if the decoder cannot be initialized
    /// @throws IllegalArgumentException if `formatName` does not name an installed format
    public static DecompressingReadableByteChannel newReadableByteChannel(
            String formatName,
            ReadableByteChannel source
    ) throws IOException {
        return newReadableByteChannel(formatName, source, ResourceOwnership.BORROWED);
    }

    /// Creates a decompressing channel for the named format with explicit source ownership.
    ///
    /// This method bypasses signature detection. After argument validation, OWNED transfers ownership before codec
    /// lookup and setup.
    ///
    /// @param formatName the stable name or alias of an installed format
    /// @param source     the channel supplying compressed bytes
    /// @param ownership  whether closing the returned channel also closes `source`
    /// @return a new decompressing channel using the format's default codec
    /// @throws IOException if the decoder cannot be initialized or ownership cleanup fails
    /// @throws IllegalArgumentException if `formatName` does not name an installed format
    public static DecompressingReadableByteChannel newReadableByteChannel(
            String formatName,
            ReadableByteChannel source,
            ResourceOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(ownership, "ownership");
        try {
            return requireDefaultCodec(formatName).newReadableByteChannel(source, ownership);
        } catch (IOException | RuntimeException | Error exception) {
            if (ownership == ResourceOwnership.OWNED) {
                closeAfterOpenFailure(source, exception);
            }
            throw exception;
        }
    }

    /// Detects a signed stream and creates a decompressing channel while borrowing the source.
    ///
    /// @param source the channel supplying signed compressed bytes and remaining open after the returned channel closes
    /// @return a new decompressing channel using the detected format's default codec
    /// @throws IOException if probing fails, the format is unrecognized, or the decoder cannot be initialized
    public static DecompressingReadableByteChannel newReadableByteChannel(
            ReadableByteChannel source
    ) throws IOException {
        return newReadableByteChannel(source, ResourceOwnership.BORROWED);
    }

    /// Detects a signed stream and creates a decompressing channel with explicit source ownership.
    ///
    /// The decoder receives every byte consumed by detection. Formats without a reliable signature must be opened by
    /// name. Closing the decoder closes its replay channel; that channel applies the requested ownership to the original
    /// source.
    ///
    /// @param source    the channel supplying signed compressed bytes
    /// @param ownership whether closing the returned channel also closes `source`
    /// @return a new decompressing channel using the detected format's default codec
    /// @throws IOException if probing fails, the format is unrecognized, decoder setup fails, or ownership cleanup fails
    public static DecompressingReadableByteChannel newReadableByteChannel(
            ReadableByteChannel source,
            ResourceOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(ownership, "ownership");

        CompressionProbeResult probe = probe(source, ownership);
        ReadableByteChannel replay = probe.takeChannel();
        try {
            @Nullable CompressionFormat format = probe.format();
            if (format == null) {
                throw new IOException("Unrecognized compression format");
            }
            return format.defaultCodec().newReadableByteChannel(replay, ResourceOwnership.OWNED);
        } catch (IOException | RuntimeException | Error exception) {
            closeAfterOpenFailure(replay, exception);
            throw exception;
        }
    }

    /// Compresses all source bytes with the named format while retaining both channels.
    ///
    /// @param formatName the stable name or alias of an installed format
    /// @param source     the channel supplying uncompressed bytes until end-of-input
    /// @param target     the channel receiving the complete compressed encoding
    /// @return the uncompressed input and compressed output byte counts
    /// @throws IOException if channel I/O, encoding, finalization, or progress fails
    /// @throws IllegalArgumentException if `formatName` does not name an installed format
    public static CodecTransferResult compress(
            String formatName,
            ReadableByteChannel source,
            WritableByteChannel target
    ) throws IOException {
        return compress(formatName, source, target, EncodingOptions.DEFAULT);
    }

    /// Compresses all source bytes with the named format and operation-scoped options.
    ///
    /// @param formatName the stable name or alias of an installed format
    /// @param source     the channel supplying uncompressed bytes until end-of-input
    /// @param target     the channel receiving the complete compressed encoding
    /// @param options    the parameters for this encoding operation
    /// @return the uncompressed input and compressed output byte counts
    /// @throws IOException if channel I/O, encoding, finalization, or progress fails
    /// @throws IllegalArgumentException if `formatName` does not name an installed format
    public static CodecTransferResult compress(
            String formatName,
            ReadableByteChannel source,
            WritableByteChannel target,
            EncodingOptions options
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        return requireDefaultCodec(formatName).compress(source, target, options);
    }

    /// Decompresses all source bytes with the named format while retaining both channels.
    ///
    /// @param formatName the stable name or alias of an installed format
    /// @param source     the channel supplying compressed bytes
    /// @param target     the channel receiving decoded bytes
    /// @return the compressed input and decoded output byte counts
    /// @throws IOException if channel I/O, decoding, or progress fails
    /// @throws IllegalArgumentException if `formatName` does not name an installed format
    public static CodecTransferResult decompress(
            String formatName,
            ReadableByteChannel source,
            WritableByteChannel target
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        return requireDefaultCodec(formatName).decompress(source, target);
    }

    /// Detects a signed stream and decompresses all bytes while retaining both channels.
    ///
    /// @param source the channel supplying signed compressed bytes
    /// @param target the channel receiving decoded bytes
    /// @return the compressed input and decoded output byte counts
    /// @throws IOException if probing fails, the format is unrecognized, or channel I/O or decoding fails
    public static CodecTransferResult decompress(
            ReadableByteChannel source,
            WritableByteChannel target
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        try (DecompressingReadableByteChannel decoder =
                     newReadableByteChannel(source, ResourceOwnership.BORROWED)) {
            return CodecTransferSupport.decompress(decoder, target);
        }
    }

    /// Creates a compressing output stream for the named format while retaining the target stream.
    ///
    /// @param formatName the stable name or alias of an installed format
    /// @param target     the stream receiving compressed bytes and remaining open after the returned stream closes
    /// @return a new compressing stream using the format's default codec
    /// @throws IOException if the encoder cannot be initialized
    /// @throws IllegalArgumentException if `formatName` does not name an installed format
    public static OutputStream newOutputStream(String formatName, OutputStream target) throws IOException {
        return newOutputStream(
                formatName,
                target,
                EncodingOptions.DEFAULT,
                ResourceOwnership.BORROWED
        );
    }

    /// Creates a compressing output stream for the named format using options and explicit target ownership.
    ///
    /// @param formatName the stable name or alias of an installed format
    /// @param target     the stream receiving compressed bytes
    /// @param options    the parameters for this encoding session
    /// @param ownership  whether closing the returned stream also closes `target`
    /// @return a new compressing stream using the format's default codec
    /// @throws IOException if encoder setup or ownership cleanup fails
    /// @throws IllegalArgumentException if `formatName` does not name an installed format
    public static OutputStream newOutputStream(
            String formatName,
            OutputStream target,
            EncodingOptions options,
            ResourceOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(ownership, "ownership");
        return StreamChannelAdapters.outputStream(
                newWritableByteChannel(
                        formatName,
                        StreamChannelAdapters.writableChannel(target),
                        options,
                        ownership
                )
        );
    }

    /// Creates a decompressing input stream for the named format while borrowing the source.
    ///
    /// @param formatName the stable name or alias of an installed format
    /// @param source     the stream supplying compressed bytes and remaining open after the returned stream closes
    /// @return a new decompressing stream using the format's default codec
    /// @throws IOException if the decoder cannot be initialized
    /// @throws IllegalArgumentException if `formatName` does not name an installed format
    public static InputStream newInputStream(String formatName, InputStream source) throws IOException {
        return newInputStream(formatName, source, ResourceOwnership.BORROWED);
    }

    /// Creates a decompressing input stream for the named format with explicit source ownership.
    ///
    /// @param formatName the stable name or alias of an installed format
    /// @param source     the stream supplying compressed bytes
    /// @param ownership  whether closing the returned stream also closes `source`
    /// @return a new decompressing stream using the format's default codec
    /// @throws IOException if decoder setup or ownership cleanup fails
    /// @throws IllegalArgumentException if `formatName` does not name an installed format
    public static InputStream newInputStream(
            String formatName,
            InputStream source,
            ResourceOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(ownership, "ownership");
        return StreamChannelAdapters.inputStream(
                newReadableByteChannel(
                        formatName,
                        StreamChannelAdapters.readableChannel(source),
                        ownership
                )
        );
    }

    /// Detects a signed stream and creates a decompressing input stream while borrowing the source.
    ///
    /// @param source the stream supplying signed compressed bytes and remaining open after the returned stream closes
    /// @return a new decompressing stream using the detected format's default codec
    /// @throws IOException if probing fails, the format is unrecognized, or the decoder cannot be initialized
    public static InputStream newInputStream(InputStream source) throws IOException {
        return newInputStream(source, ResourceOwnership.BORROWED);
    }

    /// Detects a signed stream and creates a decompressing input stream with explicit source ownership.
    ///
    /// @param source    the stream supplying signed compressed bytes
    /// @param ownership whether closing the returned stream also closes `source`
    /// @return a new decompressing stream using the detected format's default codec
    /// @throws IOException if probing fails, the format is unrecognized, decoder setup fails, or ownership cleanup fails
    public static InputStream newInputStream(
            InputStream source,
            ResourceOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(ownership, "ownership");
        return StreamChannelAdapters.inputStream(
                newReadableByteChannel(StreamChannelAdapters.readableChannel(source), ownership)
        );
    }

    /// Returns the named format's installed default codec.
    private static CompressionCodec<?> requireDefaultCodec(String formatName) {
        return require(formatName).defaultCodec();
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

    /// Stores and validates the installed official compression-format catalog.
    ///
    /// The implementation list is intentionally closed. Classes that are absent represent official optional modules
    /// that were not installed; arbitrary implementations of [CompressionFormat] are never discovered.
    @NotNullByDefault
    static final class Catalog {
        /// Official format singleton classes in deterministic detection order.
        private static final @Unmodifiable List<String> FORMAT_CLASS_NAMES = List.of(
                "org.glavo.arkivo.codec.bzip2.BZip2Format",
                "org.glavo.arkivo.codec.compress.UnixCompressFormat",
                "org.glavo.arkivo.codec.deflate.DeflateFormat",
                "org.glavo.arkivo.codec.deflate.Deflate64Format",
                "org.glavo.arkivo.codec.deflate.GzipFormat",
                "org.glavo.arkivo.codec.deflate.ZlibFormat",
                "org.glavo.arkivo.codec.lz4.LZ4Format",
                "org.glavo.arkivo.codec.lz4.LZ4BlockFormat",
                "org.glavo.arkivo.codec.lzip.LzipFormat",
                "org.glavo.arkivo.codec.lzma.LZMAFormat",
                "org.glavo.arkivo.codec.lzma.RawLZMAFormat",
                "org.glavo.arkivo.codec.lzma.LZMA2Format",
                "org.glavo.arkivo.codec.ppmd.PPMdFormat",
                "org.glavo.arkivo.codec.xz.XZFormat",
                "org.glavo.arkivo.codec.zstd.ZstdFormat"
        );

        /// Canonical formats in deterministic official order.
        private final @Unmodifiable List<CompressionFormat> formats;

        /// Formats indexed by normalized stable names and aliases.
        private final @Unmodifiable Map<String, CompressionFormat> formatsByName;

        /// The largest preferred signature prefix requested by any format.
        private final int probeSize;

        /// Creates and validates a catalog by normalizing supplied descriptors to canonical format identities.
        private Catalog(List<CompressionFormat> formats) {
            ArrayList<CompressionFormat> copiedFormats = new ArrayList<>(formats.size());
            Set<CompressionFormat> seenFormats = Collections.newSetFromMap(new IdentityHashMap<>());
            LinkedHashMap<String, CompressionFormat> formatsByName = new LinkedHashMap<>();
            int maximumProbeSize = 0;
            for (CompressionFormat format : formats) {
                CompressionFormat checkedFormat = canonicalizeFormat(format);
                if (!seenFormats.add(checkedFormat)) {
                    continue;
                }

                registerName(formatsByName, checkedFormat.name(), checkedFormat);
                for (String alias : checkedFormat.aliases()) {
                    registerName(formatsByName, alias, checkedFormat);
                }

                int formatProbeSize = checkedFormat.probeSize();
                if (formatProbeSize < 0) {
                    throw new IllegalStateException(
                            "Compression format probe size must not be negative: " + checkedFormat.name()
                    );
                }
                maximumProbeSize = Math.max(maximumProbeSize, formatProbeSize);
                copiedFormats.add(checkedFormat);
            }

            this.formats = List.copyOf(copiedFormats);
            this.formatsByName = Map.copyOf(formatsByName);
            this.probeSize = maximumProbeSize;
        }

        /// Loads every present official format through its immutable singleton accessor.
        ///
        /// @return an immutable catalog of the official format modules present to this module's class loader
        static Catalog loadBuiltins() {
            ArrayList<CompressionFormat> formats = new ArrayList<>(FORMAT_CLASS_NAMES.size());
            for (String className : FORMAT_CLASS_NAMES) {
                @Nullable CompressionFormat format = loadFormat(className);
                if (format != null) {
                    formats.add(format);
                }
            }
            return new Catalog(formats);
        }

        /// Creates a catalog from explicit descriptors for internal validation and tests.
        ///
        /// @param formats the descriptors to canonicalize and index in preferred order
        /// @return an immutable catalog containing each canonical identity at most once
        static Catalog of(Iterable<? extends CompressionFormat> formats) {
            Objects.requireNonNull(formats, "formats");
            ArrayList<CompressionFormat> copiedFormats = new ArrayList<>();
            for (CompressionFormat format : formats) {
                copiedFormats.add(Objects.requireNonNull(format, "compression format"));
            }
            return new Catalog(copiedFormats);
        }

        /// Returns canonical formats in deterministic official order.
        ///
        /// @return the immutable ordered canonical format list
        @Unmodifiable List<CompressionFormat> formats() {
            return formats;
        }

        /// Returns the format with the given stable name or alias, ignoring case.
        ///
        /// @param name the stable format name or alias
        /// @return the matching format, or {@code null} if none matches
        @Nullable CompressionFormat find(String name) {
            Objects.requireNonNull(name, "name");
            return formatsByName.get(normalizeName(name));
        }

        /// Returns the format with the given stable name or alias.
        ///
        /// @param name the stable format name or alias
        /// @return the matching format
        /// @throws IllegalArgumentException when no matching official format is installed
        CompressionFormat require(String name) {
            @Nullable CompressionFormat format = find(name);
            if (format == null) {
                throw new IllegalArgumentException("Unknown compression format: " + name);
            }
            return format;
        }

        /// Returns the matching format with the largest preferred probe size.
        ///
        /// The supplied buffer is not modified.
        ///
        /// @param prefix the stream prefix to inspect, from its current position to its limit
        /// @return the best matching format, or {@code null} if no format recognizes the prefix
        @Nullable CompressionFormat detect(ByteBuffer prefix) {
            Objects.requireNonNull(prefix, "prefix");
            @Nullable CompressionFormat detected = null;
            int detectedProbeSize = -1;
            for (CompressionFormat format : formats) {
                if (format.matches(prefix.asReadOnlyBuffer()) && format.probeSize() > detectedProbeSize) {
                    detected = format;
                    detectedProbeSize = format.probeSize();
                }
            }
            return detected;
        }

        /// Returns the largest preferred signature prefix requested by an installed official format.
        ///
        /// @return the maximum indexed [CompressionFormat#probeSize()] value
        int probeSize() {
            return probeSize;
        }

        /// Loads one known singleton class, or returns null when its optional module is absent.
        private static @Nullable CompressionFormat loadFormat(String className) {
            try {
                Class<?> formatClass = Class.forName(className);
                Method instanceMethod = formatClass.getMethod("instance");
                if (!Modifier.isStatic(instanceMethod.getModifiers())) {
                    throw new IllegalStateException(
                            "Built-in compression format instance() is not static: " + className
                    );
                }
                Object value = instanceMethod.invoke(null);
                if (!(value instanceof CompressionFormat format)) {
                    throw new IllegalStateException(
                            "Built-in compression format has an incompatible instance: " + className
                    );
                }
                return format;
            } catch (ClassNotFoundException ignored) {
                return null;
            } catch (ReflectiveOperationException | LinkageError | SecurityException exception) {
                throw new IllegalStateException("Failed to load built-in compression format: " + className, exception);
            }
        }

        /// Resolves a descriptor to the canonical identity exposed by its default codec.
        private static CompressionFormat canonicalizeFormat(CompressionFormat format) {
            CompressionFormat suppliedFormat = Objects.requireNonNull(format, "compression format");
            CompressionCodec<?> defaultCodec = Objects.requireNonNull(
                    suppliedFormat.defaultCodec(),
                    "format default codec"
            );
            CompressionFormat canonicalFormat = Objects.requireNonNull(
                    defaultCodec.format(),
                    "default codec format"
            );
            if (canonicalFormat != suppliedFormat
                    && canonicalFormat.getClass() != suppliedFormat.getClass()) {
                throw new IllegalStateException(
                        "Compression format " + suppliedFormat.name()
                                + " does not match its default codec format " + canonicalFormat.name()
                );
            }
            CompressionCodec<?> canonicalDefaultCodec = Objects.requireNonNull(
                    canonicalFormat.defaultCodec(),
                    "canonical format default codec"
            );
            if (canonicalDefaultCodec.format() != canonicalFormat) {
                throw new IllegalStateException(
                        "Compression format " + canonicalFormat.name()
                                + " is not the identity returned by its default codec"
                );
            }
            return canonicalFormat;
        }

        /// Registers one stable name or alias after validating it for unambiguous lookup.
        private static void registerName(
                Map<String, CompressionFormat> formatsByName,
                String name,
                CompressionFormat format
        ) {
            Objects.requireNonNull(name, "format name");
            if (name.isBlank()) {
                throw new IllegalStateException("Compression format names and aliases must not be blank");
            }
            String normalizedName = normalizeName(name);
            @Nullable CompressionFormat previous = formatsByName.putIfAbsent(normalizedName, format);
            if (previous != null && previous != format) {
                throw new IllegalStateException(
                        "Ambiguous compression format name or alias " + name
                                + ": " + previous.name() + " and " + format.name()
                );
            }
        }

        /// Normalizes one format lookup name without using the process locale.
        private static String normalizeName(String name) {
            return name.toLowerCase(Locale.ROOT);
        }
    }
}
