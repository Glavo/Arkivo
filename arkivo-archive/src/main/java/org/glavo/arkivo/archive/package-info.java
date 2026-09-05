// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Reads and writes archives as file systems or sequences of entries.
///
/// [ArkivoFormats] locates installed formats and opens archives. [ArkivoFileSystem] provides path-based access through
/// Java NIO; [ArkivoStreamingReader] and [ArkivoStreamingWriter] process entries in order. [ArkivoVolumeSource] and
/// [ArkivoVolumeTarget] provide access to split archives.
///
/// Format descriptors are immutable and safe to share. Readers and writers must not be used concurrently unless their
/// documentation permits it. File-system synchronization is controlled by [ArkivoFileSystemThreadSafety].
/// Factory methods specify which sources or targets are owned by the returned object and must be closed with it.
@NotNullByDefault
package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
