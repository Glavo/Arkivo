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
    /// The common environment option that controls how archive storage is opened.
    public static final ArkivoFileSystemOption<ArkivoFileSystemOpenMode> OPEN_MODE =
            ArkivoFileSystemOption.of("openMode", ArkivoFileSystemOpenMode.class, ArkivoFileSystemOpenMode::parse);

    /// The mode used to open this file system.
    private final ArkivoFileSystemOpenMode openMode;

    /// Creates an archive-backed file system.
    protected ArkivoFileSystem(ArkivoFileSystemOpenMode openMode) {
        this.openMode = Objects.requireNonNull(openMode, "openMode");
    }

    /// Returns the mode used to open this file system.
    public final ArkivoFileSystemOpenMode openMode() {
        return openMode;
    }

    /// Opens a forward-only stream over archive entry paths in storage order.
    public ArkivoFileSystemEntryStream openEntryStream() throws IOException {
        throw new UnsupportedOperationException("Streaming entry traversal is not supported");
    }
}
