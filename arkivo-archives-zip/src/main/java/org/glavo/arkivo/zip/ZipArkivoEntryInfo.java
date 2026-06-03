// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoEntryInfo;
import org.glavo.arkivo.zip.internal.ZipArkivoEntryInfoImpl;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Exposes immutable metadata for one ZIP entry.
@NotNullByDefault
public sealed interface ZipArkivoEntryInfo extends ArkivoEntryInfo permits ZipArkivoEntryInfoImpl {
    /// Returns the compressed size stored in the ZIP metadata.
    @Nullable Long compressedSize();

    /// Returns the CRC-32 value stored in the ZIP metadata.
    @Nullable Long crc32();

    /// Returns the ZIP compression method.
    ZipMethod method();

    /// Returns the ZIP encryption method.
    ZipEncryption encryption();
}
