// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.cpio;

import org.glavo.arkivo.archive.ArchiveCreateOptions;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoStreamingReaderFormat;
import org.glavo.arkivo.archive.ArkivoStreamingWriterFormat;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.List;
import java.util.Objects;

/// Describes Arkivo's forward-only CPIO support.
///
/// This descriptor probes all four supported header dialects and opens streaming readers and writers. Generic creation
/// options retain the new ASCII dialect, UTF-8 metadata, big-endian old-binary word order, and 512-byte block size.
/// Factory methods transfer source or target ownership to the returned reader or writer, which closes it with the
/// archive cursor.
@NotNullByDefault
public final class CPIOArkivoFormat implements ArkivoStreamingReaderFormat, ArkivoStreamingWriterFormat {
    /// The stable CPIO format name.
    public static final String NAME = "cpio";

    /// The shared CPIO format instance.
    private static final CPIOArkivoFormat INSTANCE = new CPIOArkivoFormat();

    /// Creates a service-discoverable CPIO format descriptor.
    public CPIOArkivoFormat() {
    }

    /// Returns the shared CPIO format descriptor.
    ///
    /// @return the process-wide immutable descriptor
    public static CPIOArkivoFormat instance() {
        return INSTANCE;
    }

    /// Returns the stable CPIO format name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns common CPIO archive extensions.
    @Override
    public @Unmodifiable List<String> fileExtensions() {
        return List.of("cpio");
    }

    /// Returns the full old-binary header size used to reduce false-positive binary signature matches.
    @Override
    public int probeSize() {
        return 26;
    }

    /// Returns whether the prefix starts with a recognized CPIO header signature.
    @Override
    public boolean matches(ByteBuffer prefix) {
        Objects.requireNonNull(prefix, "prefix");
        int position = prefix.position();
        if (prefix.remaining() >= 2) {
            int first = Byte.toUnsignedInt(prefix.get(position));
            int second = Byte.toUnsignedInt(prefix.get(position + 1));
            if (first == 0x71 && second == 0xc7 || first == 0xc7 && second == 0x71) {
                if (prefix.remaining() < probeSize()) {
                    return true;
                }
                ByteOrder order = first == 0x71 ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
                int nameSize = Short.toUnsignedInt(prefix.duplicate().order(order).getShort(position + 20));
                return nameSize >= 2;
            }
        }
        if (prefix.remaining() < 6) {
            return false;
        }
        return prefix.get(position) == '0'
                && prefix.get(position + 1) == '7'
                && prefix.get(position + 2) == '0'
                && prefix.get(position + 3) == '7'
                && prefix.get(position + 4) == '0'
                && (prefix.get(position + 5) == '1'
                || prefix.get(position + 5) == '2'
                || prefix.get(position + 5) == '7');
    }

    /// Opens a streaming CPIO reader from an input stream.
    @Override
    public CPIOArkivoStreamingReader openStreamingReader(InputStream source) {
        return CPIOArkivoStreamingReader.open(source);
    }

    /// Opens a configured streaming CPIO reader from an input stream.
    @Override
    public CPIOArkivoStreamingReader openStreamingReader(InputStream source, ArchiveReadOptions options) {
        return CPIOArkivoStreamingReader.open(
                source,
                new CPIOArchiveOptions.Read(options, CPIOArchiveOptions.DEFAULT_METADATA_CHARSET_DETECTOR)
        );
    }

    /// Opens a streaming CPIO reader from a readable channel.
    @Override
    public CPIOArkivoStreamingReader openStreamingReader(ReadableByteChannel source) {
        return CPIOArkivoStreamingReader.open(source);
    }

    /// Opens a configured streaming CPIO reader from a readable channel.
    @Override
    public CPIOArkivoStreamingReader openStreamingReader(
            ReadableByteChannel source,
            ArchiveReadOptions options
    ) {
        return CPIOArkivoStreamingReader.open(
                source,
                new CPIOArchiveOptions.Read(options, CPIOArchiveOptions.DEFAULT_METADATA_CHARSET_DETECTOR)
        );
    }

    /// Opens a streaming CPIO writer over an output stream.
    @Override
    public CPIOArkivoStreamingWriter openStreamingWriter(OutputStream target) {
        return CPIOArkivoStreamingWriter.open(target);
    }

    /// Opens a configured streaming CPIO writer over an output stream.
    @Override
    public CPIOArkivoStreamingWriter openStreamingWriter(OutputStream target, ArchiveCreateOptions options) {
        return CPIOArkivoStreamingWriter.open(
                target,
                CPIOArchiveOptions.CREATE_DEFAULTS.withCommon(options)
        );
    }

    /// Opens a streaming CPIO writer over a writable channel.
    @Override
    public CPIOArkivoStreamingWriter openStreamingWriter(WritableByteChannel target) {
        return CPIOArkivoStreamingWriter.open(target);
    }

    /// Opens a configured streaming CPIO writer over a writable channel.
    @Override
    public CPIOArkivoStreamingWriter openStreamingWriter(
            WritableByteChannel target,
            ArchiveCreateOptions options
    ) {
        return CPIOArkivoStreamingWriter.open(
                target,
                CPIOArchiveOptions.CREATE_DEFAULTS.withCommon(options)
        );
    }
}
