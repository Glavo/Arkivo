// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Encodes and decodes XZ streams with LZMA2 and optional preprocessing filters.
///
/// [XZCodec] is an immutable, thread-safe configuration for LZMA2 properties, block integrity checks, preprocessing
/// filters, block splitting, and decoder checksum verification. [XZFilterChain] lists zero through three
/// size-preserving filters in encoding order; the codec appends the required terminal LZMA2 filter.
///
/// Each created encoder or decoder owns an independent mutable stream session and is not safe for concurrent use.
/// Buffer-driven operations advance caller [java.nio.ByteBuffer] positions by actual progress and do not retain caller
/// buffers after returning.
///
/// A nonterminal `flush` finishes the active XZ Block and makes all accepted bytes decodable without ending the XZ
/// Stream. `finishFrame` writes the Stream Index and Footer and leaves the encoder configured for a concatenated Stream;
/// terminal `finish` ends the complete session. The configured block size controls automatic Block boundaries but does
/// not create additional Stream frames.
///
/// Decoders derive filter and dictionary requirements from Block Headers, enforce operation-scoped output, history, and
/// memory limits, and optionally verify each Block check. Invalid filter chains, malformed or truncated structures,
/// check mismatches, unsupported fields, and limit violations are reported as I/O exceptions. `reset` abandons a failed
/// Stream before reuse.
@NotNullByDefault
package org.glavo.arkivo.codec.xz;

import org.jetbrains.annotations.NotNullByDefault;
