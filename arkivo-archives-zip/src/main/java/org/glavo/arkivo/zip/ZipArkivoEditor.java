// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoCommitTarget;
import org.glavo.arkivo.ArkivoEditStorage;
import org.glavo.arkivo.ArkivoEditor;
import org.glavo.arkivo.ArkivoSourceMutationPolicy;
import org.glavo.arkivo.zip.internal.ZipArkivoEditorImpl;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/// Edits an existing ZIP archive through configurable editor strategies.
@NotNullByDefault
public abstract sealed class ZipArkivoEditor extends ArkivoEditor permits ZipArkivoEditorImpl {
    /// Creates a ZIP archive editor base instance.
    protected ZipArkivoEditor() {
    }

    /// Opens a ZIP archive editor for the given source path.
    public static ZipArkivoEditor open(Path path) throws IOException {
        return open(path, Map.of());
    }

    /// Opens a ZIP archive editor for the given source path with environment options.
    public static ZipArkivoEditor open(Path path, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(environment, "environment");
        return ZipArkivoEditorImpl.open(path, environment);
    }

    /// Returns the source archive path edited by this editor.
    public abstract Path sourcePath();

    /// Returns the storage strategy used for new and replacement entry data.
    public abstract ArkivoEditStorage storage();

    /// Returns the target strategy used when committing assembled archive bytes.
    public abstract ArkivoCommitTarget commitTarget();

    /// Returns the policy that decides whether source-file mutation is allowed.
    public abstract ArkivoSourceMutationPolicy sourceMutationPolicy();
}
