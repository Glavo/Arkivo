// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Encodes and decodes BZip2 streams.
///
/// [BZip2Format] identifies BZip2 members, while [BZip2Codec] is an immutable, thread-safe configuration whose
/// compression level selects the BZip2 block size. Each encoder or decoder created from a codec owns an independent
/// mutable session and is not safe for concurrent use.
///
/// A BZip2 stream is exposed as a frame. `finishFrame` writes the current stream trailer and leaves the encoder ready
/// for another concatenated stream; terminal `finish` ends the complete encoding session. BZip2 has no nonterminal
/// flush operation. Decoding validates the member structure and checksums, and the configured decoded-output limit is
/// enforced across the operation.
///
/// Buffer-driven engines advance caller [java.nio.ByteBuffer] positions by the bytes consumed and produced and do not
/// retain either buffer after returning. An I/O, format, checksum, or limit exception means that no successful outcome
/// was reached; `reset` abandons that session before reuse, and `close` releases it without completing pending input.
@NotNullByDefault
package org.glavo.arkivo.codec.bzip2;

import org.jetbrains.annotations.NotNullByDefault;
