// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Reads and writes TAR archives, optionally wrapped by an Arkivo compression codec.
///
/// [org.glavo.arkivo.archive.tar.TarArkivoFileSystem] provides indexed NIO access and complete-rewrite updates.
/// [org.glavo.arkivo.archive.tar.TarArkivoStreamingReader] and
/// [org.glavo.arkivo.archive.tar.TarArkivoStreamingWriter] process entries in archive order. Readers apply GNU and PAX
/// metadata and expose supported sparse entries as expanded logical files; a rewrite preserves expanded content rather
/// than the original sparse record layout.
@NotNullByDefault
package org.glavo.arkivo.archive.tar;

import org.jetbrains.annotations.NotNullByDefault;
