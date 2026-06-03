// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoEntryOptions;
import org.glavo.arkivo.ArkivoItemType;
import org.glavo.arkivo.ArkivoMetadata;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.FileTime;

/// Describes metadata and policies requested when writing one ZIP entry.
///
/// @param rawPath the raw encoded ZIP entry path bytes
/// @param path the decoded ZIP entry path text
/// @param type the ZIP entry type
/// @param uncompressedSize the expected uncompressed size
/// @param modifiedTime the requested last modified time
/// @param method the requested ZIP compression method
/// @param encryption the requested ZIP encryption method, or `null` to use the writer default
/// @param metadata additional ZIP metadata
@NotNullByDefault
public record ZipArkivoEntryOptions(
        byte @Unmodifiable [] rawPath,
        String path,
        ArkivoItemType type,
        @Nullable Long uncompressedSize,
        @Nullable FileTime modifiedTime,
        ZipMethod method,
        @Nullable ZipEncryption encryption,
        ArkivoMetadata metadata
) implements ArkivoEntryOptions {
    /// Creates ZIP entry options.
    public ZipArkivoEntryOptions {
        rawPath = rawPath.clone();
    }

    /// Creates a builder for the given decoded ZIP entry path.
    public static Builder builder(String path) {
        return new Builder(path.getBytes(StandardCharsets.UTF_8), path);
    }

    /// Creates a builder for the given raw encoded ZIP entry path and decoded ZIP entry path.
    public static Builder builder(byte @Unmodifiable [] rawPath, String path) {
        return new Builder(rawPath, path);
    }

    /// Returns the raw encoded ZIP entry path bytes.
    @Override
    public byte @Unmodifiable [] rawPath() {
        return rawPath.clone();
    }

    /// Builds `ZipArkivoEntryOptions` instances.
    @NotNullByDefault
    public static final class Builder {
        /// The raw encoded ZIP entry path bytes.
        private final byte @Unmodifiable [] rawPath;

        /// The decoded ZIP entry path text.
        private final String path;

        /// The requested ZIP entry type.
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

        /// Creates a builder for the given raw encoded ZIP entry path and decoded ZIP entry path.
        public Builder(byte @Unmodifiable [] rawPath, String path) {
            this.rawPath = rawPath.clone();
            this.path = path;
        }

        /// Sets the ZIP entry type.
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

        /// Builds the ZIP entry options.
        public ZipArkivoEntryOptions build() {
            return new ZipArkivoEntryOptions(rawPath, path, type, uncompressedSize, modifiedTime, method, encryption, metadata);
        }
    }
}
