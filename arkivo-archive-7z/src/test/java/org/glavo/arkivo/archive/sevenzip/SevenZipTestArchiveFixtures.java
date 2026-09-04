// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

/// Creates small in-memory 7z archives shared by focused contract tests.
@NotNullByDefault
final class SevenZipTestArchiveFixtures {
    /// Creates no instances.
    private SevenZipTestArchiveFixtures() {
    }

    /// Returns a new minimal 7z archive with an empty next header.
    static byte[] minimalArchive() {
        ByteBuffer buffer = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[]{'7', 'z', (byte) 0xbc, (byte) 0xaf, 0x27, 0x1c});
        buffer.put((byte) 0);
        buffer.put((byte) 4);
        buffer.putInt(0);
        buffer.putLong(0L);
        buffer.putLong(0L);
        buffer.putInt(0);

        CRC32 crc32 = new CRC32();
        crc32.update(buffer.array(), 12, 20);
        buffer.putInt(8, (int) crc32.getValue());
        return buffer.array();
    }
}
