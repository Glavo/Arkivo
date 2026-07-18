// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides compression-format discovery, immutable codec configuration, buffer-driven engines, blocking channel and
/// stream contexts, dictionary negotiation, and operation-scoped decompression limits.
///
/// Format and codec values are safe to share. Encoders, decoders, and their channel or stream contexts are stateful and
/// must be confined to one operation unless a type explicitly documents stronger thread safety. Buffer-driven methods
/// communicate byte progress through [java.nio.ByteBuffer#position()] and never take ownership of caller buffers.
/// Every codec accepts exact source-size metadata for encoder creation; implementations may use or ignore it according
/// to their format and algorithm.
/// The default [CompressionCodec] channel factories preserve [java.nio.channels.InterruptibleChannel] when their backing
/// channel implements it, including terminal interruption and asynchronous-close behavior.
@NotNullByDefault
package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;
