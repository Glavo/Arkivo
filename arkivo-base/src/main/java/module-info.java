// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Defines the shared Arkivo API contracts.
@SuppressWarnings("module")
module org.glavo.arkivo.base {
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo;
    exports org.glavo.arkivo.compress;
    exports org.glavo.arkivo.internal to org.glavo.arkivo.archives.zip;

    uses org.glavo.arkivo.ArkivoFormat;
    uses org.glavo.arkivo.compress.CompressionCodec;
}
