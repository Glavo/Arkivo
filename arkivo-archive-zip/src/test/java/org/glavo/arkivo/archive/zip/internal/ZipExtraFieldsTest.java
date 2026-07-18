// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies strict write validation and bounded compatibility parsing of ZIP extra fields.
@NotNullByDefault
final class ZipExtraFieldsTest {
    /// Keeps complete fields visible while treating a truncated trailing record as opaque read metadata.
    @Test
    void ignoresTruncatedTrailingRecordOnlyForReading() throws IOException {
        byte @Unmodifiable [] extraData = {
                0x55, 0x54, 0x01, 0x00, 0x01,
                (byte) 0xff, (byte) 0xff, (byte) 0xe8, 0x03, 0x01, 0x02, 0x03
        };

        assertDoesNotThrow(() -> ZipExtraFields.validateForReading(extraData));
        ZipExtraFields.Field field = Objects.requireNonNull(ZipExtraFields.find(extraData, 0x5455));
        assertEquals(1, field.dataSize());
        assertThrows(IOException.class, () -> ZipExtraFields.validate(extraData));
    }

    /// Keeps recognized truncated fields and nonzero short tails invalid on read paths.
    @Test
    void rejectsRecognizedOrHeaderlessTruncatedFieldsForReading() {
        assertThrows(
                IOException.class,
                () -> ZipExtraFields.validateForReading(new byte[]{0x01, 0x00, 0x02, 0x00, 0x00})
        );
        assertThrows(
                IOException.class,
                () -> ZipExtraFields.validateForReading(new byte[]{0x01, 0x02, 0x03})
        );
        assertDoesNotThrow(
                () -> ZipExtraFields.validateForReading(new byte[]{0x00, 0x00, 0x00})
        );
    }
}
