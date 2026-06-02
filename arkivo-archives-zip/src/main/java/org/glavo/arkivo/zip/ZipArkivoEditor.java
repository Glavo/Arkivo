package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoEditor;
import org.glavo.arkivo.ArkivoName;
import org.glavo.arkivo.ArkivoReader;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/// Edits an existing ZIP archive.
@NotNullByDefault
public final class ZipArkivoEditor implements ArkivoEditor<ZipInfo, ZipInfoSpec> {
    /// The archive channel.
    private final SeekableByteChannel channel;

    /// Whether closing this editor should close the archive channel.
    private final boolean closeChannel;

    /// Creates a ZIP editor over the given channel.
    private ZipArkivoEditor(SeekableByteChannel channel, boolean closeChannel) {
        this.channel = channel;
        this.closeChannel = closeChannel;
    }

    /// Opens an existing ZIP archive for editing.
    public static ZipArkivoEditor open(Path path) throws IOException {
        return new ZipArkivoEditor(Files.newByteChannel(path, StandardOpenOption.READ, StandardOpenOption.WRITE), true);
    }

    /// Opens an existing ZIP archive channel for editing.
    public static ZipArkivoEditor open(SeekableByteChannel channel) {
        return new ZipArkivoEditor(channel, false);
    }

    /// Adds a new ZIP item from the given channel.
    @Override
    public void add(ReadableByteChannel source, ZipInfoSpec spec) throws IOException {
        throw notImplemented();
    }

    /// Replaces an existing ZIP item with data read from the given channel.
    @Override
    public void replace(ArkivoName name, ReadableByteChannel source, ZipInfoSpec spec) throws IOException {
        throw notImplemented();
    }

    /// Removes ZIP items with the given name.
    @Override
    public void remove(ArkivoName name) throws IOException {
        throw notImplemented();
    }

    /// Returns a read-only view of the current ZIP archive state.
    @Override
    public ArkivoReader<ZipInfo> reader() throws IOException {
        throw notImplemented();
    }

    /// Commits the accumulated ZIP archive changes.
    @Override
    public void commit() throws IOException {
        throw notImplemented();
    }

    /// Closes the editor and its owned channel.
    @Override
    public void close() throws IOException {
        if (closeChannel) {
            channel.close();
        }
    }

    /// Returns the placeholder exception used until ZIP editing is implemented.
    private static UnsupportedOperationException notImplemented() {
        return new UnsupportedOperationException("ZIP archive editing is not implemented yet");
    }
}
