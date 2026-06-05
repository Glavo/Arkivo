// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.glavo.arkivo.ArkivoFileSystemThreadSafety;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.locks.ReentrantLock;

/// Creates and operates optional ZIP implementation locks.
@NotNullByDefault
final class ZipLocks {
    /// Prevents instantiation.
    private ZipLocks() {
    }

    /// Creates a lock for the requested thread-safety strategy, or `null` when no locking is requested.
    static @Nullable ReentrantLock create(ArkivoFileSystemThreadSafety threadSafety) {
        return threadSafety == ArkivoFileSystemThreadSafety.NONE
                ? null
                : new ReentrantLock();
    }

    /// Acquires the lock when it is present.
    static void lock(@Nullable ReentrantLock lock) {
        if (lock != null) {
            lock.lock();
        }
    }

    /// Releases the lock when it is present.
    static void unlock(@Nullable ReentrantLock lock) {
        if (lock != null) {
            lock.unlock();
        }
    }

}
