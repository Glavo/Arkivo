// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar.internal;

import org.glavo.arkivo.archive.internal.AbstractArkivoPath;
import org.glavo.arkivo.archive.ar.internal.ArArkivoFileSystemProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/// Represents a path inside a AR Arkivo file system.
@NotNullByDefault
final class ArArkivoPath extends AbstractArkivoPath<ArArkivoFileSystemImpl> {
    /// Creates a AR path from parsed components.
    private ArArkivoPath(ArArkivoFileSystemImpl fileSystem, boolean absolute, List<String> names) {
        super(fileSystem, absolute, names);
    }

    /// Creates a AR path from path text components.
    private ArArkivoPath(ArArkivoFileSystemImpl fileSystem, String first, String... more) {
        super(fileSystem, first, more);
    }

    /// Returns the root path for the given file system.
    static ArArkivoPath root(ArArkivoFileSystemImpl fileSystem) {
        return new ArArkivoPath(fileSystem, true, List.of());
    }

    /// Returns a AR path from path text components.
    static ArArkivoPath of(ArArkivoFileSystemImpl fileSystem, String first, String... more) {
        return new ArArkivoPath(fileSystem, first, more);
    }

    /// Returns this path as a AR path owned by the expected file system.
    static ArArkivoPath require(Path path, ArArkivoFileSystemImpl expectedFileSystem) {
        return requirePath(path, expectedFileSystem, ArArkivoPath.class);
    }

    /// Creates another AR path with the given parsed components.
    @Override
    protected ArArkivoPath createPath(boolean absolute, List<String> names) {
        return new ArArkivoPath(getFileSystem(), absolute, names);
    }

    /// Returns the AR URI scheme.
    @Override
    protected String uriScheme() {
        return ArArkivoFileSystemProvider.SCHEME;
    }

    /// Returns the underlying archive URI, or `null` when unavailable.
    @Override
    protected @Nullable URI archiveUri() {
        return getFileSystem().archiveUri();
    }
}
