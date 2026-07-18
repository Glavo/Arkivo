// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar;

import org.glavo.arkivo.archive.ArchiveEntryAttributes;
import org.jetbrains.annotations.NotNullByDefault;


/// Exposes metadata from one AR archive member header.
///
/// Instances read from an indexed file system or streaming reader are stable snapshots. A pending streaming-writer
/// view exposes a read-only projection of the member being configured and can reflect later attribute-view mutations
/// until that member is committed.
@NotNullByDefault
public interface ArArkivoEntryAttributes extends ArchiveEntryAttributes {
    /// Returns the decoded archive member path.
    ///
    /// @return the logical member path
    String path();

    /// Returns the raw AR member identifier before long-name resolution.
    ///
    /// @return the identifier field or resolved long-name representation recorded for this member
    String identifier();

    /// Returns the numeric user identifier stored by the AR header.
    ///
    /// @return the non-negative user identifier
    long userId();

    /// Returns the numeric group identifier stored by the AR header.
    ///
    /// @return the non-negative group identifier
    long groupId();

    /// Returns the POSIX mode bits stored by the AR header.
    ///
    /// @return the non-negative mode value, including file-type and permission bits
    int mode();
}
