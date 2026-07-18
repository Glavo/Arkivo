// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Encodes and decodes headerless PPMd7 payloads.
///
/// PPMd7 has no self-describing standalone wrapper in this package. [PPMdCodec] is an immutable, thread-safe value that
/// holds the maximum context order, model-arena size, and optional externally declared decoded size used by an embedding
/// container. Decoding requires a nonnegative declared decoded size; without it, the raw range-coded payload has no
/// terminal boundary that this API can infer.
///
/// Each created encoder or decoder owns a mutable probability model and is not safe for concurrent use. Buffer-driven
/// operations advance caller [java.nio.ByteBuffer] positions by actual progress and do not retain caller buffers after
/// returning. Only terminal `finish` is exposed; there are no public frame or nonterminal flush operations.
///
/// Decoder construction checks the configured model memory and declared decoded size against operation-scoped memory
/// and output limits. Malformed range-coded data, premature input exhaustion, decoded-size mismatches, and limit
/// violations fail the operation. `reset` abandons the current model before reuse, while `close` releases it without
/// completing pending input.
@NotNullByDefault
package org.glavo.arkivo.codec.ppmd;

import org.jetbrains.annotations.NotNullByDefault;
