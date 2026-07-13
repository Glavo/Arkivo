// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.ppmd;

import org.glavo.arkivo.codec.CodecOption;
import org.jetbrains.annotations.NotNullByDefault;

/// Defines the raw PPMd stream parameters required to initialize a model.
@NotNullByDefault
public final class PPMdCodecOptions {
    /// Selects the PPMd model's maximum context order as a value from 2 through 64.
    public static final CodecOption<Long> MAXIMUM_ORDER = CodecOption.of("ppmd.maximumOrder", Long.class);

    /// Selects the PPMd model arena size in bytes.
    public static final CodecOption<Long> MEMORY_SIZE = CodecOption.of("ppmd.memorySize", Long.class);

    /// Declares the exact number of uncompressed bytes to decode from a raw stream.
    public static final CodecOption<Long> DECODED_SIZE = CodecOption.of("ppmd.decodedSize", Long.class);

    /// Creates no instances.
    private PPMdCodecOptions() {
    }
}
