// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.ar;

import org.glavo.arkivo.ArkivoFormat;
import org.glavo.arkivo.ArkivoSeekableChannelSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/// Describes AR archive streaming support provided by Arkivo.
@NotNullByDefault
public final class ArArkivoFormat implements ArkivoFormat {
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
    public ArArkivoFileSystem open(Path path) throws IOException {
        return ArArkivoFileSystem.open(path);
    }

    /// Opens an AR archive file system with environment options.
    public ArArkivoFileSystem open(Path path, Map<String, ?> environment) throws IOException {
        return ArArkivoFileSystem.open(path, environment);
    }

    /// Opens a read-only AR archive file system from a repeatable seekable channel source.
    public ArArkivoFileSystem open(ArkivoSeekableChannelSource source) throws IOException {
        return ArArkivoFileSystem.open(source);
    }

    /// Opens a read-only AR archive file system from a channel source with environment options.
    public ArArkivoFileSystem open(
            ArkivoSeekableChannelSource source,
            Map<String, ?> environment
    ) throws IOException {
        return ArArkivoFileSystem.open(source, environment);
    }

    /// Opens a streaming AR reader from an input stream.
    public ArArkivoStreamingReader openStreamingReader(InputStream source) {
        return ArArkivoStreamingReader.open(source);
    }

    /// Opens a streaming AR reader from a readable channel.
    public ArArkivoStreamingReader openStreamingReader(ReadableByteChannel source) {
        return ArArkivoStreamingReader.open(source);
    }

    /// Opens a streaming AR writer over an output stream.
    public ArArkivoStreamingWriter openStreamingWriter(OutputStream output) {
        return ArArkivoStreamingWriter.open(output);
    }

    /// Opens a streaming AR writer over a writable channel.
    public ArArkivoStreamingWriter openStreamingWriter(WritableByteChannel output) {
        return ArArkivoStreamingWriter.open(output);
    }
}
