// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests streaming 7z publication through transactional split-volume targets.
@NotNullByDefault
public final class SevenZipSplitStreamingWriterIntegrationTest {
    /// Verifies that a streaming writer publishes bounded output through a transactional volume target.
    @Test
    public void createsSplitArchiveWithStreamingWriter() throws IOException {
        byte[] content = new byte[512];
        for (int index = 0; index < content.length; index++) {
            content[index] = (byte) (index * 13);
        }
        RecordingVolumeTarget target = new RecordingVolumeTarget(-1L, false);

        try (SevenZipArkivoStreamingWriter writer = SevenZipArkivoStreamingWriter.open(target, 64L)) {
            var entry = writer.beginFile("content.bin");
            entry.openOutputStream().write(content);
            assertEquals(0, target.openOutputCount());
        }

        byte[][] volumes = target.committedVolumes();
        assertTrue(volumes.length > 1);
        assertTrue(target.allOpenedChannelsClosed());
        for (byte[] volume : volumes) {
            assertTrue(volume.length > 0 && volume.length <= 64);
        }
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(
                SevenZipTestArchiveFixtures.volumeSource(volumes)
        )) {
            assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/content.bin")));
        }
    }

    /// Verifies that a streaming target failure rolls back unpublished volumes and permits close retry.
    @Test
    public void streamingWriterTargetFailureRollsBack() throws IOException {
        RecordingVolumeTarget target = new RecordingVolumeTarget(1L, false);
        SevenZipArchiveOptions.Create options = SevenZipArchiveOptions.CREATE_DEFAULTS
                .withPasswordProvider(ArkivoPasswordProvider.fixed(
                        "rollback-password".getBytes(StandardCharsets.UTF_16LE)
                ))
                .withEncryptHeaders(true);
        SevenZipArkivoStreamingWriter writer = SevenZipArkivoStreamingWriter.open(target, 64L, options);
        var entry = writer.beginFile("content.bin");
        try (OutputStream output = entry.openOutputStream()) {
            output.write(new byte[512]);
        }

        IOException exception = assertThrows(IOException.class, writer::close);

        assertEquals("volume open failed", exception.getMessage());
        assertEquals(1, target.rollbackCount());
        assertEquals(0, target.committedVolumes().length);
        assertTrue(target.allOpenedChannelsClosed());
        writer.close();
    }

    /// Verifies that split streaming writers reject non-positive volume sizes.
    @Test
    public void rejectsNonPositiveSplitSize() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipArkivoStreamingWriter.open(new RecordingVolumeTarget(-1L, false), 0L)
        );
    }
}
