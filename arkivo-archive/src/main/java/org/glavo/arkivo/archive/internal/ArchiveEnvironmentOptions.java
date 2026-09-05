// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.glavo.arkivo.archive.ArchiveMetadataCharsetDetector;
import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArkivoCommitTarget;
import org.glavo.arkivo.archive.ArkivoEditStorageFactory;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.charset.Charset;
import java.nio.file.OpenOption;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/// Defines common environment keys and their accepted value representations.
@NotNullByDefault
public final class ArchiveEnvironmentOptions {
    /// The NIO environment key for file-system synchronization.
    public static final ArchiveOption<ArkivoFileSystemThreadSafety> THREAD_SAFETY =
            ArchiveOption.of(
                    "arkivo.threadSafety",
                    ArkivoFileSystemThreadSafety.class,
                    ArchiveEnvironmentOptions::threadSafetyValue
            );

    /// The NIO environment key for archive path open options.
    public static final ArchiveOption<Set<OpenOption>> OPEN_OPTIONS =
            ArchiveOption.of(
                    "arkivo.openOptions",
                    openOptionsType(),
                    ArchiveEnvironmentOptions::openOptionsValue
            );

    /// The NIO environment key for the operation-owned edit-storage factory.
    public static final ArchiveOption<ArkivoEditStorageFactory> EDIT_STORAGE_FACTORY =
            ArchiveOption.of("arkivo.editStorageFactory", ArkivoEditStorageFactory.class);

    /// The NIO environment key for update publication.
    public static final ArchiveOption<ArkivoCommitTarget> COMMIT_TARGET =
            ArchiveOption.of("arkivo.commitTarget", ArkivoCommitTarget.class);

    /// The NIO environment key for archive read limits.
    public static final ArchiveOption<ArchiveReadLimits> READ_LIMITS =
            ArchiveOption.of("arkivo.readLimits", ArchiveReadLimits.class);

    /// Creates no instances.
    private ArchiveEnvironmentOptions() {
    }

    /// Returns a format-specific option that normalizes metadata charset detectors, charsets, and charset names.
    ///
    /// @param key the dot-separated environment key
    /// @return an option whose raw values may be an [ArchiveMetadataCharsetDetector], [Charset], or charset-name string
    /// @throws IllegalArgumentException if `key` contains empty segments or whitespace
    public static ArchiveOption<ArchiveMetadataCharsetDetector> metadataCharsetDetectorOption(
            String key
    ) {
        return ArchiveOption.of(
                key,
                ArchiveMetadataCharsetDetector.class,
                value -> metadataCharsetDetectorValue(value, key)
        );
    }

    /// Converts a raw thread-safety value.
    private static ArkivoFileSystemThreadSafety threadSafetyValue(Object value) {
        if (value instanceof ArkivoFileSystemThreadSafety strategy) {
            return strategy;
        }
        if (value instanceof String name) {
            return ArkivoFileSystemThreadSafety.parse(name);
        }
        throw new IllegalArgumentException("Expected ArkivoFileSystemThreadSafety or String for key: arkivo.threadSafety");
    }

    /// Converts a raw metadata charset-detector value.
    private static ArchiveMetadataCharsetDetector metadataCharsetDetectorValue(Object value, String key) {
        if (value instanceof ArchiveMetadataCharsetDetector detector) {
            return detector;
        }
        if (value instanceof Charset charset) {
            return ArchiveMetadataCharsetDetector.fixed(charset);
        }
        if (value instanceof String charsetName) {
            return ArchiveMetadataCharsetDetector.fixed(Charset.forName(charsetName));
        }
        throw new IllegalArgumentException(
                "Expected ArchiveMetadataCharsetDetector, Charset, or String for key: " + key
        );
    }

    /// Converts raw NIO open options.
    private static @Unmodifiable Set<OpenOption> openOptionsValue(Object value) {
        LinkedHashSet<OpenOption> options = new LinkedHashSet<>();
        if (value instanceof OpenOption option) {
            options.add(option);
        } else if (value instanceof OpenOption[] array) {
            for (OpenOption option : array) {
                options.add(option);
            }
        } else if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (!(item instanceof OpenOption option)) {
                    throw new IllegalArgumentException("Expected only OpenOption values for key: arkivo.openOptions");
                }
                options.add(option);
            }
        } else {
            throw new IllegalArgumentException("Expected OpenOption, OpenOption[], or Collection for key: arkivo.openOptions");
        }
        return Set.copyOf(options);
    }

    /// Returns the erased open-option set type.
    @SuppressWarnings("unchecked")
    private static Class<Set<OpenOption>> openOptionsType() {
        return (Class<Set<OpenOption>>) (Class<?>) Set.class;
    }
}
