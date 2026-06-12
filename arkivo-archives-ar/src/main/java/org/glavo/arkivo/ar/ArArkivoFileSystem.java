// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.ar;

import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.ar.internal.ArArkivoFileSystemImpl;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/// Opens AR archives as read-only NIO file systems.
@NotNullByDefault
public abstract sealed class ArArkivoFileSystem extends ArkivoFileSystem permits ArArkivoFileSystemImpl {
    /// Creates an AR archive file system base instance.
    protected ArArkivoFileSystem(ArkivoFileSystemThreadSafety threadSafety) {
        super(threadSafety);
    }

    /// Opens an AR archive file system.
    public static ArArkivoFileSystem open(Path path) throws IOException {
        return open(path, Map.of());
    }

    /// Opens an AR archive file system with environment options.
    public static ArArkivoFileSystem open(Path path, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(environment, "environment");
        return ArArkivoFileSystemProvider.instance().newFileSystem(path, environment);
    }
}
