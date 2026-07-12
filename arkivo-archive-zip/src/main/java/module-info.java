// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides ZIP archive APIs for Arkivo.
module org.glavo.arkivo.archive.zip {
    requires org.glavo.arkivo.archive;
    requires static org.glavo.arkivo.codec.bzip2;
    requires static org.glavo.arkivo.codec.deflate64;
    requires static org.glavo.arkivo.codec.lzma;
    requires static org.glavo.arkivo.codec.xz;
    requires static com.github.luben.zstd_jni;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.archive.zip;

    provides org.glavo.arkivo.archive.ArkivoFormat with org.glavo.arkivo.archive.zip.ZipArkivoFormat;
}
