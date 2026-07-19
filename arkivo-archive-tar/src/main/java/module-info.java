// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides TAR archive file systems and forward-only readers and writers.
///
/// The exported API indexes TAR entries for NIO access, supports complete-rewrite updates, and reads or writes entries
/// sequentially. Installed Arkivo codecs can wrap the TAR byte stream in an outer compression format; indexed codecs
/// permit lazy random access to contiguous entry bodies without expanding the complete archive.
module org.glavo.arkivo.archive.tar {
    requires transitive org.glavo.arkivo.archive;
    requires org.glavo.arkivo.archive.codec;
    requires transitive org.glavo.arkivo.codec;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.archive.tar;

    provides java.nio.file.spi.FileSystemProvider with org.glavo.arkivo.archive.tar.internal.TarArkivoFileSystemProvider;
}
