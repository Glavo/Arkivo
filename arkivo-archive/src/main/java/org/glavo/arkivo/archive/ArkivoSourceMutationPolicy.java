// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.glavo.arkivo.archive.internal.ArkivoSourceMutationPolicies;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;

/// Decides whether an editor may directly mutate the source archive for an operation.
@NotNullByDefault
public interface ArkivoSourceMutationPolicy {
    /// Returns a policy that never allows direct source-file mutation.
    static ArkivoSourceMutationPolicy never() {
        return ArkivoSourceMutationPolicies.never();
    }

    /// Returns a policy that allows direct mutation only for fixed-length patches.
    static ArkivoSourceMutationPolicy patchWhenSafe() {
        return ArkivoSourceMutationPolicies.patchWhenSafe();
    }

    /// Returns a policy that allows direct mutation whenever the editor reports it as supported.
    static ArkivoSourceMutationPolicy directWhenPossible() {
        return ArkivoSourceMutationPolicies.directWhenPossible();
    }

    /// Decides how the editor should handle a potential direct source-file mutation.
    ArkivoSourceMutationDecision decide(ArkivoSourceMutationRequest request) throws IOException;
}
