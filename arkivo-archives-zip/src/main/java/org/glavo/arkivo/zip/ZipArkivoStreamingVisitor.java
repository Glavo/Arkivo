// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.FileVisitResult;

/// Visits entries while a ZIP stream is read in storage order.
@NotNullByDefault
public interface ZipArkivoStreamingVisitor {
    /// Visits a directory entry before later entries are read.
    default FileVisitResult preVisitDirectory(String path, ZipArkivoEntryAttributes attributes) throws IOException {
        return FileVisitResult.CONTINUE;
    }

    /// Visits a regular file entry before the next entry is read.
    FileVisitResult visitFile(String path, ZipArkivoEntryAttributes attributes) throws IOException;
}
