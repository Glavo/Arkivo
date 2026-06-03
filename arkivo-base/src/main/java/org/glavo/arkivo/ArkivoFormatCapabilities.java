// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.glavo.arkivo.internal.ArkivoFormatCapabilitiesImpl;
import org.jetbrains.annotations.NotNullByDefault;

/// Describes which operations an archive format supports.
@NotNullByDefault
public sealed interface ArkivoFormatCapabilities permits ArkivoFormatCapabilitiesImpl {
    /// Creates a capability set.
    static ArkivoFormatCapabilities of(
            boolean streamingRead,
            boolean streamingWrite,
            boolean randomRead,
            boolean randomWrite,
            boolean editing,
            boolean fileSystem,
            boolean encryptedRead,
            boolean encryptedWrite,
            boolean splitRead,
            boolean splitWrite
    ) {
        return new ArkivoFormatCapabilitiesImpl(
                streamingRead,
                streamingWrite,
                randomRead,
                randomWrite,
                editing,
                fileSystem,
                encryptedRead,
                encryptedWrite,
                splitRead,
                splitWrite
        );
    }

    /// Returns whether the format supports sequential reading.
    boolean streamingRead();

    /// Returns whether the format supports sequential writing.
    boolean streamingWrite();

    /// Returns whether the format supports random-access reading.
    boolean randomRead();

    /// Returns whether the format supports random-access writing.
    boolean randomWrite();

    /// Returns whether the format supports editing an existing archive.
    boolean editing();

    /// Returns whether the format supports a NIO file system view.
    boolean fileSystem();

    /// Returns whether the format supports reading encrypted archives or entries.
    boolean encryptedRead();

    /// Returns whether the format supports writing encrypted archives or entries.
    boolean encryptedWrite();

    /// Returns whether the format supports reading split or multi-volume archives.
    boolean splitRead();

    /// Returns whether the format supports writing split or multi-volume archives.
    boolean splitWrite();
}
