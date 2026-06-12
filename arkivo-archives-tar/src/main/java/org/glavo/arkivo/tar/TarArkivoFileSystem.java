// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.tar;

import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.tar.internal.TarArkivoFileSystemImpl;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/// Opens TAR archives as read-only NIO file systems.
@NotNullByDefault
public abstract sealed class TarArkivoFileSystem extends ArkivoFileSystem permits TarArkivoFileSystemImpl {
    /// Creates a TAR archive file system base instance.
    protected TarArkivoFileSystem(ArkivoFileSystemThreadSafety threadSafety) {
        super(threadSafety);
    }

    /// Opens a TAR archive file system.
    public static TarArkivoFileSystem open(Path path) throws IOException {
        return open(path, Map.of());
    }

    /// Opens a TAR archive file system with environment options.
    public static TarArkivoFileSystem open(Path path, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(environment, "environment");
        return TarArkivoFileSystemProvider.instance().newFileSystem(path, environment);
    }
}
