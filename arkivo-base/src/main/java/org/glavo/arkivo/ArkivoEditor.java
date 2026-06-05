// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;

/// Provides shared behavior and environment options for archive editors.
@NotNullByDefault
public abstract class ArkivoEditor implements AutoCloseable {
    /// The common environment option for an `ArkivoEditStorage` value.
    public static final ArkivoFileSystemOption<ArkivoEditStorage> STORAGE =
            ArkivoFileSystemOption.of("arkivo.editor", "storage", ArkivoEditStorage.class);

    /// The common environment option for an `ArkivoCommitTarget` value.
    public static final ArkivoFileSystemOption<ArkivoCommitTarget> COMMIT_TARGET =
            ArkivoFileSystemOption.of("arkivo.editor", "commitTarget", ArkivoCommitTarget.class);

    /// The common environment option for an `ArkivoSourceMutationPolicy` value.
    public static final ArkivoFileSystemOption<ArkivoSourceMutationPolicy> SOURCE_MUTATION_POLICY =
            ArkivoFileSystemOption.of(
                    "arkivo.editor",
                    "sourceMutationPolicy",
                    ArkivoSourceMutationPolicy.class
            );

    /// Creates an archive editor base instance.
    protected ArkivoEditor() {
    }

    /// Closes this editor and releases resources owned by it.
    @Override
    public abstract void close() throws IOException;
}
