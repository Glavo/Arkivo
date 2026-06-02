package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.attribute.FileTime;

/// Describes metadata requested when writing an archive item.
@NotNullByDefault
public interface ArkivoInfoSpec {
    /// Returns the item name inside the archive.
    ArkivoName name();

    /// Returns the item type.
    ArkivoItemType type();

    /// Returns the expected uncompressed size.
    @Nullable Long uncompressedSize();

    /// Returns the requested last modified time.
    @Nullable FileTime modifiedTime();

    /// Returns additional metadata requested for the item.
    ArkivoMetadata metadata();
}
