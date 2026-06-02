package org.glavo.arkivo.compress;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;

/// Creates channels that decode compressed bytes into uncompressed bytes.
@NotNullByDefault
public interface CompressionDecoder {
    /// Opens a decoder that reads compressed bytes from the source channel.
    ReadableByteChannel openDecoder(ReadableByteChannel source) throws IOException;
}
