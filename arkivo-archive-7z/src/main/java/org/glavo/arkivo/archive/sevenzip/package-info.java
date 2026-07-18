// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Reads and writes 7z archives.
///
/// [org.glavo.arkivo.archive.sevenzip.SevenZipArkivoFileSystem] provides indexed NIO access to single-volume and
/// numbered split archives and performs complete-rewrite updates. Read-only file systems materialize compressed entry
/// bodies on demand; writable sessions publish their finalized archive when closed.
/// [org.glavo.arkivo.archive.sevenzip.SevenZipArkivoStreamingWriter] accepts entries in forward order but stages the
/// archive in seekable storage because the 7z signature and next header are finalized after the entry data.
///
/// Output compression and preprocessing are selected with
/// [org.glavo.arkivo.archive.sevenzip.SevenZipCompression] and
/// [org.glavo.arkivo.archive.sevenzip.SevenZipFilterChain]. The public coder graph and packed-stream types describe
/// physical input ranges and decoded folder topology without exposing mutable parser state.
@NotNullByDefault
package org.glavo.arkivo.archive.sevenzip;

import org.jetbrains.annotations.NotNullByDefault;
