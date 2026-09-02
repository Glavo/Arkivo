// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides read-only Apple UDIF disk images and HFS Plus archive file systems.
module org.glavo.arkivo.archive.dmg {
    requires transitive org.glavo.arkivo.archive;
    requires org.glavo.arkivo.base;
    requires org.glavo.arkivo.checksum;
    requires org.glavo.arkivo.codec;
    requires org.glavo.arkivo.codec.bzip2;
    requires org.glavo.arkivo.codec.deflate;
    requires org.glavo.arkivo.codec.xz;
    requires java.xml;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.archive.dmg;

    provides java.nio.file.spi.FileSystemProvider with
            org.glavo.arkivo.archive.dmg.internal.DMGArkivoFileSystemProvider;
}
