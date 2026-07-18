// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies ZIP64 boundaries that require archive structures too large for the default test tier.
@NotNullByDefault
public final class Zip64ScalabilityTest {
    /// Verifies that the streaming ZIP writer emits ZIP64 end records when the entry count overflows ZIP32 fields.
    @Test
    public void streamingWriterZip64EndRecordForManyEntries() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(output)) {
            for (int index = 0; index <= 0xffff; index++) {
                var entry = writer.beginDirectory("dir-" + index);
                entry.close();
            }
        }

        byte @Unmodifiable [] archive = output.toByteArray();
        ByteBuffer buffer = ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);
        int endOffset = archive.length - 22;
        int locatorOffset = endOffset - 20;
        int zip64EndOffset = Math.toIntExact(buffer.getLong(locatorOffset + 8));

        assertEquals(0x07064b50, buffer.getInt(locatorOffset));
        assertEquals(0x06064b50, buffer.getInt(zip64EndOffset));
        assertEquals(44L, buffer.getLong(zip64EndOffset + 4));
        assertEquals(0x1_0000L, buffer.getLong(zip64EndOffset + 24));
        assertEquals(0x1_0000L, buffer.getLong(zip64EndOffset + 32));
        assertEquals(0x06054b50, buffer.getInt(endOffset));
        assertEquals(0xffff, Short.toUnsignedInt(buffer.getShort(endOffset + 8)));
        assertEquals(0xffff, Short.toUnsignedInt(buffer.getShort(endOffset + 10)));
    }
}
