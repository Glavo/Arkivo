// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.glavo.arkivo.ArkivoPasswordProvider;
import org.glavo.arkivo.zip.ZipReadOptions;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Stores ZIP read options.
@NotNullByDefault
public final class ZipReadOptionsImpl implements ZipReadOptions {
    /// The provider used to decrypt encrypted ZIP entries.
    private final @Nullable ArkivoPasswordProvider passwordProvider;

    /// Creates ZIP read options.
    public ZipReadOptionsImpl(@Nullable ArkivoPasswordProvider passwordProvider) {
        this.passwordProvider = passwordProvider;
    }

    /// Returns the provider used to decrypt encrypted ZIP entries.
    @Override
    public @Nullable ArkivoPasswordProvider passwordProvider() {
        return passwordProvider;
    }
}
