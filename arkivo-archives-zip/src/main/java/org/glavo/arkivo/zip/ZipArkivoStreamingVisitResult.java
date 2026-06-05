// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.jetbrains.annotations.NotNullByDefault;

/// Controls whether a ZIP streaming reader continues after visiting an entry.
@NotNullByDefault
public enum ZipArkivoStreamingVisitResult {
    /// Continue reading the next ZIP entry in storage order.
    CONTINUE,

    /// Stop reading entries and leave the reader positioned at the current entry.
    TERMINATE
}
