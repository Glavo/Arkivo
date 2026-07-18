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
    ///
    /// @return the backing archive URI, or `null`
    @Nullable URI archiveUri();

    /// Returns the file store exposed for archive entries.
    ///
    /// @return the archive file store
    FileStore fileStore();

    /// Opens a seekable channel for an archive entry.
    ///
    /// @param path the entry path
    /// @param options the requested open options
    /// @param attributes the attributes to set when creating an entry
    /// @return a new caller-closeable channel positioned at zero
    /// @throws IOException if the entry channel cannot be opened
    SeekableByteChannel newByteChannel(
            Path path,
            Set<? extends OpenOption> options,
            FileAttribute<?>... attributes
    ) throws IOException;

    /// Opens an input stream for an archive entry.
    ///
    /// @param path the entry path
    /// @param options the requested open options
    /// @return a new caller-closeable input stream positioned at the start of the entry body
    /// @throws IOException if the entry stream cannot be opened
    InputStream newInputStream(Path path, OpenOption... options) throws IOException;

    /// Opens an output stream for an archive entry.
    ///
    /// @param path the entry path
    /// @param options the requested open options
    /// @return a new caller-closeable output stream
    /// @throws ReadOnlyFileSystemException if this implementation is read-only
    /// @throws IOException if a writable implementation cannot open the entry stream
    default OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
        throw new ReadOnlyFileSystemException();
    }

    /// Opens a directory stream for an archive directory.
    ///
    /// @param directory the archive directory path
    /// @param filter the entry acceptance filter
    /// @return a new caller-closeable directory stream
    /// @throws IOException if the directory cannot be enumerated
    DirectoryStream<Path> newDirectoryStream(
            Path directory,
            DirectoryStream.Filter<? super Path> filter
    ) throws IOException;

    /// Creates an archive directory.
    ///
    /// @param directory the directory path to create
    /// @param attributes the attributes to set atomically when supported
    /// @throws ReadOnlyFileSystemException if this implementation is read-only
    /// @throws IOException if a writable implementation cannot create the directory
    default void createDirectory(Path directory, FileAttribute<?>... attributes) throws IOException {
        throw new ReadOnlyFileSystemException();
    }

    /// Creates an archive symbolic link.
    ///
    /// @param link the symbolic-link path to create
    /// @param target the stored link target
    /// @param attributes the attributes to set atomically when supported
    /// @throws ReadOnlyFileSystemException if this implementation is read-only
    /// @throws IOException if a writable implementation cannot create the symbolic link
    default void createSymbolicLink(Path link, Path target, FileAttribute<?>... attributes) throws IOException {
        throw new ReadOnlyFileSystemException();
    }

    /// Deletes an archive entry.
    ///
    /// @param path the entry path to delete
    /// @throws ReadOnlyFileSystemException if this implementation is read-only
    /// @throws IOException if a writable implementation cannot delete the entry
    default void delete(Path path) throws IOException {
        throw new ReadOnlyFileSystemException();
    }

    /// Moves an archive entry within this file system.
    ///
    /// @param source the source entry path
    /// @param target the target entry path
    /// @param options the requested move options
    /// @throws ReadOnlyFileSystemException if this implementation is read-only
    /// @throws IOException if a writable implementation cannot move the entry
    default void move(Path source, Path target, CopyOption... options) throws IOException {
        throw new ReadOnlyFileSystemException();
    }

    /// Checks access to an archive entry.
    ///
    /// @param path the entry path
    /// @param modes the access modes to check
    /// @throws IOException if the entry does not exist or the requested access is unavailable
    void checkAccess(Path path, AccessMode... modes) throws IOException;

    /// Returns a requested attribute view, or `null` when the view is unsupported.
    ///
    /// @param <V> the attribute-view type
    /// @param path the entry path
    /// @param type the requested attribute-view class
    /// @param options the link handling options
    /// @return the requested view, or `null` when unsupported
    <V extends FileAttributeView> @Nullable V getFileAttributeView(
            Path path,
            Class<V> type,
            LinkOption... options
    );

    /// Reads typed attributes for an archive entry.
    ///
    /// @param <A> the attribute snapshot type
    /// @param path the entry path
    /// @param type the requested attribute class
    /// @return a stable attribute snapshot
    /// @throws IOException if the entry cannot be resolved or its attributes cannot be read
    <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type) throws IOException;

    /// Reads named attributes for an archive entry.
    ///
    /// @param path the entry path
    /// @param attributes the attribute view and names to read
    /// @return an immutable mapping from requested names to snapshot values
    /// @throws IOException if the entry cannot be resolved or its attributes cannot be read
    Map<String, Object> readAttributes(Path path, String attributes) throws IOException;

    /// Reads the target of an archive symbolic link.
    ///
    /// @param link the symbolic-link path
    /// @return the stored link target path
    /// @throws IOException if the link cannot be resolved or its target cannot be read
    Path readSymbolicLink(Path link) throws IOException;

    /// Sets a named archive-entry attribute.
    ///
    /// @param path the entry path
    /// @param attribute the attribute view and name
    /// @param value the replacement attribute value
    /// @throws ReadOnlyFileSystemException if this implementation is read-only
    /// @throws IOException if a writable implementation cannot update the attribute
    default void setAttribute(Path path, String attribute, Object value) throws IOException {
        throw new ReadOnlyFileSystemException();
    }
}
