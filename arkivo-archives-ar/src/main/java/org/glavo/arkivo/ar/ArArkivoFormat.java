// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.ar;

import org.glavo.arkivo.ArkivoFormat;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
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

    /// Opens an AR archive file system.
    public ArArkivoFileSystem open(Path path) throws IOException {
        return ArArkivoFileSystem.open(path);
    }

    /// Opens an AR archive file system with environment options.
    public ArArkivoFileSystem open(Path path, Map<String, ?> environment) throws IOException {
        return ArArkivoFileSystem.open(path, environment);
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
