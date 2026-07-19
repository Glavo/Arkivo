// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar;

import org.glavo.arkivo.archive.ArchiveCreateOptions;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArchiveUpdateOptions;
import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFormat.FileSystem;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.ArkivoFormat.StreamingReader;
import org.glavo.arkivo.archive.ArkivoFormat.StreamingWriter;
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

/// Describes Arkivo's indexed and streaming AR support.
///
/// This descriptor opens read-only, creation, and complete-rewrite NIO file systems as well as forward-only readers
/// and writers. Generic options retain the AR UTF-8 metadata default. Factory methods have the same ownership,
/// close-time finalization, and update publication contracts as the corresponding concrete AR types.
@NotNullByDefault
public final class ArArkivoFormat implements
        FileSystem.Writable,
        StreamingReader,
        StreamingWriter {
    /// The stable AR format name.
    public static final String NAME = "ar";

    /// The shared AR format instance.
    private static final ArArkivoFormat INSTANCE = new ArArkivoFormat();

    /// Creates the canonical AR format descriptor.
    private ArArkivoFormat() {
    }

    /// Returns the shared AR format descriptor.
    ///
    /// @return the process-wide immutable descriptor
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
    public ArArkivoFileSystem open(Path path, ArchiveReadOptions options) throws IOException {
        return ArArkivoFileSystem.open(path, readOptions(options));
    }

    /// Creates a new path-backed AR archive file system.
    @Override
    public ArArkivoFileSystem create(Path path, ArchiveCreateOptions options) throws IOException {
        return ArArkivoFileSystem.create(path, createOptions(options));
    }

    /// Opens a complete-rewrite update of a path-backed AR archive.
    @Override
    public ArArkivoFileSystem update(Path path, ArchiveUpdateOptions options) throws IOException {
        return ArArkivoFileSystem.update(path, updateOptions(options));
    }

    /// Opens a read-only AR archive file system directly from one owned seekable channel.
    @Override
    public ArArkivoFileSystem open(SeekableByteChannel source) throws IOException {
        return ArArkivoFileSystem.open(source);
    }

    /// Opens an AR archive file system directly from one owned seekable channel with options.
    @Override
    public ArArkivoFileSystem open(SeekableByteChannel source, ArchiveReadOptions options) throws IOException {
        return ArArkivoFileSystem.open(source, readOptions(options));
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
            ArchiveReadOptions options
    ) throws IOException {
        return ArArkivoFileSystem.open(source, readOptions(options));
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
            ArchiveReadOptions options
    ) {
        Objects.requireNonNull(options, "options");
        return ArArkivoStreamingReader.open(source, readOptions(options));
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
            ArchiveReadOptions options
    ) {
        Objects.requireNonNull(options, "options");
        return ArArkivoStreamingReader.open(source, readOptions(options));
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
            ArchiveCreateOptions options
    ) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(options, "options");
        @Nullable ArkivoEditStorage bodyStorage = options.editStorage();
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
            ArchiveCreateOptions options
    ) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(options, "options");
        @Nullable ArkivoEditStorage bodyStorage = options.editStorage();
        return bodyStorage == null
                ? ArArkivoStreamingWriter.open(output)
                : ArArkivoStreamingWriter.open(output, bodyStorage);
    }

    /// Applies AR defaults to format-independent read options.
    private static ArArchiveOptions.Read readOptions(ArchiveReadOptions options) {
        return new ArArchiveOptions.Read(options, ArArchiveOptions.DEFAULT_METADATA_CHARSET_DETECTOR);
    }

    /// Applies AR defaults to format-independent creation options.
    private static ArArchiveOptions.Create createOptions(ArchiveCreateOptions options) {
        return new ArArchiveOptions.Create(options, ArArchiveOptions.DEFAULT_METADATA_CHARSET_DETECTOR);
    }

    /// Applies AR defaults to format-independent update options.
    private static ArArchiveOptions.Update updateOptions(ArchiveUpdateOptions options) {
        return new ArArchiveOptions.Update(options, ArArchiveOptions.DEFAULT_METADATA_CHARSET_DETECTOR);
    }
}
