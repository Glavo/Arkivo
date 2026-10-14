// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Computes seeded XXH32 and XXH64 checksums.
///
/// [XXHash32#newAccumulator()] and [XXHash64#newAccumulator()] create zero-seeded computations.
/// Their overloads accept explicit seeds, which remain unchanged when an accumulator is reset.
@NotNullByDefault
package org.glavo.arkivo.checksum.xxhash;

import org.jetbrains.annotations.NotNullByDefault;
