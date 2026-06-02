package org.glavo.arkivo.compress;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes a complete compression codec with encoder and decoder support.
@NotNullByDefault
public interface CompressionCodec extends CompressionFormat, Compressor, Decompressor {
}
