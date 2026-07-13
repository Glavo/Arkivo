// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.archive.internal.AbstractArkivoPath;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoFileSystem;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoFileSystemProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/// Represents a path inside a 7z Arkivo file system.
@NotNullByDefault
final class SevenZipArkivoPath extends AbstractArkivoPath<SevenZipArkivoFileSystem> {
    /// Creates a 7z path from parsed components.
    private SevenZipArkivoPath(SevenZipArkivoFileSystem fileSystem, boolean absolute, List<String> names) {
        super(fileSystem, absolute, names);
    }

    /// Creates a 7z path from path text components.
    private SevenZipArkivoPath(SevenZipArkivoFileSystem fileSystem, String first, String... more) {
        super(fileSystem, first, more);
    }

    /// Returns the root path for the given file system.
    static SevenZipArkivoPath root(SevenZipArkivoFileSystem fileSystem) {
        return new SevenZipArkivoPath(fileSystem, true, List.of());
    }

    /// Returns a 7z path from path text components.
    static SevenZipArkivoPath of(SevenZipArkivoFileSystem fileSystem, String first, String... more) {
        return new SevenZipArkivoPath(fileSystem, first, more);
    }

    /// Creates another 7z path with the given parsed components.
    @Override
    protected SevenZipArkivoPath createPath(boolean absolute, List<String> names) {
        return new SevenZipArkivoPath(getFileSystem(), absolute, names);
    }

    /// Returns the 7z URI scheme.
    @Override
    protected String uriScheme() {
        return SevenZipArkivoFileSystemProvider.SCHEME;
    }

    /// Returns the underlying archive URI, or `null` when unavailable.
    @Override
    protected @Nullable URI archiveUri() {
        SevenZipArkivoFileSystem fileSystem = getFileSystem();
        if (fileSystem instanceof SevenZipArkivoFileSystemImpl implementation) {
            return implementation.archiveUri();
        }
        return null;
    }
}
