// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.attribute.FileTime;

/// Provides a general-purpose archive item metadata specification.
///
/// @param name the item name inside the archive
/// @param type the item type
/// @param uncompressedSize the expected uncompressed size
/// @param modifiedTime the requested last modified time
/// @param metadata additional metadata requested for the item
@NotNullByDefault
public record BasicArkivoInfoSpec(
        ArkivoName name,
        ArkivoItemType type,
        @Nullable Long uncompressedSize,
        @Nullable FileTime modifiedTime,
        ArkivoMetadata metadata
) implements ArkivoInfoSpec {
    /// Creates a builder for an item with the given name.
    public static Builder builder(ArkivoName name) {
        return new Builder(name);
    }

    /// Builds `BasicArkivoInfoSpec` instances.
    @NotNullByDefault
    public static final class Builder {
        /// The item name inside the archive.
        private final ArkivoName name;

        /// The requested item type.
        private ArkivoItemType type = ArkivoItemType.REGULAR_FILE;

        /// The expected uncompressed size.
        private @Nullable Long uncompressedSize;

        /// The requested last modified time.
        private @Nullable FileTime modifiedTime;

        /// Additional metadata requested for the item.
        private ArkivoMetadata metadata = ArkivoMetadata.empty();

        /// Creates a builder for an item with the given name.
        public Builder(ArkivoName name) {
            this.name = name;
        }

        /// Sets the item type.
        public Builder type(ArkivoItemType type) {
            this.type = type;
            return this;
        }

        /// Sets the expected uncompressed size.
        public Builder uncompressedSize(@Nullable Long uncompressedSize) {
            this.uncompressedSize = uncompressedSize;
            return this;
        }

        /// Sets the requested last modified time.
        public Builder modifiedTime(@Nullable FileTime modifiedTime) {
            this.modifiedTime = modifiedTime;
            return this;
        }

        /// Sets the additional metadata.
        public Builder metadata(ArkivoMetadata metadata) {
            this.metadata = metadata;
            return this;
        }

        /// Builds the metadata specification.
        public BasicArkivoInfoSpec build() {
            return new BasicArkivoInfoSpec(name, type, uncompressedSize, modifiedTime, metadata);
        }
    }
}
