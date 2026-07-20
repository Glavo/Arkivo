// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Defines reusable checksum algorithms, per-computation accumulators, and immutable checksum values.
///
/// Algorithm objects contain only reusable configuration. Mutable progress belongs to accumulators returned by
/// [org.glavo.arkivo.checksum.ChecksumAlgorithm#newAccumulator()].
@NotNullByDefault
package org.glavo.arkivo.checksum;

import org.jetbrains.annotations.NotNullByDefault;
