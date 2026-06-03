// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.charset.StandardCharsets;

/// Represents an item path inside a ZIP archive.
///
/// @param rawPath the raw encoded ZIP item path bytes
/// @param path the decoded ZIP item path text
@NotNullByDefault
public record ZipName(
        byte @Unmodifiable [] rawPath,
        String path
) {
    /// Creates a ZIP name.
    public ZipName {
        rawPath = rawPath.clone();
    }

    /// Creates a ZIP name from decoded path text using UTF-8 for the raw bytes.
    public static ZipName of(String path) {
        return new ZipName(path.getBytes(StandardCharsets.UTF_8), path);
    }

    /// Creates a ZIP name from raw encoded path bytes and decoded path text.
    public static ZipName of(byte @Unmodifiable [] rawPath, String path) {
        return new ZipName(rawPath, path);
    }

    /// Returns the raw encoded ZIP item path bytes.
    @Override
    public byte @Unmodifiable [] rawPath() {
        return rawPath.clone();
    }

    /// Returns the decoded ZIP item path text.
    @Override
    public String toString() {
        return path;
    }
}
