package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes which operations an archive format supports.
@NotNullByDefault
public record ArkivoFormatCapabilities(
        /// Whether the format supports sequential reading.
        boolean streamingRead,
        /// Whether the format supports sequential writing.
        boolean streamingWrite,
        /// Whether the format supports random-access reading.
        boolean randomRead,
        /// Whether the format supports random-access writing.
        boolean randomWrite,
        /// Whether the format supports editing an existing archive.
        boolean editing,
        /// Whether the format supports a NIO file system view.
        boolean fileSystem
) {
}
