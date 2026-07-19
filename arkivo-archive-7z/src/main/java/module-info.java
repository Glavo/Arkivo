// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides 7z archive file systems and forward-only writers.
///
/// The exported API reads single-volume and numbered split archives through NIO file systems, creates and rewrites
/// archives through writable file systems, and writes sequential entries through a streaming writer. Supported coder
/// graphs, output compression, preprocessing filters, solid folders, AES-protected content and headers, and
/// transactional multi-volume publication are described by the exported `sevenzip` package.
module org.glavo.arkivo.archive.sevenzip {
    requires transitive org.glavo.arkivo.archive;
    requires org.glavo.arkivo.archive.codec;
    requires org.glavo.arkivo.base;
    requires org.glavo.arkivo.codec;
    requires org.glavo.arkivo.codec.lzma;
    requires org.glavo.arkivo.codec.xz;
    requires static org.glavo.arkivo.codec.ppmd;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.archive.sevenzip;

    provides java.nio.file.spi.FileSystemProvider with
            org.glavo.arkivo.archive.sevenzip.internal.SevenZipArkivoFileSystemProvider;
}
