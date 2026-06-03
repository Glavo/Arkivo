// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;

/// Describes an archive format that can expose an archive as a NIO file system.
@NotNullByDefault
public interface ArkivoFileSystemFormat extends ArkivoFormat {
    /// Opens an archive path as a file system with default options.
    FileSystem open(Path path) throws IOException;
}
