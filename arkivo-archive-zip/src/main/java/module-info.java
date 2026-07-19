// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides ZIP archive file systems and forward-only readers and writers.
///
/// The exported API reads, creates, and completely rewrites single-volume or split ZIP archives. It supports stored
/// and installed compression methods, traditional and WinZip AES encryption, raw ZIP metadata, and transactional
/// multi-volume publication.
module org.glavo.arkivo.archive.zip {
    requires transitive org.glavo.arkivo.archive;
    requires org.glavo.arkivo.archive.codec;
    requires org.glavo.arkivo.base;
    requires org.glavo.arkivo.codec;
    requires org.glavo.arkivo.codec.deflate;
    requires static org.glavo.arkivo.codec.lzma;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.archive.zip;

    provides java.nio.file.spi.FileSystemProvider with org.glavo.arkivo.archive.zip.internal.ZipArkivoFileSystemProvider;
}
