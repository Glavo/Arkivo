// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides ZIP archive APIs for Arkivo.
module org.glavo.arkivo.archives.zip {
    requires org.glavo.arkivo.base;
    requires org.apache.commons.compress;
    requires com.github.luben.zstd_jni;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.zip;

    provides org.glavo.arkivo.ArkivoFormat with org.glavo.arkivo.zip.ZipArkivoFormat;
}
