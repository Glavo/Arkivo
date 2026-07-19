// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides AR archive file systems and forward-only readers and writers.
///
/// The exported API indexes AR members for NIO access, supports complete-rewrite updates, and reads or writes members
/// sequentially. GNU and BSD long-name representations are accepted when reading; output uses fixed header names or
/// BSD extended names as required.
module org.glavo.arkivo.archive.ar {
    requires transitive org.glavo.arkivo.archive;
    requires org.glavo.arkivo.archive.codec;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.archive.ar;

    provides java.nio.file.spi.FileSystemProvider with org.glavo.arkivo.archive.ar.internal.ArArkivoFileSystemProvider;
}
