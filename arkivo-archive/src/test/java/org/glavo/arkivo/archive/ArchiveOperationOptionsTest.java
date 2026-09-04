// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies immutable strongly typed archive operation options.
@NotNullByDefault
public final class ArchiveOperationOptionsTest {
    /// Verifies stable thread-safety names round-trip through the environment parser.
    @Test
    public void threadSafetyOptionNamesRoundTrip() {
        assertEquals("none", ArkivoFileSystemThreadSafety.NONE.optionName());
        assertEquals("concurrent-read", ArkivoFileSystemThreadSafety.CONCURRENT_READ.optionName());
        assertEquals("strict", ArkivoFileSystemThreadSafety.STRICT.optionName());
        for (ArkivoFileSystemThreadSafety threadSafety : ArkivoFileSystemThreadSafety.values()) {
            assertSame(threadSafety, ArkivoFileSystemThreadSafety.parse(threadSafety.optionName()));
        }
        assertSame(
                ArkivoFileSystemThreadSafety.CONCURRENT_READ,
                ArkivoFileSystemThreadSafety.parse("  CONCURRENT_READ  ")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ArkivoFileSystemThreadSafety.parse("unsupported")
        );
    }

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
        ArkivoEditStorageFactory storageFactory = ArkivoEditStorageFactory.memory();
        ArkivoPasswordProvider passwordProvider = ArkivoPasswordProvider.none();
        ArchiveMetadataCharsetDetector charsetDetector = ArchiveMetadataCharsetDetector.fixed(StandardCharsets.UTF_8);
        ArchiveReadOptions defaults = ArchiveReadOptions.DEFAULT;
        ArchiveReadOptions configured = defaults
                .withThreadSafety(ArkivoFileSystemThreadSafety.NONE)
                .withEditStorageFactory(storageFactory)
                .withPasswordProvider(passwordProvider)
                .withMetadataCharsetDetector(charsetDetector)
                .withLimits(limits);

        assertEquals(ArkivoFileSystemThreadSafety.CONCURRENT_READ, defaults.threadSafety());
        assertSame(ArchiveReadLimits.DEFAULT, defaults.limits());
        assertEquals(ArkivoFileSystemThreadSafety.NONE, configured.threadSafety());
        assertSame(limits, configured.limits());
        assertSame(storageFactory, configured.editStorageFactory());
        assertSame(passwordProvider, configured.passwordProvider());
        assertSame(charsetDetector, configured.metadataCharsetDetector());
        assertNull(defaults.editStorageFactory());
        assertNull(defaults.passwordProvider());
        assertNull(defaults.metadataCharsetDetector());
    }

    /// Verifies creation options expose explicit common configuration.
    @Test
    public void createOptionsAreImmutable() {
        ArkivoEditStorageFactory storageFactory = ArkivoEditStorageFactory.memory();
        ArkivoPasswordProvider passwordProvider = ArkivoPasswordProvider.none();
        ArchiveMetadataCharsetDetector charsetDetector = ArchiveMetadataCharsetDetector.fixed(StandardCharsets.UTF_8);
        ArchiveCreateOptions configured = ArchiveCreateOptions.DEFAULT
                .withThreadSafety(ArkivoFileSystemThreadSafety.NONE)
                .withEditStorageFactory(storageFactory)
                .withPasswordProvider(passwordProvider)
                .withMetadataCharsetDetector(charsetDetector);

        assertEquals(ArkivoFileSystemThreadSafety.CONCURRENT_READ, ArchiveCreateOptions.DEFAULT.threadSafety());
        assertEquals(ArkivoFileSystemThreadSafety.NONE, configured.threadSafety());
        assertSame(storageFactory, configured.editStorageFactory());
        assertSame(passwordProvider, configured.passwordProvider());
        assertSame(charsetDetector, configured.metadataCharsetDetector());
        assertNull(ArchiveCreateOptions.DEFAULT.editStorageFactory());
        assertNull(ArchiveCreateOptions.DEFAULT.passwordProvider());
        assertNull(ArchiveCreateOptions.DEFAULT.metadataCharsetDetector());
    }

    /// Verifies update options carry publication and read-limit policy together.
    @Test
    public void updateOptionsAreImmutable() {
        ArkivoCommitTarget commitTarget = ArkivoCommitTarget.writeTo(java.nio.file.Path.of("target.arc"));
        ArchiveReadLimits limits = ArchiveReadLimits.builder().maximumMetadataSize(1024L).build();
        ArkivoEditStorageFactory storageFactory = ArkivoEditStorageFactory.memory();
        ArkivoPasswordProvider passwordProvider = ArkivoPasswordProvider.none();
        ArchiveMetadataCharsetDetector charsetDetector = ArchiveMetadataCharsetDetector.fixed(StandardCharsets.UTF_8);
        ArchiveUpdateOptions configured = ArchiveUpdateOptions.DEFAULT
                .withThreadSafety(ArkivoFileSystemThreadSafety.STRICT)
                .withEditStorageFactory(storageFactory)
                .withCommitTarget(commitTarget)
                .withPasswordProvider(passwordProvider)
                .withMetadataCharsetDetector(charsetDetector)
                .withLimits(limits);

        assertNull(ArchiveUpdateOptions.DEFAULT.commitTarget());
        assertEquals(ArkivoFileSystemThreadSafety.STRICT, configured.threadSafety());
        assertSame(storageFactory, configured.editStorageFactory());
        assertSame(commitTarget, configured.commitTarget());
        assertSame(passwordProvider, configured.passwordProvider());
        assertSame(charsetDetector, configured.metadataCharsetDetector());
        assertSame(limits, configured.limits());

        ArchiveReadOptions readOptions = configured.readOptions();
        assertEquals(configured.threadSafety(), readOptions.threadSafety());
        assertSame(configured.editStorageFactory(), readOptions.editStorageFactory());
        assertSame(configured.passwordProvider(), readOptions.passwordProvider());
        assertSame(configured.metadataCharsetDetector(), readOptions.metadataCharsetDetector());
        assertSame(configured.limits(), readOptions.limits());
    }

    /// Verifies required option components reject null values.
    @Test
    @SuppressWarnings("DataFlowIssue")
    public void requiredComponentsRejectNull() {
        assertThrows(NullPointerException.class, () -> ArchiveReadOptions.DEFAULT.withLimits(null));
        assertThrows(NullPointerException.class, () -> ArchiveReadOptions.DEFAULT.withThreadSafety(null));
        assertThrows(NullPointerException.class, () -> ArchiveCreateOptions.DEFAULT.withThreadSafety(null));
        assertThrows(NullPointerException.class, () -> ArchiveUpdateOptions.DEFAULT.withLimits(null));
        assertThrows(NullPointerException.class, () -> ArchiveUpdateOptions.DEFAULT.withThreadSafety(null));
    }
}
