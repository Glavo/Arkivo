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
@NotNullByDefault
public sealed interface ZipArkivoEntryOptions extends ArkivoEntryOptions permits ZipArkivoEntryOptionsImpl {
    /// Creates a builder for the given decoded ZIP entry path.
    static Builder builder(String path) {
        return new Builder(path.getBytes(StandardCharsets.UTF_8), path);
    }

    /// Creates a builder for the given raw encoded ZIP entry path and decoded ZIP entry path.
    static Builder builder(byte @Unmodifiable [] rawPath, String path) {
        return new Builder(rawPath, path);
    }

    /// Returns the requested ZIP compression method.
    ZipMethod method();

    /// Returns the requested ZIP encryption method, or `null` to use the writer default.
    @Nullable ZipEncryption encryption();

    /// Builds `ZipArkivoEntryOptions` instances.
    @NotNullByDefault
    final class Builder {
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
            return new ZipArkivoEntryOptionsImpl(rawPath, path, type, uncompressedSize, modifiedTime, method, encryption, metadata);
        }
    }
}

/// Stores metadata and policies requested when writing one ZIP entry.
@NotNullByDefault
final class ZipArkivoEntryOptionsImpl implements ZipArkivoEntryOptions {
    /// The raw encoded ZIP entry path bytes.
    private final byte @Unmodifiable [] rawPath;

    /// The decoded ZIP entry path text.
    private final String path;

    /// The requested ZIP entry type.
    private final ArkivoItemType type;

    /// The expected uncompressed size.
    private final @Nullable Long uncompressedSize;

    /// The requested last modified time.
    private final @Nullable FileTime modifiedTime;

    /// The requested ZIP compression method.
    private final ZipMethod method;

    /// The requested ZIP encryption method.
    private final @Nullable ZipEncryption encryption;

    /// Additional ZIP metadata.
    private final ArkivoMetadata metadata;

    /// Creates ZIP entry options.
    ZipArkivoEntryOptionsImpl(
            byte @Unmodifiable [] rawPath,
            String path,
            ArkivoItemType type,
            @Nullable Long uncompressedSize,
            @Nullable FileTime modifiedTime,
            ZipMethod method,
            @Nullable ZipEncryption encryption,
            ArkivoMetadata metadata
    ) {
        this.rawPath = rawPath.clone();
        this.path = path;
        this.type = type;
        this.uncompressedSize = uncompressedSize;
        this.modifiedTime = modifiedTime;
        this.method = method;
        this.encryption = encryption;
        this.metadata = metadata;
    }

    /// Returns the raw encoded ZIP entry path bytes.
    @Override
    public byte @Unmodifiable [] rawPath() {
        return rawPath.clone();
    }

    /// Returns the decoded ZIP entry path text.
    @Override
    public String path() {
        return path;
    }

    /// Returns the requested ZIP entry type.
    @Override
    public ArkivoItemType type() {
        return type;
    }

    /// Returns the expected uncompressed size.
    @Override
    public @Nullable Long uncompressedSize() {
        return uncompressedSize;
    }

    /// Returns the requested last modified time.
    @Override
    public @Nullable FileTime modifiedTime() {
        return modifiedTime;
    }

    /// Returns the requested ZIP compression method.
    @Override
    public ZipMethod method() {
        return method;
    }

    /// Returns the requested ZIP encryption method.
    @Override
    public @Nullable ZipEncryption encryption() {
        return encryption;
    }

    /// Returns additional ZIP metadata.
    @Override
    public ArkivoMetadata metadata() {
        return metadata;
    }
}
