// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.util.Objects;

/// Provides shared behavior for archive-backed file systems.
@NotNullByDefault
public abstract class ArkivoFileSystem extends FileSystem {
    /// The common environment option that controls which archive storage access values are enabled.
    public static final ArkivoFileSystemOption<ArkivoStorageAccessSet> STORAGE_ACCESS =
            ArkivoFileSystemOption.of(
                    "arkivo",
                    "storageAccess",
                    ArkivoStorageAccessSet.class,
                    ArkivoFileSystem::storageAccessOptionValue
            );

    /// The common environment option that controls the requested file system thread-safety strategy.
    public static final ArkivoFileSystemOption<ArkivoFileSystemThreadSafety> THREAD_SAFETY =
            ArkivoFileSystemOption.of(
                    "arkivo",
                    "threadSafety",
                    ArkivoFileSystemThreadSafety.class,
                    ArkivoFileSystem::threadSafetyOptionValue
            );

    /// The storage access values enabled for this file system.
    private final ArkivoStorageAccessSet storageAccess;

    /// The requested file system thread-safety strategy.
    private final ArkivoFileSystemThreadSafety threadSafety;

    /// Creates an archive-backed file system.
    protected ArkivoFileSystem(ArkivoStorageAccessSet storageAccess) {
        this(storageAccess, ArkivoFileSystemThreadSafety.CONCURRENT_READ);
    }

    /// Creates an archive-backed file system with a thread-safety strategy.
    protected ArkivoFileSystem(
            ArkivoStorageAccessSet storageAccess,
            ArkivoFileSystemThreadSafety threadSafety
    ) {
        this.storageAccess = Objects.requireNonNull(storageAccess, "storageAccess");
        this.threadSafety = Objects.requireNonNull(threadSafety, "threadSafety");
    }

    /// Returns the storage access values enabled for this file system.
    public final ArkivoStorageAccessSet storageAccess() {
        return storageAccess;
    }

    /// Returns the requested file system thread-safety strategy.
    public final ArkivoFileSystemThreadSafety threadSafety() {
        return threadSafety;
    }

    /// Opens a forward-only stream over archive entry paths in storage order.
    public ArkivoFileSystemEntryStream openEntryStream() throws IOException {
        throw new UnsupportedOperationException("Streaming entry traversal is not supported");
    }

    /// Converts a raw storage access option value.
    private static ArkivoStorageAccessSet storageAccessOptionValue(Object value) {
        if (value instanceof ArkivoStorageAccessSet access) {
            return access;
        }
        if (value instanceof String stringValue) {
            return ArkivoStorageAccessSet.parse(stringValue);
        }
        throw new IllegalArgumentException(
            "Expected ArkivoStorageAccessSet or String for key: " + STORAGE_ACCESS.key()
        );
    }

    /// Converts a raw thread-safety option value.
    private static ArkivoFileSystemThreadSafety threadSafetyOptionValue(Object value) {
        if (value instanceof ArkivoFileSystemThreadSafety threadSafety) {
            return threadSafety;
        }
        if (value instanceof String stringValue) {
            return ArkivoFileSystemThreadSafety.parse(stringValue);
        }
        throw new IllegalArgumentException(
                "Expected ArkivoFileSystemThreadSafety or String for key: " + THREAD_SAFETY.key()
        );
    }
}
