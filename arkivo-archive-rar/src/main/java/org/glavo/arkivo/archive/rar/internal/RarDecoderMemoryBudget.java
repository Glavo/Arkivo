// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.codec.DecompressionMemoryLimitException;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;

/// Accounts for primitive-array payloads retained by one decoder session and its active entry.
/// Objects and their headers, reference arrays, shared tables, stream adapters, and garbage awaiting collection are not accounted for.
/// Access must be serialized with the owning session.
@NotNullByDefault
final class RarDecoderMemoryBudget {
    /// The maximum accounted payload, or `UNLIMITED_SIZE`.
    private final long maximum;

    /// Payload bytes reserved for live working arrays.
    private long used;

    /// Creates an empty budget with a byte limit or `UNLIMITED_SIZE`.
    RarDecoderMemoryBudget(long maximum) {
        if (maximum < ArchiveReadLimits.UNLIMITED_SIZE) {
            throw new IllegalArgumentException("maximum must be UNLIMITED_SIZE or non-negative");
        }
        this.maximum = maximum;
    }

    /// Reserves payload before allocation; rejection leaves the budget unchanged.
    /// The caller must release the reservation if allocation fails.
    void reserve(long bytes) throws IOException {
        if (bytes < 0) {
            throw new IllegalArgumentException("Negative memory reservation");
        }
        if (bytes > Long.MAX_VALUE - used) {
            throw new IOException("RAR decoder working-memory size overflows");
        }
        long required = used + bytes;
        if (maximum >= 0 && required > maximum) {
            throw new DecompressionMemoryLimitException(maximum, required);
        }
        used = required;
    }

    /// Releases payload after its working arrays are discarded or leave the decoding operation.
    void release(long bytes) {
        if (bytes < 0 || bytes > used) {
            throw new IllegalArgumentException("Invalid memory release");
        }
        used -= bytes;
    }

    /// Returns the number of payload bytes currently reserved.
    long used() {
        return used;
    }
}
