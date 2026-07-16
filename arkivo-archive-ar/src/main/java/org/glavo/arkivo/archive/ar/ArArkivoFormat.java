// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar;

import org.glavo.arkivo.archive.ArchiveOptions;
import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFileSystemFormat;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.ArkivoStreamingReaderFormat;
import org.glavo.arkivo.archive.ArkivoStreamingWriterFormat;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/// Describes AR archive streaming support provided by Arkivo.
@NotNullByDefault
public final class ArArkivoFormat implements
        ArkivoFileSystemFormat,
        ArkivoStreamingReaderFormat,
        ArkivoStreamingWriterFormat {
    /// The stable AR format name.
    public static final String NAME = "ar";

    /// The shared AR format instance.
    private static final ArArkivoFormat INSTANCE = new ArArkivoFormat();

    /// Creates an AR format descriptor.
    public ArArkivoFormat() {
    }

    /// Returns the shared AR format descriptor.
    public static ArArkivoFormat instance() {
        return INSTANCE;
    }

    /// Returns the stable AR format name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns common AR-based archive file extensions.
    @Override
    public @Unmodifiable List<String> fileExtensions() {
        return List.of("a", "ar", "deb");
    }

    /// Returns the number of leading bytes used to identify AR archives.
    @Override
    public int probeSize() {
        return 8;
    }

    /// Returns whether the prefix starts with the AR archive signature.
    @Override
    public boolean matches(ByteBuffer prefix) {
        int position = prefix.position();
        return prefix.remaining() >= 8
                && prefix.get(position) == '!'
                && prefix.get(position + 1) == '<'
                && prefix.get(position + 2) == 'a'
                && prefix.get(position + 3) == 'r'
                && prefix.get(position + 4) == 'c'
                && prefix.get(position + 5) == 'h'
                && prefix.get(position + 6) == '>'
                && prefix.get(position + 7) == '\n';
    }

    /// Opens an AR archive file system.
    @Override
    public ArArkivoFileSystem open(Path path) throws IOException {
        return ArArkivoFileSystem.open(path);
    }

    /// Opens an AR archive file system with options.
    @Override
    public ArArkivoFileSystem open(Path path, ArchiveOptions options) throws IOException {
        return ArArkivoFileSystem.open(path, options);
    }

    /// Opens a read-only AR archive file system directly from one owned seekable channel.
    @Override
    public ArArkivoFileSystem open(SeekableByteChannel source) throws IOException {
        return ArArkivoFileSystem.open(source);
    }

    /// Opens an AR archive file system directly from one owned seekable channel with options.
    @Override
    public ArArkivoFileSystem open(SeekableByteChannel source, ArchiveOptions options) throws IOException {
        return ArArkivoFileSystem.open(source, options);
    }

    /// Opens a read-only AR archive file system from a repeatable seekable channel source.
    @Override
    public ArArkivoFileSystem open(ArkivoSeekableChannelSource source) throws IOException {
        return ArArkivoFileSystem.open(source);
    }

    /// Opens an AR archive file system from a channel source with options.
    @Override
    public ArArkivoFileSystem open(
            ArkivoSeekableChannelSource source,
            ArchiveOptions options
    ) throws IOException {
        return ArArkivoFileSystem.open(source, options);
    }

    /// Opens a streaming AR reader from an input stream.
    @Override
    public ArArkivoStreamingReader openStreamingReader(InputStream source) {
        return ArArkivoStreamingReader.open(source);
    }

    /// Opens a streaming AR reader from an input stream with options.
    @Override
    public ArArkivoStreamingReader openStreamingReader(
            InputStream source,
            ArchiveOptions options
    ) {
        Objects.requireNonNull(options, "options");
        return ArArkivoStreamingReader.open(source, options);
    }

    /// Opens a streaming AR reader from a readable channel.
    @Override
    public ArArkivoStreamingReader openStreamingReader(ReadableByteChannel source) {
        return ArArkivoStreamingReader.open(source);
    }

    /// Opens a streaming AR reader from a readable channel with options.
    @Override
    public ArArkivoStreamingReader openStreamingReader(
            ReadableByteChannel source,
            ArchiveOptions options
    ) {
        Objects.requireNonNull(options, "options");
        return ArArkivoStreamingReader.open(source, options);
    }

    /// Opens a streaming AR writer over an output stream.
    @Override
    public ArArkivoStreamingWriter openStreamingWriter(OutputStream output) {
        return ArArkivoStreamingWriter.open(output);
    }

    /// Opens a streaming AR writer over an output stream with options.
    @Override
    public ArArkivoStreamingWriter openStreamingWriter(
            OutputStream output,
            ArchiveOptions options
    ) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(options, "options");
        @Nullable ArkivoEditStorage bodyStorage = options.get(ArkivoFileSystem.EDIT_STORAGE);
        return bodyStorage == null
                ? ArArkivoStreamingWriter.open(output)
                : ArArkivoStreamingWriter.open(output, bodyStorage);
    }

    /// Opens a streaming AR writer over a writable channel.
    @Override
    public ArArkivoStreamingWriter openStreamingWriter(WritableByteChannel output) {
        return ArArkivoStreamingWriter.open(output);
    }

    /// Opens a streaming AR writer over a writable channel with options.
    @Override
    public ArArkivoStreamingWriter openStreamingWriter(
            WritableByteChannel output,
            ArchiveOptions options
    ) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(options, "options");
        @Nullable ArkivoEditStorage bodyStorage = options.get(ArkivoFileSystem.EDIT_STORAGE);
        return bodyStorage == null
                ? ArArkivoStreamingWriter.open(output)
                : ArArkivoStreamingWriter.open(output, bodyStorage);
    }
}
