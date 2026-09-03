// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.cpio;

import org.glavo.arkivo.archive.ArchiveCreateOptions;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the public CPIO format descriptor and its generic streaming factories.
@NotNullByDefault
public final class CPIOArkivoFormatTest {
    /// Verifies descriptor metadata and every supported ASCII signature.
    @Test
    public void describesAndDetectsAsciiArchives() {
        CPIOArkivoFormat format = CPIOArkivoFormat.instance();

        assertSame(format, CPIOArkivoFormat.instance());
        assertEquals(CPIOArkivoFormat.NAME, format.name());
        assertEquals(List.of("cpio"), format.fileExtensions());
        assertEquals(26, format.probeSize());
        assertTrue(format instanceof org.glavo.arkivo.archive.ArkivoFormat.StreamingReader);
        assertTrue(format instanceof org.glavo.arkivo.archive.ArkivoFormat.StreamingWriter);

        for (String signature : List.of("070701", "070702", "070707")) {
            assertTrue(format.matches(ByteBuffer.wrap(signature.getBytes(StandardCharsets.US_ASCII))), signature);
        }
        assertFalse(format.matches(ByteBuffer.wrap("07070".getBytes(StandardCharsets.US_ASCII))));
        byte[] asciiSignature = "070701".getBytes(StandardCharsets.US_ASCII);
        for (int index = 0; index < asciiSignature.length; index++) {
            byte[] wrongSignature = asciiSignature.clone();
            wrongSignature[index] = index == 5 ? (byte) '3' : (byte) 'x';
            assertFalse(format.matches(ByteBuffer.wrap(wrongSignature)), "index " + index);
        }
    }

    /// Verifies old-binary probing in both byte orders without mutating the caller's buffer state.
    @Test
    public void detectsBinaryArchivesWithoutMutatingPrefix() {
        CPIOArkivoFormat format = CPIOArkivoFormat.instance();

        assertTrue(format.matches(ByteBuffer.wrap(new byte[]{0x71, (byte) 0xc7})));
        assertTrue(format.matches(ByteBuffer.wrap(new byte[]{(byte) 0xc7, 0x71})));
        assertFalse(format.matches(ByteBuffer.wrap(new byte[]{0x71, 0})));
        assertFalse(format.matches(ByteBuffer.wrap(new byte[]{0, (byte) 0xc7})));

        for (ByteOrder headerOrder : List.of(ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN)) {
            ByteBuffer prefix = binaryHeader(headerOrder, 2);
            prefix.order(headerOrder == ByteOrder.BIG_ENDIAN ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
            prefix.position(2).mark().position(3);
            int position = prefix.position();
            int limit = prefix.limit();
            ByteOrder order = prefix.order();

            assertTrue(format.matches(prefix), headerOrder.toString());
            assertEquals(position, prefix.position());
            assertEquals(limit, prefix.limit());
            assertSame(order, prefix.order());
            prefix.reset();
            assertEquals(2, prefix.position());

            assertFalse(format.matches(binaryHeader(headerOrder, 0)), headerOrder.toString());
            assertFalse(format.matches(binaryHeader(headerOrder, 1)), headerOrder.toString());
        }
    }

    /// Verifies every stream and channel factory can produce or consume a valid empty archive.
    @Test
    public void roundTripsEmptyArchivesThroughGenericFactories() throws IOException {
        CPIOArkivoFormat format = CPIOArkivoFormat.instance();

        ByteArrayOutputStream streamDefault = new ByteArrayOutputStream();
        try (var writer = format.openStreamingWriter(streamDefault)) {
            // Closing an empty writer emits the trailer entry and final block padding.
        }
        assertEmptyArchive(format, streamDefault.toByteArray());

        ByteArrayOutputStream streamConfigured = new ByteArrayOutputStream();
        try (var writer = format.openStreamingWriter(streamConfigured, ArchiveCreateOptions.DEFAULT)) {
            // Closing an empty writer emits the trailer entry and final block padding.
        }
        assertEmptyArchive(format, streamConfigured.toByteArray());

        ByteArrayOutputStream channelDefault = new ByteArrayOutputStream();
        try (var writer = format.openStreamingWriter(Channels.newChannel(channelDefault))) {
            // Closing an empty writer emits the trailer entry and final block padding.
        }
        assertEmptyArchive(format, channelDefault.toByteArray());

        ByteArrayOutputStream channelConfigured = new ByteArrayOutputStream();
        try (var writer = format.openStreamingWriter(
                Channels.newChannel(channelConfigured),
                ArchiveCreateOptions.DEFAULT
        )) {
            // Closing an empty writer emits the trailer entry and final block padding.
        }
        assertEmptyArchive(format, channelConfigured.toByteArray());
    }

    /// Verifies null prefixes fail at the public probing boundary.
    @Test
    @SuppressWarnings("DataFlowIssue")
    public void rejectsNullPrefix() {
        assertThrows(NullPointerException.class, () -> CPIOArkivoFormat.instance().matches((ByteBuffer) null));
    }

    /// Creates one full old-binary header at a nonzero buffer position.
    private static ByteBuffer binaryHeader(ByteOrder order, int nameSize) {
        ByteBuffer prefix = ByteBuffer.allocate(32);
        int position = 3;
        prefix.put(position, order == ByteOrder.BIG_ENDIAN ? (byte) 0x71 : (byte) 0xc7);
        prefix.put(position + 1, order == ByteOrder.BIG_ENDIAN ? (byte) 0xc7 : (byte) 0x71);
        prefix.duplicate().order(order).putShort(position + 20, (short) nameSize);
        prefix.position(position).limit(position + 26);
        return prefix;
    }

    /// Verifies an archive is accepted by all generic reader overloads and contains no entries.
    private static void assertEmptyArchive(CPIOArkivoFormat format, byte[] archive) throws IOException {
        assertTrue(archive.length > 0);
        try (var reader = format.openStreamingReader(new ByteArrayInputStream(archive))) {
            assertFalse(reader.next());
        }
        try (var reader = format.openStreamingReader(
                new ByteArrayInputStream(archive),
                ArchiveReadOptions.DEFAULT
        )) {
            assertFalse(reader.next());
        }
        try (var reader = format.openStreamingReader(Channels.newChannel(new ByteArrayInputStream(archive)))) {
            assertFalse(reader.next());
        }
        try (var reader = format.openStreamingReader(
                Channels.newChannel(new ByteArrayInputStream(archive)),
                ArchiveReadOptions.DEFAULT
        )) {
            assertFalse(reader.next());
        }
    }
}
