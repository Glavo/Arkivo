// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.glavo.arkivo.internal.ArkivoFileStoreAttributes;
import org.glavo.arkivo.zip.ZipArkivoEntryAttributeView;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileStoreAttributeView;
import java.util.Objects;

/// Describes the synthetic file store exposed by streaming ZIP file systems.
@NotNullByDefault
final class StreamingZipFileStore extends FileStore {
    /// The shared read-only streaming ZIP file store.
    static final StreamingZipFileStore READ_ONLY = new StreamingZipFileStore(true);

    /// The shared writable streaming ZIP file store.
    static final StreamingZipFileStore WRITABLE = new StreamingZipFileStore(false);

    /// Whether this file store is read-only.
    private final boolean readOnly;

    /// Creates a streaming ZIP file store.
    private StreamingZipFileStore(boolean readOnly) {
        this.readOnly = readOnly;
    }

    /// Returns the file store name.
    @Override
    public String name() {
        return "zip-stream";
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
