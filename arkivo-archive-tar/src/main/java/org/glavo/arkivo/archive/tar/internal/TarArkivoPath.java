// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar.internal;

import org.glavo.arkivo.archive.internal.AbstractArkivoPath;
import org.glavo.arkivo.archive.tar.TarArkivoFileSystemProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/// Represents a path inside a TAR Arkivo file system.
@NotNullByDefault
final class TarArkivoPath extends AbstractArkivoPath<TarArkivoFileSystemImpl> {
    /// Creates a TAR path from parsed components.
    private TarArkivoPath(TarArkivoFileSystemImpl fileSystem, boolean absolute, List<String> names) {
        super(fileSystem, absolute, names);
    }

    /// Creates a TAR path from path text components.
    private TarArkivoPath(TarArkivoFileSystemImpl fileSystem, String first, String... more) {
        super(fileSystem, first, more);
    }

    /// Returns the root path for the given file system.
    static TarArkivoPath root(TarArkivoFileSystemImpl fileSystem) {
        return new TarArkivoPath(fileSystem, true, List.of());
    }

    /// Returns a TAR path from path text components.
    static TarArkivoPath of(TarArkivoFileSystemImpl fileSystem, String first, String... more) {
        return new TarArkivoPath(fileSystem, first, more);
    }

    /// Returns this path as a TAR path owned by the expected file system.
    static TarArkivoPath require(Path path, TarArkivoFileSystemImpl expectedFileSystem) {
        return requirePath(path, expectedFileSystem, TarArkivoPath.class);
    }

    /// Creates another TAR path with the given parsed components.
    @Override
    protected TarArkivoPath createPath(boolean absolute, List<String> names) {
        return new TarArkivoPath(getFileSystem(), absolute, names);
    }

    /// Returns the TAR URI scheme.
    @Override
    protected String uriScheme() {
        return TarArkivoFileSystemProvider.SCHEME;
    }

    /// Returns the underlying archive URI, or `null` when unavailable.
    @Override
    protected @Nullable URI archiveUri() {
        return getFileSystem().archiveUri();
    }
}
