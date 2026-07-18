// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.attribute.BasicFileAttributeView;

/// Reads and configures AR-specific attributes for an archive member.
///
/// Mutators are supported by update-enabled indexed file systems and by the current pending streaming-writer member.
/// They fail for read-only file systems and after a pending member has been committed. Indexed reads are snapshots;
/// attributes read from a pending streaming member can reflect subsequent mutations until commit.
@NotNullByDefault
public interface ArArkivoEntryAttributeView extends BasicFileAttributeView {
    /// Returns the attribute view name.
    @Override
    default String name() {
        return "ar";
    }

    /// Reads the AR-specific member attributes for the current view state.
    @Override
    ArArkivoEntryAttributes readAttributes() throws IOException;

    /// Sets the non-negative numeric user identifier stored by the AR header.
    ///
    /// @param userId the new user identifier
    /// @throws IllegalArgumentException if `userId` is negative
    /// @throws IOException if the attribute cannot be changed in the current file-system or entry state
    void setUserId(long userId) throws IOException;

    /// Sets the non-negative numeric group identifier stored by the AR header.
    ///
    /// @param groupId the new group identifier
    /// @throws IllegalArgumentException if `groupId` is negative
    /// @throws IOException if the attribute cannot be changed in the current file-system or entry state
    void setGroupId(long groupId) throws IOException;

    /// Sets the non-negative POSIX mode bits stored by the AR header without changing the member file type.
    ///
    /// @param mode the replacement mode, including file-type and permission bits
    /// @throws IllegalArgumentException if `mode` is negative or describes an incompatible member type
    /// @throws IOException if the attribute cannot be changed in the current file-system or entry state
    void setMode(int mode) throws IOException;

    /// Sets or changes the non-negative member data size.
    ///
    /// For a pending streaming member, this declares the expected body size before the body is opened; a fixed body
    /// must have exactly that size. In an update file system, this resizes an existing regular-file body by truncating
    /// it or zero-extending it, and fails while a replacement channel for that path is active.
    ///
    /// @param size the expected or replacement body size, in bytes
    /// @throws IllegalArgumentException if `size` is negative or exceeds the supported AR member-size range
    /// @throws IOException if a fixed body has a different size or the body cannot be resized in the current state
    void setSize(long size) throws IOException;
}
