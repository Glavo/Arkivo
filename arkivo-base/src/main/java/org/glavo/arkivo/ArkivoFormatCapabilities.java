// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes which operations an archive format supports.
///
/// @param streamingRead whether the format supports sequential reading
/// @param streamingWrite whether the format supports sequential writing
/// @param randomRead whether the format supports random-access reading
/// @param randomWrite whether the format supports random-access writing
/// @param editing whether the format supports editing an existing archive
/// @param fileSystem whether the format supports a NIO file system view
/// @param encryptedRead whether the format supports reading encrypted archives or items
/// @param encryptedWrite whether the format supports writing encrypted archives or items
/// @param splitRead whether the format supports reading split or multi-volume archives
/// @param splitWrite whether the format supports writing split or multi-volume archives
@NotNullByDefault
public record ArkivoFormatCapabilities(
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
}
