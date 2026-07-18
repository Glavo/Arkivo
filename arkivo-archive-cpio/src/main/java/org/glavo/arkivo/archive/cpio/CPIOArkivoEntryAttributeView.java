// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.cpio;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.attribute.BasicFileAttributeView;

/// Configures CPIO-specific metadata for one pending streaming entry.
@NotNullByDefault
public interface CPIOArkivoEntryAttributeView extends BasicFileAttributeView {
    /// Returns the attribute view name.
    @Override
    default String name() {
        return "cpio";
    }

    /// Reads a snapshot of the pending CPIO entry metadata.
    @Override
    CPIOArkivoEntryAttributes readAttributes() throws IOException;

    /// Sets the inode number.
    void setInode(long inode) throws IOException;

    /// Sets the numeric user identifier.
    void setUserId(long userId) throws IOException;

    /// Sets the numeric group identifier.
    void setGroupId(long groupId) throws IOException;

    /// Sets the link count.
    void setLinkCount(long linkCount) throws IOException;

    /// Sets the POSIX mode and file-type bits.
    void setMode(int mode) throws IOException;

    /// Sets the legacy device field used by old CPIO dialects.
    void setDevice(long device) throws IOException;

    /// Sets the legacy remote-device field used by old CPIO dialects.
    void setRemoteDevice(long remoteDevice) throws IOException;

    /// Sets the device major and minor numbers used by new ASCII dialects.
    void setDeviceNumbers(long major, long minor) throws IOException;

    /// Sets the remote-device major and minor numbers used by new ASCII dialects.
    void setRemoteDeviceNumbers(long major, long minor) throws IOException;

    /// Sets the expected entry data size before its body is opened.
    void setSize(long size) throws IOException;
}
