// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides raw PPMd7 compression-model support.
///
/// The exported [org.glavo.arkivo.codec.ppmd] package models PPMd7 payloads whose order, model memory, and decoded size
/// are supplied by an embedding container.
@SuppressWarnings("module")
module org.glavo.arkivo.codec.ppmd {
    requires transitive org.glavo.arkivo.codec;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.codec.ppmd;
    exports org.glavo.arkivo.codec.ppmd.internal to org.glavo.arkivo.archive.rar;
}
