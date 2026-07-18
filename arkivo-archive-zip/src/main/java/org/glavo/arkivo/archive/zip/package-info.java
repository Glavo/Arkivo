// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Reads and writes single-volume and split ZIP archives.
///
/// [org.glavo.arkivo.archive.zip.ZipArkivoFileSystem] provides indexed NIO access, writable creation sessions, and
/// complete-rewrite updates. [org.glavo.arkivo.archive.zip.ZipArkivoStreamingReader] consumes local file records in
/// forward order, while [org.glavo.arkivo.archive.zip.ZipArkivoStreamingWriter] emits entries and the central directory
/// sequentially. Split output is published through transactional volume targets.
///
/// The package exposes recognized compression and encryption identifiers, ZIP-specific entry attributes, raw extra
/// fields and comments, and contextual decoding for legacy metadata without an authoritative Unicode representation.
@NotNullByDefault
package org.glavo.arkivo.archive.zip;

import org.jetbrains.annotations.NotNullByDefault;
