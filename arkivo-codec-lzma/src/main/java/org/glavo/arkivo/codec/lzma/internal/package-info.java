// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Implements the LZMA and LZMA2 range coders, state machines, and buffer and channel engines.
///
/// This package is exported only to the official XZ implementation module. Applications use the immutable codec
/// configurations in [org.glavo.arkivo.codec.lzma].
@NotNullByDefault
package org.glavo.arkivo.codec.lzma.internal;

import org.jetbrains.annotations.NotNullByDefault;
