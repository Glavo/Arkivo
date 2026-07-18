// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzip;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.DecompressionLimits;
import org.glavo.arkivo.codec.lzip.internal.LzipDecoder;
import org.glavo.arkivo.codec.lzip.internal.LzipEncoder;
import org.glavo.arkivo.codec.lzip.internal.LzipSupport;
import org.glavo.arkivo.codec.spi.CompressionDecoderSupport;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.Objects;

/// Provides an immutable lzip configuration and transport-independent member engines.
@NotNullByDefault
public final class LzipCodec implements CompressionCodec.Framed<LzipCodec> {
    /// The lzip default dictionary size corresponding to the reference encoder's level six.
    public static final int DEFAULT_DICTIONARY_SIZE = 8 * 1024 * 1024;

    /// The smallest dictionary representable by a lzip member header.
    public static final int MINIMUM_DICTIONARY_SIZE = 4 * 1024;

    /// The largest dictionary representable by a lzip member header.
    public static final int MAXIMUM_DICTIONARY_SIZE = 512 * 1024 * 1024;

    /// The default immutable lzip codec configuration.
    public static final LzipCodec DEFAULT = new LzipCodec(DEFAULT_DICTIONARY_SIZE);

    /// The dictionary size written to each encoded lzip member.
    private final int dictionarySize;

    /// Creates the default lzip codec configuration.
    public LzipCodec() {
        this(DEFAULT_DICTIONARY_SIZE);
    }

    /// Creates an lzip codec with an exactly representable dictionary size.
    public LzipCodec(int dictionarySize) {
        LzipSupport.encodeDictionarySize(dictionarySize);
        this.dictionarySize = dictionarySize;
    }

    /// Returns the canonical lzip format.
    @Override
    public LzipFormat format() {
        return LzipFormat.instance();
    }

    /// Returns the dictionary size written to newly encoded members.
    public int dictionarySize() {
        return dictionarySize;
    }

    /// Returns an immutable lzip codec with the requested exactly representable dictionary size.
    public LzipCodec withDictionarySize(int dictionarySize) {
        return dictionarySize == this.dictionarySize ? this : new LzipCodec(dictionarySize);
    }

    /// Creates a frame-capable lzip member encoder.
    @Override
    public CompressionEncoder.Framed newEncoder() {
        return new LzipEncoder(dictionarySize);
    }

    /// Creates a frame-capable lzip member decoder with operation-scoped safety limits.
    @Override
    public CompressionDecoder.Framed newDecoder(DecompressionLimits limits) throws IOException {
        Objects.requireNonNull(limits, "limits");
        DecompressionLimits payloadLimits = limits.withMaximumOutputSize(
                DecompressionLimits.UNLIMITED_SIZE
        );
        return CompressionDecoderSupport.limitEngineOutput(
                new LzipDecoder(payloadLimits),
                limits.maximumOutputSize()
        );
    }
}
