// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

import org.glavo.arkivo.sevenzip.SevenZipArkivoEntryAttributeView;
import org.glavo.arkivo.internal.ArkivoFileStoreAttributes;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.FileStoreAttributeView;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.Objects;

/// Describes the synthetic file store exposed by 7z file systems.
@NotNullByDefault
final class SevenZipFileStore extends FileStore {
    /// The shared read-only 7z file store.
    static final SevenZipFileStore READ_ONLY = new SevenZipFileStore(true);

    /// The shared writable 7z file store.
    static final SevenZipFileStore WRITABLE = new SevenZipFileStore(false);

    /// Whether this file store is read-only.
    private final boolean readOnly;

    /// Creates a 7z file store.
    private SevenZipFileStore(boolean readOnly) {
        this.readOnly = readOnly;
    }

    /// Returns the file store name.
    @Override
    public String name() {
        return "7z";
    }

    /// Returns the file store type.
    @Override
    public String type() {
        return "7z";
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
        return type == BasicFileAttributeView.class
                || type == FileOwnerAttributeView.class
                || type == PosixFileAttributeView.class
                || type == SevenZipArkivoEntryAttributeView.class;
    }

    /// Returns whether this file store supports the given attribute view.
    @Override
    public boolean supportsFileAttributeView(String name) {
        Objects.requireNonNull(name, "name");
        return "basic".equals(name) || "owner".equals(name) || "posix".equals(name) || "7z".equals(name);
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
