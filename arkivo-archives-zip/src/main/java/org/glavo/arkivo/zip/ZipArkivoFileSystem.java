// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;

/// Opens ZIP archives as NIO file systems.
@NotNullByDefault
public final class ZipArkivoFileSystem {
    /// Creates no instances.
    private ZipArkivoFileSystem() {
    }

    /// Opens a ZIP archive file system.
    public static FileSystem open(Path path) throws IOException {
        throw new UnsupportedOperationException("ZIP archive file systems are not implemented yet");
    }
}
