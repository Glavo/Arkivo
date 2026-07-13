// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests 7z LZMA decoder pipeline setup behavior.
@NotNullByDefault
public final class SevenZipLZMADecoderTest {
    /// Verifies the official modern BCJ method IDs are recognized as supported decoders.
    @Test
    public void recognizesModernBcjMethodIds() {
        assertTrue(SevenZipLZMADecoder.isSupported(new byte[]{0x0a}));
        assertTrue(SevenZipLZMADecoder.isSupported(new byte[]{0x0b}));
    }

    /// Verifies modern BCJ properties reject start offsets that violate instruction alignment.
    @Test
    public void modernBcjFiltersRejectMisalignedStartOffsets() {
        assertThrows(
                IOException.class,
                () -> SevenZipLZMADecoder.openARM64Filter(
                        new ByteArrayInputStream(new byte[0]),
                        new byte[]{0x02, 0x00, 0x00, 0x00}
                )
        );
        assertThrows(
                IOException.class,
                () -> SevenZipLZMADecoder.openRiscVFilter(
                        new ByteArrayInputStream(new byte[0]),
                        new byte[]{0x01, 0x00, 0x00, 0x00}
                )
        );
    }

    /// Verifies current and legacy Zstandard coder property lengths are accepted.
    @Test
    public void zstandardAcceptsCompatiblePropertyLengths() throws IOException {
        for (int propertyLength : new int[]{0, 1, 3, 5}) {
            SevenZipLZMADecoder.openZstandard(
                    new ByteArrayInputStream(new byte[0]),
                    new byte[propertyLength],
                    0
            ).close();
        }
    }

    /// Verifies unknown Zstandard coder property layouts are rejected.
    @Test
    public void zstandardRejectsUnknownPropertyLengths() {
        IOException exception = assertThrows(
                IOException.class,
                () -> SevenZipLZMADecoder.openZstandard(
                        new ByteArrayInputStream(new byte[0]),
                        new byte[2],
                        0
                )
        );
        assertTrue(exception.getMessage().contains("zero, one, three, or five bytes"));
    }

    /// Verifies that decoder setup failures are not replaced by cleanup failures.
    @Test
    public void openFolderPreservesSetupFailureWhenInputCloseFails() {
        SevenZipFolderMethod method = SevenZipFolderMethod.single(
                SevenZipLZMADecoder.LZMA_METHOD_ID,
                new byte[0],
                0
        );

        IOException exception = assertThrows(
                IOException.class,
                () -> SevenZipLZMADecoder.openFolder(new CloseFailingInputStream(), method, 0)
        );
        assertEquals(true, exception.getMessage().contains("7z LZMA coder properties must contain five bytes"));
        assertEquals(1, exception.getSuppressed().length);
        assertEquals("close failed", exception.getSuppressed()[0].getMessage());
    }

    /// Verifies that runtime cleanup failures do not replace decoder setup failures.
    @Test
    public void openFolderPreservesSetupFailureWhenInputCloseFailsAtRuntime() {
        SevenZipFolderMethod method = SevenZipFolderMethod.single(
                SevenZipLZMADecoder.LZMA_METHOD_ID,
                new byte[0],
                0
        );

        IOException exception = assertThrows(
                IOException.class,
                () -> SevenZipLZMADecoder.openFolder(new RuntimeCloseFailingInputStream(), method, 0)
        );
        assertEquals(true, exception.getMessage().contains("7z LZMA coder properties must contain five bytes"));
        assertEquals(1, exception.getSuppressed().length);
        assertEquals("close failed", exception.getSuppressed()[0].getMessage());
    }

    /// Input stream that fails when closed.
    @NotNullByDefault
    private static final class CloseFailingInputStream extends ByteArrayInputStream {
        /// Creates an empty close-failing input stream.
        private CloseFailingInputStream() {
            super(new byte[0]);
        }

        /// Always fails when closed.
        @Override
        public void close() throws IOException {
            throw new IOException("close failed");
        }
    }

    /// Input stream that fails at runtime when closed.
    @NotNullByDefault
    private static final class RuntimeCloseFailingInputStream extends ByteArrayInputStream {
        /// Creates an empty runtime close-failing input stream.
        private RuntimeCloseFailingInputStream() {
            super(new byte[0]);
        }

        /// Always fails at runtime when closed.
        @Override
        public void close() {
            throw new IllegalStateException("close failed");
        }
    }
}
