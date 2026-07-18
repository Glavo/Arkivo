// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests 7z metadata header parsing behavior.
@NotNullByDefault
public final class SevenZipHeaderParserTest {
    /// Verifies that empty streams may omit unpack metadata before empty substream metadata.
    @Test
    public void emptyStreamsAllowSubStreamsInfoWithoutUnpackInfo() throws IOException {
        assertEquals(
                List.of(),
                SevenZipHeaderParser.parseEntries(new byte[]{0x01, 0x04, 0x08, 0x00, 0x00, 0x00})
        );
    }

    /// Verifies that empty archive headers may contain redundant top-level end markers.
    @Test
    public void emptyArchiveAllowsTrailingEndMarkerPadding() throws IOException {
        assertEquals(
                List.of(),
                SevenZipHeaderParser.parseEntries(new byte[]{0x01, 0x04, 0x08, 0x00, 0x00, 0x00, 0x00})
        );
    }

    /// Verifies that top-level end-marker padding does not make arbitrary trailing metadata acceptable.
    @Test
    public void emptyArchiveRejectsNonEndTrailingData() {
        IOException exception = assertThrows(
                IOException.class,
                () -> SevenZipHeaderParser.parseEntries(
                        new byte[]{0x01, 0x04, 0x08, 0x00, 0x00, 0x00, 0x01}
                )
        );

        assertEquals("Trailing bytes in 7z header: [1]", exception.getMessage());
    }

    /// Verifies that packed streams require unpack metadata before substream metadata.
    @Test
    public void subStreamsInfoBeforeUnpackInfoIsRejected() {
        IOException exception = assertThrows(
                IOException.class,
                () -> SevenZipHeaderParser.parseEntries(new byte[]{
                        0x01,
                        0x04,
                        0x06, 0x00, 0x01, 0x09, 0x01, 0x00,
                        0x08, 0x00,
                        0x00,
                        0x00
                })
        );

        assertEquals("7z substreams appeared before folders", exception.getMessage());
    }

    /// Verifies that unpack metadata cannot be appended after an empty substream block.
    @Test
    public void unpackInfoAfterSubStreamsInfoIsRejected() {
        IOException exception = assertThrows(
                IOException.class,
                () -> SevenZipHeaderParser.parseEntries(new byte[]{
                        0x01,
                        0x04,
                        0x08, 0x00,
                        0x07, 0x00,
                        0x00,
                        0x00
                })
        );

        assertEquals("7z folders appeared after substreams", exception.getMessage());
    }

    /// Verifies unknown top-level properties are rejected as checked input failures.
    @Test
    public void unknownHeaderPropertyIsCheckedFailure() {
        IOException exception = assertThrows(
                IOException.class,
                () -> SevenZipHeaderParser.parseEntries(new byte[]{0x01, 0x7f})
        );

        assertEquals("Unsupported 7z header property: 0x7f", exception.getMessage());
    }

    /// Verifies encoded headers without archive stream access fail through the checked parser contract.
    @Test
    public void encodedHeaderWithoutArchiveAccessIsCheckedFailure() {
        IOException exception = assertThrows(
                IOException.class,
                () -> SevenZipHeaderParser.parseEntries(new byte[]{0x17})
        );

        assertEquals("7z encoded headers require archive stream access", exception.getMessage());
    }

    /// Verifies coder flags and methods controlled by archive bytes cannot leak runtime failures.
    @Test
    public void unsupportedCoderMetadataIsCheckedFailure() {
        IOException flagsFailure = assertThrows(
                IOException.class,
                () -> SevenZipHeaderParser.parseEntries(
                        encodedHeaderWithCoder(0xc1, 0x00),
                        (offset, size) -> new ByteArrayInputStream(new byte[0])
                )
        );
        assertEquals("Unsupported 7z coder flags: 0xc1", flagsFailure.getMessage());

        IOException methodFailure = assertThrows(
                IOException.class,
                () -> SevenZipHeaderParser.parseEntries(
                        encodedHeaderWithCoder(0x01, 0x7e),
                        (offset, size) -> new ByteArrayInputStream(new byte[0])
                )
        );
        assertEquals("Unsupported 7z coder method: [126]", methodFailure.getMessage());
    }

    /// Returns an encoded-header descriptor with caller-selected coder bytes.
    private static byte[] encodedHeaderWithCoder(int flags, int method) {
        return new byte[]{
                0x17,
                0x06, 0x00, 0x01, 0x09, 0x01, 0x00,
                0x07, 0x0b, 0x01, 0x00, 0x01, (byte) flags, (byte) method, 0x0c, 0x01, 0x00,
                0x00
        };
    }

    /// Verifies that encoded-header read failures are not replaced by runtime cleanup failures.
    @Test
    public void encodedHeaderReadFailureSuppressesRuntimeCloseFailure() {
        IOException exception = assertThrows(
                IOException.class,
                () -> SevenZipHeaderParser.parseEntries(
                        truncatedEncodedCopyHeader(),
                        (offset, size) -> new RuntimeCloseFailingInputStream()
                )
        );

        assertEquals("Unexpected end of 7z encoded header", exception.getMessage());
        assertEquals(1, exception.getSuppressed().length);
        assertEquals(IllegalStateException.class, exception.getSuppressed()[0].getClass());
        assertEquals("close failed", exception.getSuppressed()[0].getMessage());
    }

    /// Returns an encoded-header descriptor for a one-byte Copy stream.
    private static byte[] truncatedEncodedCopyHeader() {
        return new byte[]{
                0x17,
                0x06, 0x00, 0x01, 0x09, 0x01, 0x00,
                0x07, 0x0b, 0x01, 0x00, 0x01, 0x01, 0x00, 0x0c, 0x01, 0x00,
                0x00
        };
    }

    /// Input stream that reaches EOF immediately and fails at runtime when closed.
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
