// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.glavo.arkivo.archive.internal.AbstractArkivoPath;
import org.glavo.arkivo.archive.rar.internal.RarArkivoFileSystemProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/// Represents a path inside a RAR Arkivo file system.
@NotNullByDefault
final class RarArkivoPath extends AbstractArkivoPath<RarArkivoFileSystemImpl> {
    /// Creates a RAR path from parsed components.
    private RarArkivoPath(RarArkivoFileSystemImpl fileSystem, boolean absolute, List<String> names) {
        super(fileSystem, absolute, names);
    }

    /// Creates a RAR path from path text components.
    private RarArkivoPath(RarArkivoFileSystemImpl fileSystem, String first, String... more) {
        super(fileSystem, first, more);
    }

    /// Returns the root path for the given file system.
    static RarArkivoPath root(RarArkivoFileSystemImpl fileSystem) {
        return new RarArkivoPath(fileSystem, true, List.of());
    }

    /// Returns a RAR path from path text components.
    static RarArkivoPath of(RarArkivoFileSystemImpl fileSystem, String first, String... more) {
        return new RarArkivoPath(fileSystem, first, more);
    }

    /// Returns this path as a RAR path owned by the expected file system.
    static RarArkivoPath require(Path path, RarArkivoFileSystemImpl expectedFileSystem) {
        return requirePath(path, expectedFileSystem, RarArkivoPath.class);
    }

    /// Creates another RAR path with the given parsed components.
    @Override
    protected RarArkivoPath createPath(boolean absolute, List<String> names) {
        return new RarArkivoPath(getFileSystem(), absolute, names);
    }

    /// Returns the RAR URI scheme.
    @Override
    protected String uriScheme() {
        return RarArkivoFileSystemProvider.SCHEME;
    }

    /// Returns the underlying archive URI, or `null` when unavailable.
    @Override
    protected @Nullable URI archiveUri() {
        return getFileSystem().archiveUri();
    }
}
