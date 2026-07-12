// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;

/// Defines the mutable state required by one legacy RAR file-data cipher.
@NotNullByDefault
interface RarLegacyCipher {
    /// Returns the number of ciphertext bytes processed as one indivisible unit.
    int blockSize();

    /// Decrypts complete units in place and advances the cipher state.
    void decrypt(byte[] data, int offset, int length);

    /// Clears all password-derived and evolving state.
    void clear();
}
