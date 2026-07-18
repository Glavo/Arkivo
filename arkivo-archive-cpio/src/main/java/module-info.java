// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides forward-only CPIO archive readers and writers.
///
/// The exported API supports new ASCII, new ASCII with checksum, old portable ASCII, and old binary headers. CPIO is
/// exposed as a streaming format; this module does not provide an NIO archive file system.
module org.glavo.arkivo.archive.cpio {
    requires transitive org.glavo.arkivo.archive;
    requires org.glavo.arkivo.base;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.archive.cpio;

    provides org.glavo.arkivo.archive.ArkivoFormat with org.glavo.arkivo.archive.cpio.CPIOArkivoFormat;
}
