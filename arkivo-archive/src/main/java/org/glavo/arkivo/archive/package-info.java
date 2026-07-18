// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides common archive discovery, random-access file-system, forward-only streaming, multi-volume, editing, and
/// safety-limit APIs.
///
/// Format descriptors are immutable and safe to share. Readers, writers, file systems, entry handles, channels, and
/// transactional outputs carry mutable lifecycle state; callers must follow their documented ownership and close
/// contracts. Factory methods that take ownership state the transfer point explicitly. Unless a type documents stronger
/// coordination, mutable operation objects are not safe for concurrent use.
@NotNullByDefault
package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
