// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Reads supported RAR4 and RAR5 archives.
///
/// [org.glavo.arkivo.archive.rar.RarArkivoFileSystem] indexes entries for read-only NIO access and materializes decoded
/// bodies on demand. [org.glavo.arkivo.archive.rar.RarArkivoStreamingReader] processes entries and split volumes in
/// physical order while retaining the decompression state required by solid archives. Password-protected headers and
/// entries use the provider configured by [org.glavo.arkivo.archive.rar.RarArchiveOptions.Read].
///
/// RAR creation and update are not supported.
@NotNullByDefault
package org.glavo.arkivo.archive.rar;

import org.jetbrains.annotations.NotNullByDefault;
