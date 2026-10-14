// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides SHA-256 digest computations using the shared checksum contracts.
module org.glavo.arkivo.checksum.sha2 {
    requires transitive org.glavo.arkivo.checksum;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.checksum.sha2;
}
