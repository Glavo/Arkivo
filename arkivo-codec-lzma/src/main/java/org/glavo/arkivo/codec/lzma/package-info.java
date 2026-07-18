// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Encodes and decodes LZMA-alone streams, headerless LZMA streams, and headerless LZMA2 streams.
///
/// [LZMACodec] manages the LZMA-alone header that carries model properties, dictionary size, and an optional exact
/// decoded size. [RawLZMACodec] carries no standalone metadata, so an embedding format must retain all matching
/// [LZMAProperties] and any decoded-size boundary. LZMA2 control streams carry literal and position properties but not
/// their dictionary size, which remains external. Raw LZMA can terminate by an end marker or an externally declared
/// decoded size; raw LZMA2 terminates with its control-stream end marker.
///
/// Codec and property values are immutable and safe for concurrent use. Each created encoder or decoder owns one
/// mutable session and is not safe for concurrent use. Buffer-driven operations advance caller [java.nio.ByteBuffer]
/// positions by actual progress and do not retain caller buffers after returning. These codecs expose terminal
/// `finish` only; no public frame or nonterminal flush capability is advertised.
///
/// A pledged source size is exact when present: accepting more bytes or finishing after fewer bytes is an I/O error.
/// Decoders reject dictionary sizes above the operation-scoped history or memory bound and enforce decoded-output
/// limits. Malformed properties, impossible match distances, truncation, size mismatches, and limit violations are
/// reported as I/O exceptions. `reset` abandons a session and restores the engine's original immutable configuration.
@NotNullByDefault
package org.glavo.arkivo.codec.lzma;

import org.jetbrains.annotations.NotNullByDefault;
