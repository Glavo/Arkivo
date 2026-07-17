// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.all;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.ppmd.PPMdCodec;
import org.jetbrains.annotations.NotNullByDefault;

/// Supplies externally carried format metadata for cross-provider contract tests.
@NotNullByDefault
final class CodecContractConfigurations {
    /// Creates no instances.
    private CodecContractConfigurations() {
    }

    /// Returns a decoder configuration matching a default encoding of the known uncompressed size.
    static CompressionCodec<?> decoderCodec(CompressionCodec<?> codec, long decodedSize) {
        if (codec instanceof PPMdCodec ppmdCodec) {
            return ppmdCodec.withDecodedSize(decodedSize);
        }
        return codec;
    }

    /// Returns whether the codec requires an externally configured decoded size.
    static boolean requiresDecoderConfiguration(CompressionCodec<?> codec) {
        return codec instanceof PPMdCodec;
    }
}
