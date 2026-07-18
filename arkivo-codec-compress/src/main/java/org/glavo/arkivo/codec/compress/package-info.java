// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Encodes and decodes Unix compress (`.Z`) streams.
///
/// [UnixCompressFormat] identifies the self-describing `.Z` header. [UnixCompressCodec] is an immutable, thread-safe
/// configuration for the maximum LZW code width and block-mode flag written by an encoder. A decoder obtains both
/// values from the input header; its operation-scoped limits bound decoded output, the LZW table size, and decoder
/// working memory.
///
/// Each encoder or decoder created from a codec owns one mutable stream session and is not safe for concurrent use.
/// Unix compress exposes neither independent frame boundaries nor a nonterminal flush: `finish` is the only successful
/// encoding terminator. Buffer operations advance caller [java.nio.ByteBuffer] positions by actual progress and retain
/// no caller buffer after returning.
///
/// Malformed or truncated streams and decompression-limit violations are reported as I/O exceptions. `reset` abandons
/// the current session before reuse; `close` releases a session without implicitly finishing it.
@NotNullByDefault
package org.glavo.arkivo.codec.compress;

import org.jetbrains.annotations.NotNullByDefault;
