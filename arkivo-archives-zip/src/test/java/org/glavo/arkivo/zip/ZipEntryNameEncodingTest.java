// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests ZIP entry name encoding policies.
@NotNullByDefault
public final class ZipEntryNameEncodingTest {
    /// Verifies that the standard policy is parsed by name.
    @Test
    public void standardPolicy() {
        assertEquals(ZipEntryNameEncoding.standard(), ZipEntryNameEncoding.parse("standard"));
    }

    /// Verifies that a charset policy is parsed by charset name.
    @Test
    public void charsetPolicy() {
        ZipEntryNameEncoding encoding = ZipEntryNameEncoding.parse("utf-8");

        assertEquals(ZipEntryNameEncodingMode.CHARSET, encoding.mode());
        assertEquals(StandardCharsets.UTF_8, encoding.charset());
    }

    /// Verifies that an automatic policy parses a custom candidate list.
    @Test
    public void autoPolicyWithCandidates() {
        ZipEntryNameEncoding encoding = ZipEntryNameEncoding.parse("auto:gb18030,ibm437");

        assertEquals(ZipEntryNameEncodingMode.AUTO, encoding.mode());
        assertEquals(List.of(Charset.forName("GB18030"), ZipEntryNameEncoding.cp437()), encoding.candidates());
    }

    /// Verifies that automatic policies reject empty candidate lists.
    @Test
    public void emptyAutoCandidates() {
        assertThrows(IllegalArgumentException.class, () -> ZipEntryNameEncoding.parse("auto:"));
    }
}
