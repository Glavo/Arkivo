// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.ArkivoFileSystemOption;
import org.glavo.arkivo.ArkivoPasswordProvider;
import org.glavo.arkivo.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.ArkivoVolumeSource;
import org.glavo.arkivo.zip.internal.StreamingZipArkivoReadFileSystemImpl;
import org.glavo.arkivo.zip.internal.StreamingZipArkivoFileSystemImpl;
import org.glavo.arkivo.zip.internal.ZipArkivoFileSystemConfig;
import org.glavo.arkivo.zip.internal.ZipArkivoFileSystemImpl;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/// Opens ZIP archives as NIO file systems.
@NotNullByDefault
public abstract sealed class ZipArkivoFileSystem extends ArkivoFileSystem
        permits StreamingZipArkivoFileSystemImpl, StreamingZipArkivoReadFileSystemImpl, ZipArkivoFileSystemImpl {
    /// The environment option for an `ArkivoPasswordProvider` value.
    public static final ArkivoFileSystemOption<ArkivoPasswordProvider> PASSWORD_PROVIDER =
            ArkivoFileSystemOption.of("arkivo.zip", "passwordProvider", ArkivoPasswordProvider.class);

    /// The environment option for a fixed `byte[]` password value.
    public static final ArkivoFileSystemOption<byte[]> PASSWORD =
            ArkivoFileSystemOption.of("arkivo.zip", "password", byte[].class, ZipArkivoFileSystem::passwordOptionValue);

    /// The environment option for a `ZipEncryption` value used as the default encryption method for new entries.
    public static final ArkivoFileSystemOption<ZipEncryption> DEFAULT_ENCRYPTION =
            ArkivoFileSystemOption.of(
                    "arkivo.zip",
                    "defaultEncryption",
                    ZipEncryption.class,
                    ZipArkivoFileSystem::defaultEncryptionOptionValue
            );

    /// The environment option for a `Long` value that sets the maximum size of each output volume.
    public static final ArkivoFileSystemOption<Long> SPLIT_SIZE =
            ArkivoFileSystemOption.of("arkivo.zip", "splitSize", Long.class, ZipArkivoFileSystem::splitSizeOptionValue);

    /// The environment option for a `ZipEntryNameEncoding` value that controls entry name decoding.
    public static final ArkivoFileSystemOption<ZipEntryNameEncoding> ENTRY_NAME_ENCODING =
            ArkivoFileSystemOption.of(
                    "arkivo.zip",
                    "entryNameEncoding",
                    ZipEntryNameEncoding.class,
                    ZipArkivoFileSystem::entryNameEncodingOptionValue
            );

    /// Creates a ZIP archive file system base instance.
    protected ZipArkivoFileSystem(ArkivoFileSystemThreadSafety threadSafety) {
        super(threadSafety);
    }

    /// Opens a ZIP archive file system.
    public static ZipArkivoFileSystem open(Path path) throws IOException {
        return open(path, Map.of());
    }

    /// Opens a ZIP archive file system with environment options.
    public static ZipArkivoFileSystem open(Path path, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(environment, "environment");
        return ZipArkivoFileSystemProvider.instance().newFileSystem(path, environment);
    }

    /// Opens a split ZIP archive file system.
    public static ZipArkivoFileSystem open(ArkivoVolumeSource volumes) throws IOException {
        return open(volumes, Map.of());
    }

    /// Opens a split ZIP archive file system with environment options.
    public static ZipArkivoFileSystem open(ArkivoVolumeSource volumes, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(volumes, "volumes");
        Objects.requireNonNull(environment, "environment");
        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromEnvironment(environment);
        return new ZipArkivoFileSystemImpl(ZipArkivoFileSystemProvider.instance(), null, volumes, config);
    }

    /// Returns the number of bytes stored before the ZIP archive body.
    public abstract long preambleSize() throws IOException;

    /// Opens a read-only channel over the bytes stored before the ZIP archive body.
    public abstract SeekableByteChannel openPreambleChannel() throws IOException;

    /// Converts a raw password option value.
    private static byte[] passwordOptionValue(Object value) {
        if (value instanceof byte[] password) {
            return password;
        }
        throw new IllegalArgumentException("Expected byte[] for key: " + PASSWORD.key());
    }

    /// Converts a raw default encryption option value.
    private static ZipEncryption defaultEncryptionOptionValue(Object value) {
        if (value instanceof ZipEncryption encryption) {
            return encryption;
        }
        if (value instanceof String stringValue) {
            return ZipEncryption.of(stringValue);
        }
        throw new IllegalArgumentException("Expected ZipEncryption or String for key: " + DEFAULT_ENCRYPTION.key());
    }

    /// Converts a raw split size option value.
    private static Long splitSizeOptionValue(Object value) {
        if (value instanceof Long longValue) {
            return longValue;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
            return ((Number) value).longValue();
        }
        if (value instanceof String stringValue) {
            return Long.parseLong(stringValue);
        }
        throw new IllegalArgumentException(
                "Expected Long, compatible integral number, or String for key: " + SPLIT_SIZE.key()
        );
    }

    /// Converts a raw entry name encoding option value.
    private static ZipEntryNameEncoding entryNameEncodingOptionValue(Object value) {
        if (value instanceof ZipEntryNameEncoding encoding) {
            return encoding;
        }
        if (value instanceof String stringValue) {
            return ZipEntryNameEncoding.parse(stringValue);
        }
        throw new IllegalArgumentException(
                "Expected ZipEntryNameEncoding or String for key: " + ENTRY_NAME_ENCODING.key()
        );
    }
}
