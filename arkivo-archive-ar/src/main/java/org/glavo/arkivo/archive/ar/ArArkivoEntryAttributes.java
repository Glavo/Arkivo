// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar;

import org.glavo.arkivo.archive.ArchiveEntryAttributes;
import org.jetbrains.annotations.NotNullByDefault;


/// Exposes metadata parsed from one AR archive member header.
@NotNullByDefault
public interface ArArkivoEntryAttributes extends ArchiveEntryAttributes {
    /// Returns the decoded archive member path.
    String path();

    /// Returns the raw AR member identifier before long-name resolution.
    String identifier();

    /// Returns the numeric user identifier stored by the AR header.
    long userId();

    /// Returns the numeric group identifier stored by the AR header.
    long groupId();

    /// Returns the POSIX mode bits stored by the AR header.
    int mode();
}
