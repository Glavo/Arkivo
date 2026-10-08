// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the basic archive metadata decoder contract.
@NotNullByDefault
public final class ArchiveMetadataDecoderTest {
    /// Verifies that array invocations delegate through an independent read-only buffer.
    @Test
    public void arrayDelegatesToReadOnlyBuffer() throws Exception {
        ArchiveMetadataDecoder metadataDecoder = bytes -> {
            assertTrue(bytes.isReadOnly());
            assertEquals(2, bytes.remaining());
            assertThrows(ReadOnlyBufferException.class, () -> bytes.put(0, (byte) 0));
            bytes.position(bytes.limit());
            return "decoded";
        };

        byte[] source = {1, 2};
        assertEquals("decoded", metadataDecoder.decode(source));
        assertArrayEquals(new byte[]{1, 2}, source);
    }

    /// Verifies that a charset decoder accepts both basic byte representations and empty input.
    @Test
    public void charsetDecoder() throws Exception {
        ArchiveMetadataDecoder metadataDecoder =
                ArchiveMetadataDecoder.forCharset(StandardCharsets.UTF_16LE);

        assertEquals("AB", metadataDecoder.decode(new byte[]{65, 0, 66, 0}));
        assertEquals("", metadataDecoder.decode(ByteBuffer.allocate(0)));
    }

    /// Preserves input range, contents and mark for heap, direct, sliced and read-only buffers, even on failure.
    @Test
    public void charsetDecoderPreservesBuffers() throws IOException {
        ArchiveMetadataDecoder decoder = ArchiveMetadataDecoder.forCharset(StandardCharsets.UTF_8);
        for (int kind = 0; kind < 4; kind++) {
            ByteBuffer buffer = kind == 1 ? ByteBuffer.allocateDirect(8) : ByteBuffer.allocate(8);
            if (kind == 2) {
                buffer.position(2);
                buffer = buffer.slice();
            }
            buffer.put(new byte[]{1, 'A', (byte) 0xc3, (byte) 0xa9, (byte) 0xff, 2});
            buffer.position(1).limit(4).mark();
            if (kind == 3) buffer = buffer.asReadOnlyBuffer();
            assertEquals("A\u00e9", decoder.decode(buffer));
            assertEquals(1, buffer.position());
            assertEquals(4, buffer.limit());
            buffer.reset();
            buffer.limit(5);
            ByteBuffer source = buffer;
            assertThrows(CharacterCodingException.class, () -> decoder.decode(source));
            assertEquals(1, buffer.position());
            assertEquals(5, buffer.limit());
            buffer.reset();
            assertEquals('A', buffer.get(1));
            assertEquals((byte) 0xff, buffer.get(4));
        }
    }

    /// Rejects malformed and unmappable input instead of silently replacing it.
    @Test
    public void charsetDecoderIsStrict() {
        assertThrows(CharacterCodingException.class,
                () -> ArchiveMetadataDecoder.forCharset(StandardCharsets.UTF_8).decode(new byte[]{(byte) 0xc3}));
        assertThrows(CharacterCodingException.class,
                () -> ArchiveMetadataDecoder.forCharset(StandardCharsets.US_ASCII).decode(new byte[]{(byte) 0xff}));
        assertThrows(CharacterCodingException.class,
                () -> ArchiveMetadataDecoder.forCharset(StandardCharsets.UTF_16LE).decode(new byte[]{0, (byte) 0xd8}));
    }

    /// Leaves fallback decisions and checked failure identity with the configured decoder.
    @Test
    public void customPolicyControlsConversion() throws IOException {
        ArchiveMetadataDecoder utf8 = ArchiveMetadataDecoder.forCharset(StandardCharsets.UTF_8);
        ArchiveMetadataDecoder latin1 = ArchiveMetadataDecoder.forCharset(StandardCharsets.ISO_8859_1);
        ArchiveMetadataDecoder fallback = bytes -> {
            try {
                return utf8.decode(bytes);
            } catch (CharacterCodingException exception) {
                return latin1.decode(bytes);
            }
        };
        assertEquals("\u00e9", fallback.decode(new byte[]{(byte) 0xc3, (byte) 0xa9}));
        assertEquals("\u00e9", fallback.decode(new byte[]{(byte) 0xe9}));
        IOException failure = new IOException("Rejected metadata");
        ArchiveMetadataDecoder rejecting = bytes -> { throw failure; };
        assertSame(failure, assertThrows(IOException.class, () -> rejecting.decode(new byte[]{1})));
    }

    /// Rejects null arguments and an invalid implementation's null result.
    @Test
    public void rejectsNulls() {
        assertThrows(NullPointerException.class, () -> ArchiveMetadataDecoder.forCharset(null));
        ArchiveMetadataDecoder decoder = ArchiveMetadataDecoder.forCharset(StandardCharsets.UTF_8);
        assertThrows(NullPointerException.class, () -> decoder.decode((byte[]) null));
        assertThrows(NullPointerException.class, () -> decoder.decode((ByteBuffer) null));
        ArchiveMetadataDecoder invalid = bytes -> null;
        assertThrows(NullPointerException.class, () -> invalid.decode(new byte[0]));
    }

    /// Shares a charset policy between invocations without sharing mutable charset-decoder state.
    @Test
    public void charsetDecoderSupportsConcurrentCalls() {
        ArchiveMetadataDecoder decoder = ArchiveMetadataDecoder.forCharset(StandardCharsets.UTF_8);
        IntStream.range(0, 256).parallel().forEach(index -> {
            String value = "name-\u00e9-" + index;
            try {
                assertEquals(value, decoder.decode(value.getBytes(StandardCharsets.UTF_8)));
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
        });
    }
}
