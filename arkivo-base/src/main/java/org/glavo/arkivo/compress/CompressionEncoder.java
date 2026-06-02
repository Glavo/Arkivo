// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.compress;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.WritableByteChannel;

/// Creates channels that encode uncompressed bytes into compressed bytes.
@NotNullByDefault
public interface CompressionEncoder {
    /// Opens an encoder that writes compressed bytes to the target channel.
    WritableByteChannel openEncoder(WritableByteChannel target) throws IOException;
}
