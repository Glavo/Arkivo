// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Computes checksums over arrays and byte buffers.
///
/// [Checksums] computes a result in one call or creates a [ChecksumAccumulator] for incremental input.
/// Accumulators are not safe for concurrent use. [ChecksumValue] stores the result as immutable bytes.
/// [ChecksumAccumulator.UpTo64Bits] and its width-specific subinterfaces expose primitive results.
@NotNullByDefault
package org.glavo.arkivo.checksum;

import org.jetbrains.annotations.NotNullByDefault;
