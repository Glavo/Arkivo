// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies immutable encoding-operation options and source-size validation.
@NotNullByDefault
final class EncodingOptionsTest {
    /// Verifies default, factory, wither, and invalid source-size behavior.
    @Test
    void validatesSourceSizeMetadata() {
        assertEquals(CompressionCodec.UNKNOWN_SIZE, EncodingOptions.DEFAULT.sourceSize());
        assertSame(
                EncodingOptions.DEFAULT,
                EncodingOptions.ofSourceSize(CompressionCodec.UNKNOWN_SIZE)
        );
        assertSame(
                EncodingOptions.DEFAULT,
                EncodingOptions.DEFAULT.withSourceSize(CompressionCodec.UNKNOWN_SIZE)
        );

        EncodingOptions sized = EncodingOptions.ofSourceSize(7L);
        assertEquals(7L, sized.sourceSize());
        assertSame(sized, sized.withSourceSize(7L));
        assertEquals(new EncodingOptions(8L), sized.withSourceSize(8L));
        assertThrows(
                IllegalArgumentException.class,
                () -> EncodingOptions.ofSourceSize(CompressionCodec.UNKNOWN_SIZE - 1L)
        );
    }

    /// Verifies seekable source metadata and automatic frame-size policy.
    @Test
    void validatesSeekableFramePolicy() {
        SeekableEncodingOptions defaults = SeekableEncodingOptions.DEFAULT;
        assertEquals(CompressionCodec.UNKNOWN_SIZE, defaults.sourceSize());
        assertEquals(SeekableEncodingOptions.DEFAULT_MAXIMUM_FRAME_SIZE, defaults.maximumFrameSize());
        assertSame(
                defaults,
                SeekableEncodingOptions.ofMaximumFrameSize(
                        SeekableEncodingOptions.DEFAULT_MAXIMUM_FRAME_SIZE
                )
        );

        SeekableEncodingOptions configured = SeekableEncodingOptions.ofMaximumFrameSize(4096)
                .withSourceSize(8193L);
        assertEquals(8193L, configured.sourceSize());
        assertEquals(4096, configured.maximumFrameSize());
        assertSame(configured, configured.withSourceSize(8193L));
        assertSame(configured, configured.withMaximumFrameSize(4096));
        assertEquals(
                new SeekableEncodingOptions(8193L, 2048),
                configured.withMaximumFrameSize(2048)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new SeekableEncodingOptions(CompressionCodec.UNKNOWN_SIZE - 1L, 1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> SeekableEncodingOptions.ofMaximumFrameSize(0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> configured.withMaximumFrameSize(-1)
        );
    }
}
