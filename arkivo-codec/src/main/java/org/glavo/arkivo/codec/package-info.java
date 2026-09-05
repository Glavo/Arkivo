// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Compresses and decompresses bytes using buffers, channels, or streams.
///
/// [CompressionFormats] locates installed formats. Each [CompressionFormat] supplies a default [CompressionCodec],
/// an immutable configuration containing algorithm settings and decompression limits. Format and codec objects can be
/// shared between threads.
///
/// A codec creates stateful [CompressionEncoder] and [CompressionDecoder] instances. Their buffer operations advance
/// input and output positions without retaining the buffers. Encoders, decoders, and their stream or channel adapters
/// are not safe for concurrent use unless their documentation states otherwise. [EncodingOptions] supplies the exact
/// input size for an encoding operation when it is known.
///
/// [CompressionCodec.Seekable] provides indexed compression and read-only random access to decoded bytes.
/// [SeekableEncodingOptions] controls the frame size when writing such an index.
///
/// The default codec channel factories return an [java.nio.channels.InterruptibleChannel] when the supplied channel
/// implements that interface. See [CompressionCodec] for interruption, asynchronous close, and resource ownership.
@NotNullByDefault
package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;
