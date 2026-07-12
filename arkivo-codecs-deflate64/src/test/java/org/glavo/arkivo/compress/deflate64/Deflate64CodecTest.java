// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.compress.deflate64;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the public Deflate64 codec contract.
@NotNullByDefault
final class Deflate64CodecTest {
    /// Verifies that Deflate64 exposes its implemented direction and rejects compression.
    @Test
    void exposesDecompressionOnly() throws IOException {
        Deflate64Codec codec = new Deflate64Codec();

        assertFalse(codec.canCompress());
        assertTrue(codec.canDecompress());
        try (var target = Channels.newChannel(OutputStream.nullOutputStream())) {
            assertThrows(IOException.class, () -> codec.compressTo(target));
        }
    }
}
