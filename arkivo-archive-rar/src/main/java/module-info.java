// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides read-only RAR archive file systems and forward-only readers.
///
/// The exported API reads supported RAR4 and RAR5 compression, solid archives, conventional split volumes, and
/// supported legacy and AES encryption. This module does not create or update RAR archives.
module org.glavo.arkivo.archive.rar {
    requires transitive org.glavo.arkivo.archive;
    requires org.glavo.arkivo.base;
    requires org.glavo.arkivo.codec.ppmd;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.archive.rar;

    provides java.nio.file.spi.FileSystemProvider with org.glavo.arkivo.archive.rar.internal.RarArkivoFileSystemProvider;
}
