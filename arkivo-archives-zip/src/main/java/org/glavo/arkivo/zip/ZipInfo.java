package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoInfo;
import org.glavo.arkivo.ArkivoItemType;
import org.glavo.arkivo.ArkivoMetadata;
import org.glavo.arkivo.ArkivoName;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.attribute.FileTime;

/// Exposes immutable metadata for one ZIP item.
@NotNullByDefault
public record ZipInfo(
        /// The ZIP item name.
        ZipName zipName,
        /// The ZIP item type.
        ArkivoItemType type,
        /// The uncompressed size stored in the ZIP metadata.
        @Nullable Long uncompressedSize,
        /// The compressed size stored in the ZIP metadata.
        @Nullable Long compressedSize,
        /// The last modified time stored in the ZIP metadata.
        @Nullable FileTime modifiedTime,
        /// The CRC-32 value stored in the ZIP metadata.
        @Nullable Long crc32,
        /// The ZIP compression method.
        ZipMethod method,
        /// Additional ZIP metadata.
        ArkivoMetadata metadata
) implements ArkivoInfo {
    /// Returns the item name as a generic Arkivo name.
    @Override
    public ArkivoName name() {
        return zipName.asArkivoName();
    }
}
