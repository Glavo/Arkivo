// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.internal.ArkivoFileStoreAttributes;
import org.glavo.arkivo.archive.zip.ZipArkivoEntryAttributeView;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileStoreAttributeView;
import java.util.Objects;

/// Describes the synthetic file store exposed by ZIP file systems.
@NotNullByDefault
final class ZipFileStore extends FileStore {
    /// The shared read-only ZIP file store.
    static final ZipFileStore READ_ONLY = new ZipFileStore(true);

    /// The shared writable ZIP file store.
    static final ZipFileStore WRITABLE = new ZipFileStore(false);

    /// Whether this file store rejects write operations.
    private final boolean readOnly;

    /// Creates a ZIP file store with the requested mutability.
    private ZipFileStore(boolean readOnly) {
        this.readOnly = readOnly;
    }

    /// Returns the file store name.
    @Override
    public String name() {
        return "zip";
    }

    /// Returns the file store type.
    @Override
    public String type() {
        return "zip";
    }

    /// Returns whether this file store is read-only.
    @Override
    public boolean isReadOnly() {
        return readOnly;
    }

    /// Returns an unknown total space value.
    @Override
    public long getTotalSpace() {
        return 0;
    }

    /// Returns an unknown usable space value.
    @Override
    public long getUsableSpace() {
        return 0;
    }

    /// Returns an unknown unallocated space value.
    @Override
    public long getUnallocatedSpace() {
        return 0;
    }

    /// Returns whether this file store supports the given attribute view.
    @Override
    public boolean supportsFileAttributeView(Class<? extends FileAttributeView> type) {
        Objects.requireNonNull(type, "type");
        return type == BasicFileAttributeView.class || type == ZipArkivoEntryAttributeView.class;
    }

    /// Returns whether this file store supports the given attribute view.
    @Override
    public boolean supportsFileAttributeView(String name) {
        Objects.requireNonNull(name, "name");
        return "basic".equals(name) || "zip".equals(name);
    }

    /// Returns no file store attribute view.
    @Override
    public <V extends FileStoreAttributeView> @Nullable V getFileStoreAttributeView(Class<V> type) {
        Objects.requireNonNull(type, "type");
        return null;
    }

    /// Returns no file store attribute values.
    @Override
    public Object getAttribute(String attribute) throws IOException {
        return ArkivoFileStoreAttributes.get(this, attribute);
    }
}
