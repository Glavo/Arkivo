// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.attribute.BasicFileAttributeView;

/// Provides AR-specific attributes for an archive member.
@NotNullByDefault
public interface ArArkivoEntryAttributeView extends BasicFileAttributeView {
    /// Returns the attribute view name.
    @Override
    default String name() {
        return "ar";
    }

    /// Reads the AR-specific member attributes.
    @Override
    ArArkivoEntryAttributes readAttributes() throws IOException;

    /// Sets the numeric user identifier stored by the AR header.
    void setUserId(long userId) throws IOException;

    /// Sets the numeric group identifier stored by the AR header.
    void setGroupId(long groupId) throws IOException;

    /// Sets the POSIX mode bits stored by the AR header.
    void setMode(int mode) throws IOException;

    /// Sets the expected member data size before the member body stream is opened.
    void setSize(long size) throws IOException;
}
