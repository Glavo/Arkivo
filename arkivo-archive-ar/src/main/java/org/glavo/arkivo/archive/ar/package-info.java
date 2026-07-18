// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Reads and writes Unix AR archives.
///
/// [org.glavo.arkivo.archive.ar.ArArkivoFileSystem] provides indexed NIO access and complete-rewrite updates.
/// [org.glavo.arkivo.archive.ar.ArArkivoStreamingReader] and
/// [org.glavo.arkivo.archive.ar.ArArkivoStreamingWriter] process members in archive order. Readers resolve GNU filename
/// tables and BSD extended names and omit archive symbol-table members from the logical entry sequence. Writers use a
/// fixed header name when representable and a BSD extended name otherwise.
///
/// Rewriting an archive omits linker symbol indexes because member offsets change; applications that require an index
/// must generate one after the rewritten archive has been published.
@NotNullByDefault
package org.glavo.arkivo.archive.ar;

import org.jetbrains.annotations.NotNullByDefault;
