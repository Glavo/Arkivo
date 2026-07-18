// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Encodes and decodes lzip members.
///
/// [LzipFormat] identifies the lzip member header. [LzipCodec] is an immutable, thread-safe configuration whose exactly
/// representable dictionary size is written into each new member. Decoders obtain the actual LZMA dictionary size from
/// each member and apply operation-scoped output, history-window, and memory limits before accepting it.
///
/// Lzip members are exposed as frames. `finishFrame` completes the current LZMA payload and writes the member CRC,
/// decoded size, and member size while leaving the encoder ready for a concatenated member. Terminal `finish` ends the
/// complete session; there is no nonterminal flush operation.
///
/// Created engines own mutable state and are not safe for concurrent use. They advance caller [java.nio.ByteBuffer]
/// positions by actual consumption and production and do not retain caller buffers after returning. Invalid headers,
/// malformed or truncated payloads, trailer mismatches, and limit violations are reported as I/O exceptions. `reset`
/// abandons the current member before engine reuse.
@NotNullByDefault
package org.glavo.arkivo.codec.lzip;

import org.jetbrains.annotations.NotNullByDefault;
