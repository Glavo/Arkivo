// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.zip.internal.ZipArkivoSourceMutationPolicies;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;

/// Decides whether an editor may directly mutate the source ZIP archive for an operation.
@NotNullByDefault
public interface ZipArkivoSourceMutationPolicy {
    /// Returns a policy that never allows direct source-file mutation.
    static ZipArkivoSourceMutationPolicy never() {
        return ZipArkivoSourceMutationPolicies.never();
    }

    /// Returns a policy that allows direct mutation only for fixed-length patches.
    static ZipArkivoSourceMutationPolicy patchWhenSafe() {
        return ZipArkivoSourceMutationPolicies.patchWhenSafe();
    }

    /// Returns a policy that allows direct mutation whenever the editor reports it as supported.
    static ZipArkivoSourceMutationPolicy directWhenPossible() {
        return ZipArkivoSourceMutationPolicies.directWhenPossible();
    }

    /// Decides how the editor should handle a potential direct source-file mutation.
    ZipArkivoSourceMutationDecision decide(ZipArkivoSourceMutationRequest request) throws IOException;
}
