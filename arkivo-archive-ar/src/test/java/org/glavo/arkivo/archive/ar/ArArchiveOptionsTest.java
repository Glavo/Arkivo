// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar;

import org.glavo.arkivo.archive.ArchiveCreateOptions;
import org.glavo.arkivo.archive.ArchiveMetadataDecoder;
import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArchiveUpdateOptions;
import org.glavo.arkivo.archive.ArkivoCommitTarget;
import org.glavo.arkivo.archive.ArkivoEditStorageFactory;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ar.internal.ArArkivoFileSystemConfig;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies immutable AR operation-option derivation and validation.
@NotNullByDefault
final class ArArchiveOptionsTest {
    /// Verifies operation settings reach the file-system configuration without losing limits or resource policies.
    @Test
    void preservesTypedOperationSettings() {
        var storage = ArkivoEditStorageFactory.memory();
        var limits = ArchiveReadLimits.builder().maximumEntryCount(7).build();
        var decoder = ArchiveMetadataDecoder.forCharset(StandardCharsets.ISO_8859_1);
        var target = ArkivoCommitTarget.writeTo(Path.of("target.ar"));
        var common = ArchiveUpdateOptions.DEFAULT.withThreadSafety(ArkivoFileSystemThreadSafety.STRICT)
                .withEditStorageFactory(storage).withLimits(limits).withMetadataDecoder(decoder)
                .withCommitTarget(target);
        var update = ArArkivoFileSystemConfig.fromUpdateOptions(ArArchiveOptions.UPDATE_DEFAULTS.withCommon(common));
        assertEquals(Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE), update.openOptions());
        assertSame(storage, update.readOptions().editStorageFactory());
        assertSame(limits, update.readOptions().limits());
        assertSame(decoder, update.readOptions().metadataDecoder());
        assertSame(target, update.commitTarget());
        assertSame(ArkivoFileSystemThreadSafety.STRICT, update.readOptions().threadSafety());

        var read = ArArchiveOptions.READ_DEFAULTS.withCommon(ArchiveReadOptions.DEFAULT.withLimits(limits));
        assertSame(read.common(), ArArkivoFileSystemConfig.fromReadOptions(read).readOptions());
        var create = ArArkivoFileSystemConfig.fromCreateOptions(ArArchiveOptions.CREATE_DEFAULTS
                .withCommon(ArchiveCreateOptions.DEFAULT.withEditStorageFactory(storage)).withMetadataDecoder(decoder));
        assertEquals(Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW), create.openOptions());
        assertSame(storage, create.readOptions().editStorageFactory());
        assertSame(decoder, create.readOptions().metadataDecoder());
        assertSame(ArchiveReadLimits.UNLIMITED, create.readOptions().limits());

        var environment = ArArkivoFileSystemConfig.fromEnvironment(Map.of("arkivo.ar.metadataDecoder", "ISO-8859-1"));
        assertSame(ArchiveReadLimits.UNLIMITED, environment.readOptions().limits());
        assertEquals(Set.of(StandardOpenOption.READ), environment.openOptions());
    }

    /// Verifies every operation role preserves common options and exposes its effective metadata decoder.
    @Test
    void derivesCommonAndMetadataConfiguration() {
        ArchiveMetadataDecoder metadataDecoder =
                ArchiveMetadataDecoder.forCharset(StandardCharsets.UTF_16LE);
        ArchiveReadOptions readCommon = ArchiveReadOptions.DEFAULT
                .withThreadSafety(ArkivoFileSystemThreadSafety.CONCURRENT_READ);
        ArchiveCreateOptions createCommon = ArchiveCreateOptions.DEFAULT
                .withThreadSafety(ArkivoFileSystemThreadSafety.STRICT);
        ArchiveUpdateOptions updateCommon = ArchiveUpdateOptions.DEFAULT
                .withThreadSafety(ArkivoFileSystemThreadSafety.STRICT);

        ArArchiveOptions.Read read = ArArchiveOptions.READ_DEFAULTS
                .withCommon(readCommon)
                .withMetadataDecoder(metadataDecoder);
        ArArchiveOptions.Create create = ArArchiveOptions.CREATE_DEFAULTS
                .withCommon(createCommon)
                .withMetadataDecoder(metadataDecoder);
        ArArchiveOptions.Update update = ArArchiveOptions.UPDATE_DEFAULTS
                .withCommon(updateCommon)
                .withMetadataDecoder(metadataDecoder);

        assertEquals(readCommon, read.common().withMetadataDecoder(null));
        assertSame(metadataDecoder, read.metadataDecoder());
        assertEquals(createCommon, create.common().withMetadataDecoder(null));
        assertSame(metadataDecoder, create.metadataDecoder());
        assertEquals(updateCommon, update.common().withMetadataDecoder(null));
        assertSame(metadataDecoder, update.metadataDecoder());
        assertSame(
                ArArchiveOptions.DEFAULT_METADATA_DECODER,
                ArArchiveOptions.CREATE_DEFAULTS.metadataDecoder()
        );
        assertSame(
                ArArchiveOptions.DEFAULT_METADATA_DECODER,
                ArArchiveOptions.UPDATE_DEFAULTS.metadataDecoder()
        );
    }

    /// Verifies records and non-null derivation methods reject absent configuration values.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void rejectsNullConfigurationValues() {
        assertThrows(NullPointerException.class, () -> new ArArchiveOptions.Read(null));
        assertThrows(NullPointerException.class, () -> new ArArchiveOptions.Create(null));
        assertThrows(NullPointerException.class, () -> new ArArchiveOptions.Update(null));
        assertThrows(NullPointerException.class, () -> ArArchiveOptions.READ_DEFAULTS.withCommon(null));
        assertThrows(
                NullPointerException.class,
                () -> ArArchiveOptions.READ_DEFAULTS.withMetadataDecoder(null)
        );
        assertThrows(NullPointerException.class, () -> ArArchiveOptions.CREATE_DEFAULTS.withCommon(null));
        assertThrows(
                NullPointerException.class,
                () -> ArArchiveOptions.CREATE_DEFAULTS.withMetadataDecoder(null)
        );
        assertThrows(NullPointerException.class, () -> ArArchiveOptions.UPDATE_DEFAULTS.withCommon(null));
        assertThrows(
                NullPointerException.class,
                () -> ArArchiveOptions.UPDATE_DEFAULTS.withMetadataDecoder(null)
        );
    }
}
