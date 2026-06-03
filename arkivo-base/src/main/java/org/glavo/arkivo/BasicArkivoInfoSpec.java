// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.FileTime;

/// Provides a general-purpose archive item metadata specification.
///
/// @param rawPath the raw encoded item path bytes
/// @param path the decoded item path text
/// @param type the item type
/// @param uncompressedSize the expected uncompressed size
/// @param modifiedTime the requested last modified time
/// @param metadata additional metadata requested for the item
@NotNullByDefault
public record BasicArkivoInfoSpec(
        byte @Unmodifiable [] rawPath,
        String path,
        ArkivoItemType type,
        @Nullable Long uncompressedSize,
        @Nullable FileTime modifiedTime,
        ArkivoMetadata metadata
) implements ArkivoInfoSpec {
    /// Creates a basic archive item metadata specification.
    public BasicArkivoInfoSpec {
        rawPath = rawPath.clone();
    }

    /// Creates a builder for an item with the given decoded path.
    public static Builder builder(String path) {
        return new Builder(path.getBytes(StandardCharsets.UTF_8), path);
    }

    /// Creates a builder for an item with the given raw encoded path and decoded path.
    public static Builder builder(byte @Unmodifiable [] rawPath, String path) {
        return new Builder(rawPath, path);
    }

    /// Returns the raw encoded item path bytes.
    @Override
    public byte @Unmodifiable [] rawPath() {
        return rawPath.clone();
    }

    /// Builds `BasicArkivoInfoSpec` instances.
    @NotNullByDefault
    public static final class Builder {
        /// The raw encoded item path bytes.
        private final byte @Unmodifiable [] rawPath;

        /// The decoded item path text.
        private final String path;

        /// The requested item type.
        private ArkivoItemType type = ArkivoItemType.REGULAR_FILE;

        /// The expected uncompressed size.
        private @Nullable Long uncompressedSize;

        /// The requested last modified time.
        private @Nullable FileTime modifiedTime;

        /// Additional metadata requested for the item.
        private ArkivoMetadata metadata = ArkivoMetadata.empty();

        /// Creates a builder for an item with the given raw encoded path and decoded path.
        public Builder(byte @Unmodifiable [] rawPath, String path) {
            this.rawPath = rawPath.clone();
            this.path = path;
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
            return new BasicArkivoInfoSpec(rawPath, path, type, uncompressedSize, modifiedTime, metadata);
        }
    }
}
