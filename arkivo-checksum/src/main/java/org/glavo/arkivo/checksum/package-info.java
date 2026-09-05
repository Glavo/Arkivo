// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Computes checksums over arrays and byte buffers.
///
/// [ChecksumAlgorithm] computes a result in one call or creates a [ChecksumAccumulator] for incremental input.
/// Algorithm objects are immutable and safe to share; accumulators are not safe for concurrent use. [ChecksumValue]
/// stores the result as immutable bytes. Numeric algorithms also expose primitive results through
/// [ChecksumAlgorithm.UpTo64Bits] and its width-specific subinterfaces.
@NotNullByDefault
package org.glavo.arkivo.checksum;

import org.jetbrains.annotations.NotNullByDefault;
