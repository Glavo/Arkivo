package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoName;
import org.glavo.arkivo.ArkivoWriter;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/// Writes a new ZIP archive.
@NotNullByDefault
public final class ZipArkivoWriter implements ArkivoWriter<ZipInfoSpec> {
    /// The target channel.
    private final WritableByteChannel target;

    /// Whether closing this writer should close the target channel.
    private final boolean closeChannel;

    /// Creates a ZIP writer over the given target channel.
    private ZipArkivoWriter(WritableByteChannel target, boolean closeChannel) {
        this.target = target;
        this.closeChannel = closeChannel;
    }

    /// Opens a target path for writing a new ZIP archive.
    public static ZipArkivoWriter open(Path path) throws IOException {
        return new ZipArkivoWriter(Files.newByteChannel(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), true);
    }

    /// Opens a target path for writing a ZIP archive with explicit open options.
    public static ZipArkivoWriter open(Path path, OpenOption... options) throws IOException {
        return new ZipArkivoWriter(Files.newByteChannel(path, options), true);
    }

    /// Opens a target channel for writing a ZIP archive.
    public static ZipArkivoWriter open(WritableByteChannel target) {
        return new ZipArkivoWriter(target, false);
    }

    /// Adds a new ZIP item from a source channel.
    @Override
    public void add(ReadableByteChannel source, ZipInfoSpec spec) throws IOException {
        throw new UnsupportedOperationException("ZIP archive writing is not implemented yet");
    }

    /// Adds a file system path under the given ZIP item name.
    @Override
    public void add(Path source, ArkivoName name) throws IOException {
        throw new UnsupportedOperationException("ZIP archive writing is not implemented yet");
    }

    /// Closes the writer and its owned channel.
    @Override
    public void close() throws IOException {
        if (closeChannel) {
            target.close();
        }
    }
}
