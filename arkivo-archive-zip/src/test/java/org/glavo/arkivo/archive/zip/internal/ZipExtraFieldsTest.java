// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies strict write validation and bounded compatibility parsing of ZIP extra fields.
@NotNullByDefault
final class ZipExtraFieldsTest {
    /// Removes duplicate fields without changing other records, padding, or the source array.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void removesMatchingFieldsAndPreservesPadding(int padding) throws IOException {
        byte[] records = {
                0x75, 0x70, 1, 0, 42,
                0x34, 0x12, 2, 0, 7, 8,
                0x75, 0x70, 0, 0
        };
        byte[] extraData = Arrays.copyOf(records, records.length + padding);
        byte[] original = extraData.clone();
        byte[] expected = Arrays.copyOf(new byte[]{0x34, 0x12, 2, 0, 7, 8}, 6 + padding);

        assertArrayEquals(expected, ZipExtraFields.remove(extraData, 0x7075));
        assertArrayEquals(original, extraData);
        assertSame(extraData, ZipExtraFields.remove(extraData, 0x4321));
        assertArrayEquals(new byte[padding], ZipExtraFields.remove(
                Arrays.copyOf(new byte[]{0x75, 0x70, 0, 0}, 4 + padding), 0x7075));
        byte[] paddingOnly = new byte[padding];
        assertSame(paddingOnly, ZipExtraFields.remove(paddingOnly, 0x7075));
    }

    /// Rejects malformed trailing data even after a matching field has been found.
    @Test
    void removalValidatesTheEntireInput() {
        assertThrows(IOException.class, () -> ZipExtraFields.remove(
                new byte[]{0x75, 0x70, 0, 0, 1}, 0x7075));
        assertThrows(IOException.class, () -> ZipExtraFields.remove(
                new byte[]{0x75, 0x70, 0, 0, 0x34, 0x12, 3, 0, 42}, 0x7075));
    }

    /// Keeps lookup limited to the first matching record rather than validating the remaining tail.
    @Test
    void lookupStopsAtTheFirstMatch() throws IOException {
        byte[] extraData = {0x75, 0x70, 1, 0, 42, 1};
        assertEquals(new ZipExtraFields.Field(0x7075, 4, 1, 5), ZipExtraFields.find(extraData, 0x7075));
        assertThrows(IOException.class, () -> ZipExtraFields.find(extraData, 0x1234));
    }

    /// Treats short zero padding as the end of readable records, but not as a complete record.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void distinguishesPaddingFromARecord(int padding) throws IOException {
        byte[] extraData = new byte[padding];
        ZipExtraFields.validate(extraData);
        ZipExtraFields.validateForReading(extraData);
        assertNull(ZipExtraFields.find(extraData, 0x7075));
        assertNull(ZipExtraFields.readForReading(extraData, 0));
        assertThrows(IOException.class, () -> ZipExtraFields.read(extraData, 0));
    }

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
