// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.glavo.arkivo.archive.internal.ArkivoStreamingSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies archive-core behavior when the optional archive-codec bridge is absent.
@NotNullByDefault
final class OptionalCompressionSupportTest {
    /// Verifies an unmatched source remains untouched and owned by its caller when no bridge is installed.
    @Test
    void returnsNullWithoutConsumingOrClosingSource() throws IOException {
        byte[] content = {1, 2, 3, 4};
        ReadableByteChannel source = Channels.newChannel(new ByteArrayInputStream(content));

        ArkivoStreamingSource transformed = OptionalCompressionSupport.probe(
                source,
                ArchiveReadOptions.DEFAULT
        );

        assertNull(transformed);
        assertTrue(source.isOpen());
        ByteBuffer target = ByteBuffer.allocate(content.length);
        assertEquals(content.length, source.read(target));
        assertArrayEquals(content, target.array());
        source.close();
    }

    /// Verifies the generic streaming factory closes an unrecognized source in a core-only deployment.
    @Test
    void closesUnrecognizedPublicStreamingSource() {
        ReadableByteChannel source = Channels.newChannel(new ByteArrayInputStream(new byte[]{1, 2, 3, 4}));

        IOException failure = assertThrows(
                IOException.class,
                () -> ArkivoFormats.openStreamingReader(source, ArchiveReadOptions.DEFAULT)
        );

        assertEquals("Unrecognized archive format", failure.getMessage());
        assertFalse(source.isOpen());
    }

    /// Verifies argument validation does not take ownership of an otherwise valid source.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void validatesArgumentsBeforeBridgeDispatch() throws IOException {
        ReadableByteChannel source = Channels.newChannel(new ByteArrayInputStream(new byte[0]));

        assertThrows(
                NullPointerException.class,
                () -> OptionalCompressionSupport.probe(null, ArchiveReadOptions.DEFAULT)
        );
        assertThrows(NullPointerException.class, () -> OptionalCompressionSupport.probe(source, null));
        assertTrue(source.isOpen());

        source.close();
    }
}
