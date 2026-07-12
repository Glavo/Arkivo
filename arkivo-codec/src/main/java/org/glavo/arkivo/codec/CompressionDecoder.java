// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Reads compressed bytes from a backing channel and exposes decoded bytes.
@NotNullByDefault
public interface CompressionDecoder extends ReadableByteChannel {
    /// Decodes bytes into the target and reports progress and end-of-input state.
    default CodecResult decode(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        long inputBefore = inputBytes();
        long outputBefore = outputBytes();
        int read = read(target);
        CodecStatus status = read < 0 ? CodecStatus.END_OF_INPUT : CodecStatus.ACTIVE;
        return new CodecResult(inputBytes() - inputBefore, outputBytes() - outputBefore, status);
    }

    /// Returns the number of compressed bytes consumed from the backing channel.
    long inputBytes();

    /// Returns the number of uncompressed bytes returned to callers.
    long outputBytes();
}
