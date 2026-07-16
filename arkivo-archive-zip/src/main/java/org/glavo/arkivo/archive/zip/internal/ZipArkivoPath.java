// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.internal.AbstractArkivoPath;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.List;

/// Represents a path inside a ZIP Arkivo file system.
@NotNullByDefault
final class ZipArkivoPath extends AbstractArkivoPath<ZipArkivoFileSystem> {
    /// Creates a ZIP path from parsed components.
    private ZipArkivoPath(ZipArkivoFileSystem fileSystem, boolean absolute, List<String> names) {
        super(fileSystem, absolute, names);
    }

    /// Creates a ZIP path from path text components.
    private ZipArkivoPath(ZipArkivoFileSystem fileSystem, String first, String... more) {
        super(fileSystem, first, more);
    }

    /// Returns the root path for the given file system.
    static ZipArkivoPath root(ZipArkivoFileSystem fileSystem) {
        return new ZipArkivoPath(fileSystem, true, List.of());
    }

    /// Returns a ZIP path from path text components.
    static ZipArkivoPath of(ZipArkivoFileSystem fileSystem, String first, String... more) {
        return new ZipArkivoPath(fileSystem, first, more);
    }

    /// Creates another ZIP path with the given parsed components.
    @Override
    protected ZipArkivoPath createPath(boolean absolute, List<String> names) {
        return new ZipArkivoPath(getFileSystem(), absolute, names);
    }

    /// Returns the ZIP URI scheme.
    @Override
    protected String uriScheme() {
        return ZipArkivoFileSystemProvider.SCHEME;
    }

    /// Returns the underlying archive URI, or `null` when unavailable.
    @Override
    protected @Nullable URI archiveUri() {
        ZipArkivoFileSystem fileSystem = getFileSystem();
        if (fileSystem instanceof ZipFileSystemOperations operations) {
            return operations.archiveUri();
        }
        return null;
    }
}
