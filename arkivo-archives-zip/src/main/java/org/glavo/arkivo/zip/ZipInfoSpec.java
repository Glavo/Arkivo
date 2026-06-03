// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoInfoSpec;
import org.glavo.arkivo.ArkivoItemType;
import org.glavo.arkivo.ArkivoMetadata;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.attribute.FileTime;

/// Describes metadata requested when writing one ZIP item.
///
/// @param zipName the ZIP item name
/// @param type the ZIP item type
/// @param uncompressedSize the expected uncompressed size
/// @param modifiedTime the requested last modified time
/// @param method the requested ZIP compression method
/// @param encryption the requested ZIP encryption method, or `null` to use the writer default
/// @param metadata additional ZIP metadata
@NotNullByDefault
public record ZipInfoSpec(
        ZipName zipName,
        ArkivoItemType type,
        @Nullable Long uncompressedSize,
        @Nullable FileTime modifiedTime,
        ZipMethod method,
        @Nullable ZipEncryption encryption,
        ArkivoMetadata metadata
) implements ArkivoInfoSpec {
    /// Creates a builder for the given ZIP item name.
    public static Builder builder(ZipName name) {
        return new Builder(name);
    }

    /// Returns the raw encoded ZIP item path bytes.
    @Override
    public byte @Unmodifiable [] rawPath() {
        return zipName.rawPath();
    }

    /// Returns the decoded ZIP item path text.
    @Override
    public String path() {
        return zipName.path();
    }

    /// Builds `ZipInfoSpec` instances.
    @NotNullByDefault
    public static final class Builder {
        /// The ZIP item name.
        private final ZipName name;

        /// The requested ZIP item type.
        private ArkivoItemType type = ArkivoItemType.REGULAR_FILE;

        /// The expected uncompressed size.
        private @Nullable Long uncompressedSize;

        /// The requested last modified time.
        private @Nullable FileTime modifiedTime;

        /// The requested ZIP compression method.
        private ZipMethod method = ZipMethod.deflated();

        /// The requested ZIP encryption method.
        private @Nullable ZipEncryption encryption;

        /// Additional ZIP metadata.
        private ArkivoMetadata metadata = ArkivoMetadata.empty();

        /// Creates a builder for the given ZIP item name.
        public Builder(ZipName name) {
            this.name = name;
        }

        /// Sets the ZIP item type.
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

        /// Sets the requested ZIP compression method.
        public Builder method(ZipMethod method) {
            this.method = method;
            return this;
        }

        /// Sets the requested ZIP encryption method.
        public Builder encryption(@Nullable ZipEncryption encryption) {
            this.encryption = encryption;
            return this;
        }

        /// Sets additional ZIP metadata.
        public Builder metadata(ArkivoMetadata metadata) {
            this.metadata = metadata;
            return this;
        }

        /// Builds the ZIP metadata specification.
        public ZipInfoSpec build() {
            return new ZipInfoSpec(name, type, uncompressedSize, modifiedTime, method, encryption, metadata);
        }
    }
}
