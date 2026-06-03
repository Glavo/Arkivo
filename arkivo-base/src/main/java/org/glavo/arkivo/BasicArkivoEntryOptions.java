// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.FileTime;

/// Provides general-purpose archive entry options.
///
/// @param rawPath the raw encoded entry path bytes
/// @param path the decoded entry path text
/// @param type the requested entry type
/// @param uncompressedSize the expected uncompressed size
/// @param modifiedTime the requested last modified time
/// @param metadata additional metadata requested for the entry
@NotNullByDefault
public record BasicArkivoEntryOptions(
        byte @Unmodifiable [] rawPath,
        String path,
        ArkivoItemType type,
        @Nullable Long uncompressedSize,
        @Nullable FileTime modifiedTime,
        ArkivoMetadata metadata
) implements ArkivoEntryOptions {
    /// Creates basic archive entry options.
    public BasicArkivoEntryOptions {
        rawPath = rawPath.clone();
    }

    /// Creates a builder for an entry with the given decoded path.
    public static Builder builder(String path) {
        return new Builder(path.getBytes(StandardCharsets.UTF_8), path);
    }

    /// Creates a builder for an entry with the given raw encoded path and decoded path.
    public static Builder builder(byte @Unmodifiable [] rawPath, String path) {
        return new Builder(rawPath, path);
    }

    /// Returns the raw encoded entry path bytes.
    @Override
    public byte @Unmodifiable [] rawPath() {
        return rawPath.clone();
    }

    /// Builds `BasicArkivoEntryOptions` instances.
    @NotNullByDefault
    public static final class Builder {
        /// The raw encoded entry path bytes.
        private final byte @Unmodifiable [] rawPath;

        /// The decoded entry path text.
        private final String path;

        /// The requested entry type.
        private ArkivoItemType type = ArkivoItemType.REGULAR_FILE;

        /// The expected uncompressed size.
        private @Nullable Long uncompressedSize;

        /// The requested last modified time.
        private @Nullable FileTime modifiedTime;

        /// Additional metadata requested for the entry.
        private ArkivoMetadata metadata = ArkivoMetadata.empty();

        /// Creates a builder for an entry with the given raw encoded path and decoded path.
        public Builder(byte @Unmodifiable [] rawPath, String path) {
            this.rawPath = rawPath.clone();
            this.path = path;
        }

        /// Sets the entry type.
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

        /// Builds the entry options.
        public BasicArkivoEntryOptions build() {
            return new BasicArkivoEntryOptions(rawPath, path, type, uncompressedSize, modifiedTime, metadata);
        }
    }
}
