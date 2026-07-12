// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests 7z LZMA decoder pipeline setup behavior.
@NotNullByDefault
public final class SevenZipLZMADecoderTest {
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
