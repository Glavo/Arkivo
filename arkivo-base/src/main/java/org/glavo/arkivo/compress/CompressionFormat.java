package org.glavo.arkivo.compress;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes a single-stream compression format.
@NotNullByDefault
public interface CompressionFormat {
    /// Returns the stable compression format name.
    String name();
}
