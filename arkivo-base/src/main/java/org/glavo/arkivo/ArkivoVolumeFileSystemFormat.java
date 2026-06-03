// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.FileSystem;

/// Describes an archive format that can expose multi-volume archive storage as a NIO file system.
@NotNullByDefault
public interface ArkivoVolumeFileSystemFormat extends ArkivoFileSystemFormat {
    /// Opens multi-volume archive storage as a file system with default options.
    FileSystem open(ArkivoVolumeSource volumes) throws IOException;
}
