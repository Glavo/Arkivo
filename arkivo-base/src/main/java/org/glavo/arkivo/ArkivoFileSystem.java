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
    /// The common environment option that controls which archive storage access capabilities are enabled.
    public static final ArkivoFileSystemOption<ArkivoFileSystemOpenModes> OPEN_MODES =
            ArkivoFileSystemOption.of("openModes", ArkivoFileSystemOpenModes.class, ArkivoFileSystemOpenModes::parse);

    /// The access capabilities enabled for this file system.
    private final ArkivoFileSystemOpenModes openModes;

    /// Creates an archive-backed file system.
    protected ArkivoFileSystem(ArkivoFileSystemOpenModes openModes) {
        this.openModes = Objects.requireNonNull(openModes, "openModes");
    }

    /// Returns the access capabilities enabled for this file system.
    public final ArkivoFileSystemOpenModes openModes() {
        return openModes;
    }

    /// Opens a forward-only stream over archive entry paths in storage order.
    public ArkivoFileSystemEntryStream openEntryStream() throws IOException {
        throw new UnsupportedOperationException("Streaming entry traversal is not supported");
    }
}
