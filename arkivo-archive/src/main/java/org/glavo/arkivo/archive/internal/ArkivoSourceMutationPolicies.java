// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.glavo.arkivo.archive.ArkivoSourceMutationDecision;
import org.glavo.arkivo.archive.ArkivoSourceMutationPolicy;
import org.glavo.arkivo.archive.ArkivoSourceMutationRequest;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Provides built-in archive editor source mutation policies.
@NotNullByDefault
public final class ArkivoSourceMutationPolicies {
    /// The policy that never allows direct source mutation.
    private static final ArkivoSourceMutationPolicy NEVER = new NeverPolicy();

    /// The policy that allows safe fixed-length patches.
    private static final ArkivoSourceMutationPolicy PATCH_WHEN_SAFE = new PatchWhenSafePolicy();

    /// The policy that allows direct mutation when the editor reports support.
    private static final ArkivoSourceMutationPolicy DIRECT_WHEN_POSSIBLE = new DirectWhenPossiblePolicy();

    /// Creates built-in archive editor source mutation policies.
    private ArkivoSourceMutationPolicies() {
    }

    /// Returns a policy that never allows direct source-file mutation.
    public static ArkivoSourceMutationPolicy never() {
        return NEVER;
    }

    /// Returns a policy that allows direct mutation only for fixed-length patches.
    public static ArkivoSourceMutationPolicy patchWhenSafe() {
        return PATCH_WHEN_SAFE;
    }

    /// Returns a policy that allows direct mutation whenever the editor reports it as supported.
    public static ArkivoSourceMutationPolicy directWhenPossible() {
        return DIRECT_WHEN_POSSIBLE;
    }

    /// Implements the never-direct policy.
    @NotNullByDefault
    private static final class NeverPolicy implements ArkivoSourceMutationPolicy {
        /// Creates a never-direct policy.
        private NeverPolicy() {
        }

        /// Always requests rewrite.
        @Override
        public ArkivoSourceMutationDecision decide(ArkivoSourceMutationRequest request) {
            Objects.requireNonNull(request, "request");
            return ArkivoSourceMutationDecision.REWRITE;
        }
    }

    /// Implements the fixed-length patch policy.
    @NotNullByDefault
    private static final class PatchWhenSafePolicy implements ArkivoSourceMutationPolicy {
        /// Creates a fixed-length patch policy.
        private PatchWhenSafePolicy() {
        }

        /// Allows fixed-length patches and rewrites other operations.
        @Override
        public ArkivoSourceMutationDecision decide(ArkivoSourceMutationRequest request) {
            Objects.requireNonNull(request, "request");
            return request.fixedLengthPatch()
                    ? ArkivoSourceMutationDecision.ALLOW
                    : ArkivoSourceMutationDecision.REWRITE;
        }
    }

    /// Implements the direct-when-possible policy.
    @NotNullByDefault
    private static final class DirectWhenPossiblePolicy implements ArkivoSourceMutationPolicy {
        /// Creates a direct-when-possible policy.
        private DirectWhenPossiblePolicy() {
        }

        /// Allows directly supported operations and rewrites other operations.
        @Override
        public ArkivoSourceMutationDecision decide(ArkivoSourceMutationRequest request) {
            Objects.requireNonNull(request, "request");
            return request.directMutationSupported()
                    ? ArkivoSourceMutationDecision.ALLOW
                    : ArkivoSourceMutationDecision.REWRITE;
        }
    }
}
