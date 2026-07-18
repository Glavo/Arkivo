// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Reads and writes CPIO archives in forward order.
///
/// [org.glavo.arkivo.archive.cpio.CPIOArkivoStreamingReader] recognizes the standardized new ASCII, checksum new ASCII,
/// old portable ASCII, and old binary header forms. [org.glavo.arkivo.archive.cpio.CPIOArkivoStreamingWriter] emits one
/// selected dialect, writes the `TRAILER!!!` record on close, and pads the completed archive to the configured block
/// size. This package does not expose a random-access file system.
@NotNullByDefault
package org.glavo.arkivo.archive.cpio;

import org.jetbrains.annotations.NotNullByDefault;
