// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar;

import org.glavo.arkivo.archive.ArchiveCreateOptions;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArchiveUpdateOptions;
import org.glavo.arkivo.archive.ArkivoFileSystemFormat;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.ArkivoStreamingReaderFormat;
import org.glavo.arkivo.archive.ArkivoStreamingWriterFormat;
import org.jetbrains.annotations.NotNullByDefault;
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

/// Describes TAR archive streaming support provided by Arkivo.
@NotNullByDefault
public final class TarArkivoFormat implements
        ArkivoFileSystemFormat.Writable,
        ArkivoStreamingReaderFormat,
        ArkivoStreamingWriterFormat {
    /// The size of one TAR header or padding block.
    private static final int BLOCK_SIZE = 512;

    /// The offset of the checksum field in a TAR header.
    private static final int CHECKSUM_OFFSET = 148;

    /// The size of the checksum field in a TAR header.
    private static final int CHECKSUM_SIZE = 8;

    /// The sentinel returned for an invalid TAR checksum field.
    private static final long INVALID_CHECKSUM = -1L;

    /// The stable TAR format name.
    public static final String NAME = "tar";

    /// The shared TAR format instance.
    private static final TarArkivoFormat INSTANCE = new TarArkivoFormat();

    /// Creates a classpath-discoverable TAR format descriptor.
    public TarArkivoFormat() {
    }

    /// Returns the canonical TAR service provider.
    public static TarArkivoFormat provider() {
        return INSTANCE;
    }

    /// Returns the shared TAR format descriptor.
    public static TarArkivoFormat instance() {
        return INSTANCE;
    }

    /// Returns the stable TAR format name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns common TAR archive file extensions.
    @Override
    public @Unmodifiable List<String> fileExtensions() {
        return List.of(
                "tar",
                "tar.gz",
                "tgz",
                "tar.bz2",
                "tbz2",
                "tbz",
                "tar.xz",
                "txz",
                "tar.lzma",
                "tlz",
                "tar.zst",
                "tzst"
        );
    }

    /// Returns the number of leading bytes used to identify headers and empty TAR archives.
    @Override
    public int probeSize() {
        return BLOCK_SIZE * 2;
    }

    /// Returns whether the prefix contains a valid TAR header or two empty end blocks.
    @Override
    public boolean matches(ByteBuffer prefix) {
        int position = prefix.position();
        if (prefix.remaining() < BLOCK_SIZE) {
            return false;
        }
        if (isZeroBlock(prefix, position)) {
            return prefix.remaining() >= BLOCK_SIZE * 2
                    && isZeroBlock(prefix, position + BLOCK_SIZE);
        }

        long expected = parseChecksum(prefix, position + CHECKSUM_OFFSET);
        if (expected == INVALID_CHECKSUM) {
            return false;
        }
        long unsignedActual = 0L;
        long signedActual = 0L;
        for (int index = 0; index < BLOCK_SIZE; index++) {
            if (index >= CHECKSUM_OFFSET && index < CHECKSUM_OFFSET + CHECKSUM_SIZE) {
                unsignedActual += ' ';
                signedActual += ' ';
            } else {
                byte value = prefix.get(position + index);
                unsignedActual += Byte.toUnsignedInt(value);
                signedActual += value;
            }
        }
        return expected == unsignedActual || expected == signedActual;
    }

    /// Opens a TAR archive as a file system.
    @Override
    public TarArkivoFileSystem open(Path path) throws IOException {
        return TarArkivoFileSystem.open(path);
    }

    /// Opens a TAR archive as a file system with provider options.
    @Override
    public TarArkivoFileSystem open(Path path, ArchiveReadOptions options) throws IOException {
        return TarArkivoFileSystem.open(path, readOptions(options));
    }

    /// Creates a new path-backed TAR archive file system.
    @Override
    public TarArkivoFileSystem create(Path path, ArchiveCreateOptions options) throws IOException {
        return TarArkivoFileSystem.create(path, new TarArchiveOptions.Create(options, null));
    }

    /// Opens a complete-rewrite update of a path-backed TAR archive.
    @Override
    public TarArkivoFileSystem update(Path path, ArchiveUpdateOptions options) throws IOException {
        return TarArkivoFileSystem.update(path, new TarArchiveOptions.Update(
                options,
                null,
                TarArchiveOptions.DEFAULT_METADATA_CHARSET_DETECTOR
        ));
    }

    /// Opens a read-only TAR archive file system directly from one owned seekable channel.
    @Override
    public TarArkivoFileSystem open(SeekableByteChannel source) throws IOException {
        return TarArkivoFileSystem.open(source);
    }

    /// Opens a TAR archive file system directly from one owned seekable channel with options.
    @Override
    public TarArkivoFileSystem open(SeekableByteChannel source, ArchiveReadOptions options) throws IOException {
        return TarArkivoFileSystem.open(source, readOptions(options));
    }

    /// Opens a read-only TAR archive file system from a repeatable seekable channel source.
    @Override
    public TarArkivoFileSystem open(ArkivoSeekableChannelSource source) throws IOException {
        return TarArkivoFileSystem.open(source);
    }

    /// Opens a TAR archive file system from a channel source with options.
    @Override
    public TarArkivoFileSystem open(
            ArkivoSeekableChannelSource source,
            ArchiveReadOptions options
    ) throws IOException {
        return TarArkivoFileSystem.open(source, readOptions(options));
    }

    /// Opens a streaming TAR reader from an input stream.
    @Override
    public TarArkivoStreamingReader openStreamingReader(InputStream source) throws IOException {
        return TarArkivoStreamingReader.open(source);
    }

    /// Opens a streaming TAR reader from an input stream with options.
    @Override
    public TarArkivoStreamingReader openStreamingReader(
            InputStream source,
            ArchiveReadOptions options
    ) throws IOException {
        return TarArkivoStreamingReader.open(source, readOptions(options));
    }

    /// Opens a streaming TAR reader from a readable channel.
    @Override
    public TarArkivoStreamingReader openStreamingReader(ReadableByteChannel source) throws IOException {
        return TarArkivoStreamingReader.open(source);
    }

    /// Opens a streaming TAR reader from a readable channel with options.
    @Override
    public TarArkivoStreamingReader openStreamingReader(
            ReadableByteChannel source,
            ArchiveReadOptions options
    ) throws IOException {
        return TarArkivoStreamingReader.open(source, readOptions(options));
    }

    /// Opens a streaming TAR writer over an output stream.
    @Override
    public TarArkivoStreamingWriter openStreamingWriter(OutputStream output) {
        return TarArkivoStreamingWriter.open(output);
    }

    /// Opens a streaming TAR writer over an output stream with options.
    @Override
    public TarArkivoStreamingWriter openStreamingWriter(
            OutputStream output,
            ArchiveCreateOptions options
    ) throws IOException {
        return TarArkivoStreamingWriter.open(output, new TarArchiveOptions.Create(options, null));
    }

    /// Opens a streaming TAR writer over a writable channel.
    @Override
    public TarArkivoStreamingWriter openStreamingWriter(WritableByteChannel output) {
        return TarArkivoStreamingWriter.open(output);
    }

    /// Opens a streaming TAR writer over a writable channel with options.
    @Override
    public TarArkivoStreamingWriter openStreamingWriter(
            WritableByteChannel output,
            ArchiveCreateOptions options
    ) throws IOException {
        return TarArkivoStreamingWriter.open(output, new TarArchiveOptions.Create(options, null));
    }

    /// Returns whether the given absolute block is filled with zero bytes.
    private static boolean isZeroBlock(ByteBuffer prefix, int offset) {
        for (int index = 0; index < BLOCK_SIZE; index++) {
            if (prefix.get(offset + index) != 0) {
                return false;
            }
        }
        return true;
    }

    /// Parses the fixed-width octal TAR checksum field, or returns INVALID_CHECKSUM when invalid.
    private static long parseChecksum(ByteBuffer prefix, int offset) {
        int index = offset;
        int limit = offset + CHECKSUM_SIZE;
        while (index < limit && (prefix.get(index) == 0 || prefix.get(index) == ' ')) {
            index++;
        }
        long value = 0L;
        while (index < limit) {
            byte digit = prefix.get(index);
            if (digit < '0' || digit > '7') {
                break;
            }
            value = (value << 3) + digit - '0';
            index++;
        }
        while (index < limit && (prefix.get(index) == 0 || prefix.get(index) == ' ')) {
            index++;
        }
        return index == limit ? value : INVALID_CHECKSUM;
    }

    /// Applies TAR defaults to format-independent read options.
    private static TarArchiveOptions.Read readOptions(ArchiveReadOptions options) {
        return new TarArchiveOptions.Read(
                options,
                null,
                TarArchiveOptions.DEFAULT_METADATA_CHARSET_DETECTOR
        );
    }
}
