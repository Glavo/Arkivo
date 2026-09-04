// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg;

import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies immutable DMG read-option derivation and validation.
@NotNullByDefault
final class DMGArchiveOptionsTest {
    /// Verifies unchanged values reuse the record while changed values preserve independent fields.
    @Test
    void derivesCommonAndPartitionConfiguration() {
        DMGArchiveOptions defaults = DMGArchiveOptions.DEFAULT;
        assertSame(defaults, defaults.withCommon(ArchiveReadOptions.DEFAULT));
        assertSame(defaults, defaults.withPartitionIndex(DMGArchiveOptions.AUTOMATIC_PARTITION_INDEX));

        ArchiveReadOptions common = ArchiveReadOptions.DEFAULT
                .withThreadSafety(ArkivoFileSystemThreadSafety.STRICT);
        DMGArchiveOptions configured = defaults.withCommon(common).withPartitionIndex(2);
        assertNotSame(defaults, configured);
        assertSame(common, configured.common());
        assertEquals(2, configured.partitionIndex());
    }

    /// Verifies absent common options and invalid partition indexes are rejected.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void rejectsInvalidConfiguration() {
        assertThrows(
                NullPointerException.class,
                () -> new DMGArchiveOptions(null, DMGArchiveOptions.AUTOMATIC_PARTITION_INDEX)
        );
        assertThrows(NullPointerException.class, () -> DMGArchiveOptions.DEFAULT.withCommon(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> DMGArchiveOptions.DEFAULT.withPartitionIndex(-2)
        );
    }
}
