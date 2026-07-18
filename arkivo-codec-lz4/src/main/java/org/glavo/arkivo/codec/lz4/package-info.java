// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Encodes and decodes standard LZ4 frames and headerless LZ4 blocks.
///
/// [LZ4Codec] writes standard frames and decodes standard, legacy, and skippable frames. Its immutable configuration
/// selects block independence, maximum decoded block size, checksum policies, and an optional prefix dictionary.
/// [LZ4BlockCodec] instead represents one raw block whose compressed and decoded boundaries are not stored in the data;
/// its configured maximum decoded size is therefore part of the embedding contract.
///
/// Codec values are thread-safe. Builders and engines are mutable and not safe for concurrent use. Every created engine
/// owns its history and pending output, advances caller [java.nio.ByteBuffer] positions by actual progress, and retains
/// no caller buffer after returning.
///
/// Standard-frame encoders expose both `flush` and frame finalization. A flush finishes the currently buffered physical
/// block without ending the frame. `finishFrame` writes the frame end mark and optional content checksum, then preserves
/// the configuration for a following frame; terminal `finish` ends the complete session. Raw-block encoders have only
/// terminal finalization.
///
/// Decoder limits bound output, history, and working memory. A standard frame carrying a dictionary identifier can
/// request a matching [LZ4Dictionary]; raw dictionaries without an identifier must be configured out of band. Invalid
/// offsets, malformed or truncated frames, checksum failures, missing dictionaries, and limit violations are reported
/// as I/O exceptions. `reset` abandons the current session before reuse.
@NotNullByDefault
package org.glavo.arkivo.codec.lz4;

import org.jetbrains.annotations.NotNullByDefault;
