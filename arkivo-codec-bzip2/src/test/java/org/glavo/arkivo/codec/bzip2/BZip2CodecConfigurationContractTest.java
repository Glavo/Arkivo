// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bzip2;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/// Verifies the documented scope of BZip2 decoder limits.
@NotNullByDefault
final class BZip2CodecConfigurationContractTest {
    /// Verifies BZip2 retains but does not interpret window and memory limits when constructing a decoder.
    @Test
    void treatsWindowAndMemoryLimitsAsUnenforcedMetadata() throws IOException {
        byte[] content = {1, 2, 3, 4, 5};
        ByteBuffer encoded = BZip2Codec.DEFAULT.compress(ByteBuffer.wrap(content));
        BZip2Codec restricted = BZip2Codec.DEFAULT
                .withMaximumOutputSize(content.length)
                .withMaximumWindowSize(0L)
                .withMaximumMemorySize(0L);
        ByteBuffer decoded = ByteBuffer.allocate(content.length);

        restricted.decompress(encoded, decoded);

        assertArrayEquals(content, decoded.array());
    }
}
