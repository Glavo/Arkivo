// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests the closed ZIP compression method set and numeric method identifier mapping.
@NotNullByDefault
public final class ZipMethodTest {
    /// Verifies that every recognized compression method round-trips through its numeric identifier.
    @Test
    public void recognizedIdentifiersRoundTrip() {
        for (ZipMethod method : ZipMethod.values()) {
            assertSame(method, ZipMethod.fromId(method.id()));
        }
    }

    /// Verifies that valid but unrecognized ZIP compression method identifiers return no enum value.
    @Test
    public void unknownIdentifiersReturnNull() {
        assertNull(ZipMethod.fromId(1));
        assertNull(ZipMethod.fromId(99));
        assertNull(ZipMethod.fromId(0xffff));
    }

    /// Verifies that values outside the unsigned 16-bit ZIP method field are rejected.
    @Test
    public void outOfRangeIdentifiersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ZipMethod.fromId(-1));
        assertThrows(IllegalArgumentException.class, () -> ZipMethod.fromId(0x1_0000));
    }

    /// Verifies that metadata-only entries reject compression before mutating their pending attributes.
    @Test
    public void metadataOnlyEntriesRejectCompressionImmediately() throws IOException {
        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(new ByteArrayOutputStream())) {
            var directory = writer.beginDirectory("directory");
            ZipArkivoEntryAttributeView directoryView = Objects.requireNonNull(
                    directory.attributeView(ZipArkivoEntryAttributeView.class)
            );
            assertThrows(UnsupportedOperationException.class, () -> directoryView.setMethod(ZipMethod.DEFLATED));
            assertSame(ZipMethod.STORED, directoryView.readAttributes().compressionMethod());
            directory.close();

            var symbolicLink = writer.beginSymbolicLink("link", "target");
            ZipArkivoEntryAttributeView symbolicLinkView = Objects.requireNonNull(
                    symbolicLink.attributeView(ZipArkivoEntryAttributeView.class)
            );
            assertThrows(UnsupportedOperationException.class, () -> symbolicLinkView.setMethod(ZipMethod.DEFLATED));
            assertSame(ZipMethod.STORED, symbolicLinkView.readAttributes().compressionMethod());
            symbolicLink.close();
        }
    }
}
