// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Defines the shared Arkivo API contracts.
module org.glavo.arkivo.base {
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo;
    exports org.glavo.arkivo.compress;

    uses org.glavo.arkivo.ArkivoFormat;
}
