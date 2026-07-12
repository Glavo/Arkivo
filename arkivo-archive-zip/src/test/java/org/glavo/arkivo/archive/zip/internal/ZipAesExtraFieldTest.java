// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.zip.ZipEncryption;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests WinZip AES extra field parsing.
@NotNullByDefault
public final class ZipAesExtraFieldTest {
    /// Verifies that the validating parser rejects malformed extra field record lengths.
    @Test
    public void validatingReadRejectsMalformedExtraFieldLength() {
        byte[] extraData = new byte[]{0x01, 0x00, 0x02, 0x00, 0x00};

        IOException exception = assertThrows(IOException.class, () -> ZipAesExtraField.readValidated(extraData));

        assertEquals(true, exception.getMessage().contains("Invalid ZIP extra field length"));
    }

    /// Verifies that the compatibility parser treats malformed extra data as absent AES metadata.
    @Test
    public void readTreatsMalformedExtraFieldLengthAsAbsent() {
        byte[] extraData = new byte[]{0x01, 0x00, 0x02, 0x00, 0x00};

        assertNull(ZipAesExtraField.read(extraData));
    }

    /// Verifies that invalid AES payloads do not hide later valid AES metadata.
    @Test
    public void readValidatedUsesLaterValidAesField() throws IOException {
        byte[] invalidAes = aesExtraField(3, 3, ZipConstants.STORED_METHOD);
        byte[] validAes = aesExtraField(2, 3, ZipConstants.DEFLATED_METHOD);
        byte[] extraData = concatenate(invalidAes, validAes);

        ZipAesExtraField aes = ZipAesExtraField.readValidated(extraData);

        assertNotNull(aes);
        assertEquals(ZipEncryption.winZipAes256(), aes.encryption());
        assertEquals(ZipConstants.DEFLATED_METHOD, aes.compressionMethod());
    }

    /// Creates one WinZip AES extra field record.
    private static byte[] aesExtraField(int vendorVersion, int strength, int compressionMethod) {
        byte[] extraData = new byte[11];
        writeShortLE(extraData, 0, ZipConstants.WINZIP_AES_EXTRA_FIELD_ID);
        writeShortLE(extraData, 2, 7);
        writeShortLE(extraData, 4, vendorVersion);
        writeShortLE(extraData, 6, 'A' | ('E' << 8));
        extraData[8] = (byte) strength;
        writeShortLE(extraData, 9, compressionMethod);
        return extraData;
    }

    /// Returns the concatenation of two byte arrays.
    private static byte[] concatenate(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    /// Writes a little-endian short to a byte array.
    private static void writeShortLE(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
    }
}
