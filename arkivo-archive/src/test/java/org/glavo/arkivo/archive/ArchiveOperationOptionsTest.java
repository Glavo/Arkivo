// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies immutable strongly typed archive operation options.
@NotNullByDefault
public final class ArchiveOperationOptionsTest {
    /// Verifies reusable storage factories create independent operation-owned instances.
    @Test
    public void editStorageFactoriesAreReusable() throws IOException {
        ArkivoEditStorageFactory factory = ArkivoEditStorageFactory.memory();
        try (ArkivoEditStorage first = factory.open(); ArkivoEditStorage second = factory.open()) {
            assertNotSame(first, second);
        }
    }

    /// Verifies read defaults and copy methods preserve unrelated settings.
    @Test
    public void readOptionsAreImmutable() {
        ArchiveReadLimits limits = ArchiveReadLimits.builder().maximumEntryCount(3L).build();
        ArchiveReadOptions defaults = ArchiveReadOptions.DEFAULT;
        ArchiveReadOptions configured = defaults
                .withThreadSafety(ArkivoFileSystemThreadSafety.NONE)
                .withLimits(limits);

        assertEquals(ArkivoFileSystemThreadSafety.CONCURRENT_READ, defaults.threadSafety());
        assertSame(ArchiveReadLimits.DEFAULT, defaults.limits());
        assertEquals(ArkivoFileSystemThreadSafety.NONE, configured.threadSafety());
        assertSame(limits, configured.limits());
        assertNull(configured.editStorageFactory());
        assertNull(configured.passwordProvider());
        assertNull(configured.metadataCharsetDetector());
    }

    /// Verifies creation options expose explicit common configuration.
    @Test
    public void createOptionsAreImmutable() {
        ArchiveCreateOptions configured = ArchiveCreateOptions.DEFAULT
                .withThreadSafety(ArkivoFileSystemThreadSafety.NONE);

        assertEquals(ArkivoFileSystemThreadSafety.CONCURRENT_READ, ArchiveCreateOptions.DEFAULT.threadSafety());
        assertEquals(ArkivoFileSystemThreadSafety.NONE, configured.threadSafety());
        assertNull(configured.editStorageFactory());
        assertNull(configured.passwordProvider());
        assertNull(configured.metadataCharsetDetector());
    }

    /// Verifies update options carry publication and read-limit policy together.
    @Test
    public void updateOptionsAreImmutable() {
        ArkivoCommitTarget commitTarget = ArkivoCommitTarget.writeTo(java.nio.file.Path.of("target.arc"));
        ArchiveReadLimits limits = ArchiveReadLimits.builder().maximumMetadataSize(1024L).build();
        ArchiveUpdateOptions configured = ArchiveUpdateOptions.DEFAULT
                .withCommitTarget(commitTarget)
                .withLimits(limits);

        assertNull(ArchiveUpdateOptions.DEFAULT.commitTarget());
        assertSame(commitTarget, configured.commitTarget());
        assertSame(limits, configured.limits());
    }

    /// Verifies required option components reject null values.
    @Test
    @SuppressWarnings("DataFlowIssue")
    public void requiredComponentsRejectNull() {
        assertThrows(NullPointerException.class, () -> ArchiveReadOptions.DEFAULT.withLimits(null));
        assertThrows(NullPointerException.class, () -> ArchiveCreateOptions.DEFAULT.withThreadSafety(null));
        assertThrows(NullPointerException.class, () -> ArchiveUpdateOptions.DEFAULT.withLimits(null));
    }
}
