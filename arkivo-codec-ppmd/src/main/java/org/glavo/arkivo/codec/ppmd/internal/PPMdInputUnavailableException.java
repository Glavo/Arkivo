// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.ppmd.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;

/// Suspends one PPMd range operation until a later caller-owned source buffer supplies another byte.
@NotNullByDefault
final class PPMdInputUnavailableException extends IOException {
    /// Reusable instance because temporary input exhaustion is a normal decoder outcome.
    static final PPMdInputUnavailableException INSTANCE = new PPMdInputUnavailableException();

    /// Creates the reusable input-exhaustion signal.
    private PPMdInputUnavailableException() {
        super("PPMd decoder requires another source byte");
    }
}
