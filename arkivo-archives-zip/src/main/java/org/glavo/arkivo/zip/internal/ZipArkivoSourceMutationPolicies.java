// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.glavo.arkivo.zip.ZipArkivoSourceMutationDecision;
import org.glavo.arkivo.zip.ZipArkivoSourceMutationPolicy;
import org.glavo.arkivo.zip.ZipArkivoSourceMutationRequest;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Provides built-in ZIP editor source mutation policies.
@NotNullByDefault
public final class ZipArkivoSourceMutationPolicies {
    /// The policy that never allows direct source mutation.
    private static final ZipArkivoSourceMutationPolicy NEVER = new NeverPolicy();

    /// The policy that allows safe fixed-length patches.
    private static final ZipArkivoSourceMutationPolicy PATCH_WHEN_SAFE = new PatchWhenSafePolicy();

    /// The policy that allows direct mutation when the editor reports support.
    private static final ZipArkivoSourceMutationPolicy DIRECT_WHEN_POSSIBLE = new DirectWhenPossiblePolicy();

    /// Creates built-in ZIP editor source mutation policies.
    private ZipArkivoSourceMutationPolicies() {
    }

    /// Returns a policy that never allows direct source-file mutation.
    public static ZipArkivoSourceMutationPolicy never() {
        return NEVER;
    }

    /// Returns a policy that allows direct mutation only for fixed-length patches.
    public static ZipArkivoSourceMutationPolicy patchWhenSafe() {
        return PATCH_WHEN_SAFE;
    }

    /// Returns a policy that allows direct mutation whenever the editor reports it as supported.
    public static ZipArkivoSourceMutationPolicy directWhenPossible() {
        return DIRECT_WHEN_POSSIBLE;
    }

    /// Implements the never-direct policy.
    @NotNullByDefault
    private static final class NeverPolicy implements ZipArkivoSourceMutationPolicy {
        /// Creates a never-direct policy.
        private NeverPolicy() {
        }

        /// Always requests rewrite.
        @Override
        public ZipArkivoSourceMutationDecision decide(ZipArkivoSourceMutationRequest request) {
            Objects.requireNonNull(request, "request");
            return ZipArkivoSourceMutationDecision.REWRITE;
        }
    }

    /// Implements the fixed-length patch policy.
    @NotNullByDefault
    private static final class PatchWhenSafePolicy implements ZipArkivoSourceMutationPolicy {
        /// Creates a fixed-length patch policy.
        private PatchWhenSafePolicy() {
        }

        /// Allows fixed-length patches and rewrites other operations.
        @Override
        public ZipArkivoSourceMutationDecision decide(ZipArkivoSourceMutationRequest request) {
            Objects.requireNonNull(request, "request");
            return request.fixedLengthPatch()
                    ? ZipArkivoSourceMutationDecision.ALLOW
                    : ZipArkivoSourceMutationDecision.REWRITE;
        }
    }

    /// Implements the direct-when-possible policy.
    @NotNullByDefault
    private static final class DirectWhenPossiblePolicy implements ZipArkivoSourceMutationPolicy {
        /// Creates a direct-when-possible policy.
        private DirectWhenPossiblePolicy() {
        }

        /// Allows directly supported operations and rewrites other operations.
        @Override
        public ZipArkivoSourceMutationDecision decide(ZipArkivoSourceMutationRequest request) {
            Objects.requireNonNull(request, "request");
            return request.directMutationSupported()
                    ? ZipArkivoSourceMutationDecision.ALLOW
                    : ZipArkivoSourceMutationDecision.REWRITE;
        }
    }
}
