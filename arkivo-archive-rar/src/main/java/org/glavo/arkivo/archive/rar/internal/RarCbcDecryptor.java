// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;

/// Decrypts one archive-format AES-CBC stream block by block.
@NotNullByDefault
interface RarCbcDecryptor {
    /// The AES block size shared by supported RAR encryption schemes.
    int BLOCK_SIZE = 16;

    /// Decrypts one complete ciphertext block into the target array.
    void decryptBlock(byte[] ciphertext, byte[] target) throws IOException;

    /// Clears mutable chaining state and prevents further decryption.
    void clear();
}
