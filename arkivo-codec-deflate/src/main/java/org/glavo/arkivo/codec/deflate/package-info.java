// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Encodes and decodes raw Deflate, raw Deflate64, zlib, and gzip data.
///
/// Raw Deflate and Deflate64 contain only algorithm payloads: they have terminal blocks but no identifying header,
/// wrapper checksum, or dictionary identifier. A raw Deflate preset dictionary is therefore supplied out of band. Zlib
/// adds a header, Adler-32 trailer, and optional Adler-32 dictionary identifier. Gzip adds member headers and
/// CRC-protected trailers and permits concatenated members.
///
/// Codec instances are immutable and safe for concurrent use. Their configuration methods return new values and may
/// return the receiver when no value changes. Each created encoder or decoder owns independent mutable state, is not
/// safe for concurrent use, and retains no caller [java.nio.ByteBuffer] after an operation returns. Buffer positions
/// report the exact input consumed and output produced.
///
/// Raw Deflate, Deflate64, and zlib encoders support nonterminal `flush`, which reaches a decodable boundary without
/// ending the stream; `finish` writes the terminal block and any wrapper trailer. Gzip additionally exposes frames:
/// `finishFrame` completes one member while preserving the configuration for another member, whereas `finish` ends
/// the complete encoding session. A flush or frame finalization that needs more target space must be repeated until its
/// successful outcome is returned.
///
/// Decoders enforce the requested output limit and the applicable 32 KiB or 64 KiB history-window limit. Zlib decoders
/// may return a dictionary request before payload decoding can continue. Malformed headers, payloads, trailers,
/// checksum mismatches, missing dictionaries, and limit violations are reported as I/O exceptions; use `reset` to
/// abandon a failed session before reuse.
@NotNullByDefault
package org.glavo.arkivo.codec.deflate;

import org.jetbrains.annotations.NotNullByDefault;
