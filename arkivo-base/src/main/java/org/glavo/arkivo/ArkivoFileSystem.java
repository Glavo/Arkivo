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
            ArkivoFileSystemOption.of("storageAccess", ArkivoStorageAccessSet.class, ArkivoStorageAccessSet::parse);

    /// The storage access values enabled for this file system.
    private final ArkivoStorageAccessSet storageAccess;

    /// Creates an archive-backed file system.
    protected ArkivoFileSystem(ArkivoStorageAccessSet storageAccess) {
        this.storageAccess = Objects.requireNonNull(storageAccess, "storageAccess");
    }

    /// Returns the storage access values enabled for this file system.
    public final ArkivoStorageAccessSet storageAccess() {
        return storageAccess;
    }

    /// Opens a forward-only stream over archive entry paths in storage order.
    public ArkivoFileSystemEntryStream openEntryStream() throws IOException {
        throw new UnsupportedOperationException("Streaming entry traversal is not supported");
    }
}
