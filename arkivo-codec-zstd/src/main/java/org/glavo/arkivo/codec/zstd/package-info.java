// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Encodes, decodes, and inspects Zstandard frames and trains Zstandard dictionaries.
///
/// [ZstdCodec] is an immutable, thread-safe configuration for compression parameters, checksums, dictionaries,
/// parallel workers, long-distance matching, and standard or magicless physical framing. Standard framing is
/// self-identifying and permits skippable frames. Magicless framing omits the magic value and therefore requires the
/// caller to select [ZstdFrameFormat#MAGICLESS] out of band.
/// Standard-frame configurations can write the Zstandard seekable representation: independently decodable frames are
/// followed by a skippable seek table, so ordinary sequential decoders remain compatible while indexed readers decode
/// only frames intersecting the requested logical range. Decoder memory limits account for index parsing, the retained
/// primitive index, the cached decoded frame, and the remaining codec window budget. Magicless framing does not support
/// this representation.
///
/// Builders and codec engines are mutable and not safe for concurrent use. Each engine owns independent history and
/// pending output, advances caller [java.nio.ByteBuffer] positions by actual progress, and retains no caller buffer after
/// returning. [ZstdDictionaryTrainer] is the exception among mutable public helpers: its sample collection and training
/// operations are synchronized for concurrent callers.
///
/// Encoder `flush` completes the current compressed block without ending the frame. `finishFrame` writes the final
/// block and optional checksum, then preserves the codec configuration for another frame; terminal `finish` ends the
/// complete session. A known source size is exact and, when enabled, is written to that frame's header. The initial
/// frame receives factory [org.glavo.arkivo.codec.EncodingOptions], and later frames may receive independent options
/// through [org.glavo.arkivo.codec.CompressionEncoder.Framed#startFrame(org.glavo.arkivo.codec.EncodingOptions)].
///
/// Full dictionaries carry an identifier that a decoder can request from a standard frame; raw-content dictionaries
/// must be configured out of band. Decoder limits bound output and the declared history window, and checksum verification
/// is configurable independently of checksum emission. Malformed or truncated frames, checksum failures, missing or
/// mismatched dictionaries, source-size mismatches, and limit violations are reported as I/O exceptions. `reset`
/// abandons the current frame before engine reuse.
@NotNullByDefault
package org.glavo.arkivo.codec.zstd;

import org.jetbrains.annotations.NotNullByDefault;
