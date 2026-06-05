// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoFileSystemOption;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;

/// Defines shared ZIP archive editor options.
@NotNullByDefault
public abstract class ZipArkivoEditor implements AutoCloseable {
    /// The environment option for a `ZipArkivoEditStorage` value.
    public static final ArkivoFileSystemOption<ZipArkivoEditStorage> STORAGE =
            ArkivoFileSystemOption.of("arkivo.zip.editor", "storage", ZipArkivoEditStorage.class);

    /// The environment option for a `ZipArkivoCommitTarget` value.
    public static final ArkivoFileSystemOption<ZipArkivoCommitTarget> COMMIT_TARGET =
            ArkivoFileSystemOption.of("arkivo.zip.editor", "commitTarget", ZipArkivoCommitTarget.class);

    /// The environment option for a `ZipArkivoSourceMutationPolicy` value.
    public static final ArkivoFileSystemOption<ZipArkivoSourceMutationPolicy> SOURCE_MUTATION_POLICY =
            ArkivoFileSystemOption.of(
                    "arkivo.zip.editor",
                    "sourceMutationPolicy",
                    ZipArkivoSourceMutationPolicy.class
            );

    /// Creates a ZIP archive editor base instance.
    protected ZipArkivoEditor() {
    }

    /// Closes this editor and releases temporary resources owned by it.
    @Override
    public abstract void close() throws IOException;
}
