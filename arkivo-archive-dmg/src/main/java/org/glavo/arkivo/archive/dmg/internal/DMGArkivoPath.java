// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg.internal;

import org.glavo.arkivo.archive.internal.AbstractArkivoPath;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/// Implements immutable slash-separated paths inside one DMG file system.
@NotNullByDefault
final class DMGArkivoPath extends AbstractArkivoPath<DMGArkivoFileSystemImpl> {
    /// Creates the synthetic absolute root path.
    static DMGArkivoPath root(DMGArkivoFileSystemImpl fileSystem) {
        return new DMGArkivoPath(fileSystem, true, List.of());
    }

    /// Creates a path from text components.
    static DMGArkivoPath of(DMGArkivoFileSystemImpl fileSystem, String first, String... more) {
        return new DMGArkivoPath(fileSystem, first, more);
    }

    /// Requires a path owned by the expected DMG file system.
    static DMGArkivoPath require(Path path, DMGArkivoFileSystemImpl fileSystem) {
        return requirePath(path, fileSystem, DMGArkivoPath.class);
    }

    /// Creates a path from text components.
    DMGArkivoPath(DMGArkivoFileSystemImpl fileSystem, String first, String... more) {
        super(fileSystem, first, more);
    }

    /// Creates a path from already parsed components.
    private DMGArkivoPath(DMGArkivoFileSystemImpl fileSystem, boolean absolute, List<String> names) {
        super(fileSystem, absolute, names);
    }

    /// Creates another DMG path over parsed components.
    @Override
    protected AbstractArkivoPath<DMGArkivoFileSystemImpl> createPath(boolean absolute, List<String> names) {
        return new DMGArkivoPath(getFileSystem(), absolute, names);
    }

    /// Returns the DMG provider URI scheme.
    @Override
    protected String uriScheme() {
        return DMGArkivoFileSystemProvider.SCHEME;
    }

    /// Returns the backing DMG URI when path-backed.
    @Override
    protected @Nullable URI archiveUri() {
        return getFileSystem().archiveUri();
    }
}
