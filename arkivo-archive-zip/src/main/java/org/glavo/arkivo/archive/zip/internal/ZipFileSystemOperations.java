// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.util.Map;
import java.util.Set;

/// Defines the provider-facing operations shared by ZIP file-system implementations.
///
/// This boundary lets the provider dispatch by capability instead of enumerating read-only and writable concrete
/// implementations. Mutating operations reject calls by default so read-only implementations only declare the
/// operations they actually support.
@NotNullByDefault
interface ZipFileSystemOperations {
    /// Returns the underlying archive URI, or `null` when no stable archive URI exists.
    @Nullable URI archiveUri();

    /// Returns the file store exposed for archive entries.
    FileStore fileStore();

    /// Opens a seekable channel for an archive entry.
    SeekableByteChannel newByteChannel(
            Path path,
            Set<? extends OpenOption> options,
            FileAttribute<?>... attributes
    ) throws IOException;

    /// Opens an input stream for an archive entry.
    InputStream newInputStream(Path path, OpenOption... options) throws IOException;

    /// Opens an output stream for an archive entry.
    default OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
        throw new ReadOnlyFileSystemException();
    }

    /// Opens a directory stream for an archive directory.
    DirectoryStream<Path> newDirectoryStream(
            Path directory,
            DirectoryStream.Filter<? super Path> filter
    ) throws IOException;

    /// Creates an archive directory.
    default void createDirectory(Path directory, FileAttribute<?>... attributes) throws IOException {
        throw new ReadOnlyFileSystemException();
    }

    /// Creates an archive symbolic link.
    default void createSymbolicLink(Path link, Path target, FileAttribute<?>... attributes) throws IOException {
        throw new ReadOnlyFileSystemException();
    }

    /// Deletes an archive entry.
    default void delete(Path path) throws IOException {
        throw new ReadOnlyFileSystemException();
    }

    /// Moves an archive entry within this file system.
    default void move(Path source, Path target, CopyOption... options) throws IOException {
        throw new ReadOnlyFileSystemException();
    }

    /// Checks access to an archive entry.
    void checkAccess(Path path, AccessMode... modes) throws IOException;

    /// Returns a requested attribute view, or `null` when the view is unsupported.
    <V extends FileAttributeView> @Nullable V getFileAttributeView(
            Path path,
            Class<V> type,
            LinkOption... options
    );

    /// Reads typed attributes for an archive entry.
    <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type) throws IOException;

    /// Reads named attributes for an archive entry.
    Map<String, Object> readAttributes(Path path, String attributes) throws IOException;

    /// Reads the target of an archive symbolic link.
    Path readSymbolicLink(Path link) throws IOException;

    /// Sets a named archive-entry attribute.
    default void setAttribute(Path path, String attribute, Object value) throws IOException {
        throw new ReadOnlyFileSystemException();
    }
}
