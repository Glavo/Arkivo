// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.compress;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

/// Describes a compression codec and its supported operations.
@NotNullByDefault
public interface CompressionCodec {
    /// Returns the stable codec name.
    String name();

    /// Returns whether this codec can compress uncompressed bytes.
    boolean canCompress();

    /// Returns whether this codec can decompress compressed bytes.
    boolean canDecompress();

    /// Opens a channel that accepts uncompressed bytes and writes compressed bytes to the target channel.
    WritableByteChannel compressTo(WritableByteChannel target) throws IOException;

    /// Opens a stream that accepts uncompressed bytes and writes compressed bytes to the target stream.
    default OutputStream compressTo(OutputStream target) throws IOException {
        return Channels.newOutputStream(compressTo(Channels.newChannel(target)));
    }

    /// Opens a channel that reads compressed bytes from the source channel and exposes uncompressed bytes.
    ReadableByteChannel decompressFrom(ReadableByteChannel source) throws IOException;

    /// Opens a stream that reads compressed bytes from the source stream and exposes uncompressed bytes.
    default InputStream decompressFrom(InputStream source) throws IOException {
        return Channels.newInputStream(decompressFrom(Channels.newChannel(source)));
    }
}
