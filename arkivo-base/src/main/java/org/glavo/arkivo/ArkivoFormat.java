package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes an archive format supported by Arkivo.
@NotNullByDefault
public interface ArkivoFormat {
    /// Returns the stable format name.
    String name();

    /// Returns the capability set exposed by this format.
    ArkivoFormatCapabilities capabilities();
}
