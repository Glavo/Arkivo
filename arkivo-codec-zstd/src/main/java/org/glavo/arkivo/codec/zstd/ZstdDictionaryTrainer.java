// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import org.glavo.arkivo.codec.CompressionDictionary;
import org.glavo.arkivo.codec.zstd.internal.ZstdDictionaryBuilder;
import org.glavo.arkivo.codec.zstd.internal.ZstdDictionarySupport;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.EOFException;
import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.Arrays;
import java.util.Objects;

/// Collects samples in direct memory and trains a reusable Zstandard dictionary.
///
/// Instances are safe for concurrent callers. Sample mutation and training are serialized; accepted samples remain
/// available so multiple training modes can be evaluated.
@NotNullByDefault
public final class ZstdDictionaryTrainer {
    /// Minimum accepted compression level.
    private static final int MINIMUM_COMPRESSION_LEVEL = -131_072;

    /// Maximum accepted compression level.
    private static final int MAXIMUM_COMPRESSION_LEVEL = 22;

    /// Default compression level.
    private static final int DEFAULT_COMPRESSION_LEVEL = 3;

    /// The initial number of sample-size slots.
    private static final int INITIAL_SAMPLE_COUNT = 16;

    /// Packed training sample content.
    private final ByteBuffer samples;

    /// Maximum trained dictionary size.
    private final int dictionaryCapacity;

    /// Compression level used to optimize the dictionary.
    private final int compressionLevel;

    /// Accepted sample sizes.
    private int[] sampleSizes = new int[INITIAL_SAMPLE_COUNT];

    /// Number of accepted samples.
    private int sampleCount;

    /// Creates a trainer using the default Zstandard compression level.
    public ZstdDictionaryTrainer(long sampleCapacity, long dictionaryCapacity) {
        this(sampleCapacity, dictionaryCapacity, DEFAULT_COMPRESSION_LEVEL);
    }

    /// Creates a trainer with an explicit Zstandard compression level.
    public ZstdDictionaryTrainer(long sampleCapacity, long dictionaryCapacity, long compressionLevel) {
        int validatedSampleCapacity = positiveBufferSize(sampleCapacity, "sampleCapacity");
        int validatedDictionaryCapacity = positiveBufferSize(dictionaryCapacity, "dictionaryCapacity");
        if (compressionLevel < MINIMUM_COMPRESSION_LEVEL || compressionLevel > MAXIMUM_COMPRESSION_LEVEL) {
            throw new IllegalArgumentException("compressionLevel is out of range");
        }
        this.samples = ByteBuffer.allocateDirect(validatedSampleCapacity);
        this.dictionaryCapacity = validatedDictionaryCapacity;
        this.compressionLevel = Math.toIntExact(compressionLevel);
    }

    /// Returns the maximum combined size of accepted samples.
    public long sampleCapacity() {
        return samples.capacity();
    }

    /// Returns the maximum trained dictionary size.
    public long dictionaryCapacity() {
        return dictionaryCapacity;
    }

    /// Returns the configured dictionary-optimization compression level.
    public long compressionLevel() {
        return compressionLevel;
    }

    /// Returns the number of accepted samples.
    public synchronized int sampleCount() {
        return sampleCount;
    }

    /// Returns the combined size of accepted samples.
    public synchronized long sampleBytes() {
        return samples.position();
    }

    /// Returns the remaining sample capacity.
    public synchronized long remainingSampleCapacity() {
        return samples.remaining();
    }

    /// Adds all remaining bytes as one sample.
    ///
    /// The source is advanced to its limit. A sample larger than the remaining trainer capacity causes a
    /// `BufferOverflowException` without changing the source position or trainer state.
    public synchronized void addSample(ByteBuffer sample) {
        if (!tryAddSample(sample)) {
            throw new BufferOverflowException();
        }
    }

    /// Attempts to add all remaining bytes as one sample.
    ///
    /// The source is advanced only when the sample is accepted. Empty samples are rejected.
    public synchronized boolean tryAddSample(ByteBuffer sample) {
        Objects.requireNonNull(sample, "sample");
        int size = sample.remaining();
        if (size == 0) {
            throw new IllegalArgumentException("sample must not be empty");
        }
        if (size > samples.remaining()) {
            return false;
        }

        ensureSampleSizeCapacity();
        samples.put(sample);
        sampleSizes[sampleCount++] = size;
        return true;
    }

    /// Reads one sample of the declared size from a channel.
    ///
    /// The channel remains open and bytes after the sample boundary are not consumed. Insufficient trainer capacity is
    /// rejected before reading. End of input or a read failure leaves the trainer state unchanged.
    public synchronized void addSample(ReadableByteChannel source, long sampleSize) throws IOException {
        Objects.requireNonNull(source, "source");
        int size = positiveBufferSize(sampleSize, "sampleSize");
        if (size > samples.remaining()) {
            throw new BufferOverflowException();
        }

        ByteBuffer target = samples.duplicate();
        target.limit(Math.addExact(target.position(), size));
        while (target.hasRemaining()) {
            int read = source.read(target);
            if (read < 0) {
                throw new EOFException("Sample channel ended before " + sampleSize + " bytes were read");
            }
            if (read == 0) {
                throw new IOException("Sample channel made no progress");
            }
        }

        ensureSampleSizeCapacity();
        samples.position(target.position());
        sampleSizes[sampleCount++] = size;
    }

    /// Trains a dictionary using frequency-ranked reusable sample segments.
    public synchronized CompressionDictionary train() {
        return train(false);
    }

    /// Trains a dictionary using either frequency-ranked or legacy-oriented content selection.
    ///
    /// Legacy mode selects the packed sample suffix. Both modes produce standard formatted Zstandard dictionaries;
    /// both derive initial Huffman and sequence FSE tables from the accepted samples. They are not intended to
    /// reproduce byte-identical output from the native training implementations.
    public synchronized CompressionDictionary train(boolean legacy) {
        if (sampleCount == 0) {
            throw new IllegalStateException("At least one sample is required");
        }

        if (dictionaryCapacity < ZstdDictionaryBuilder.MINIMUM_DICTIONARY_CAPACITY) {
            throw new ZstdDictionaryTrainingException(
                    ZstdDictionaryTrainingException.DESTINATION_TOO_SMALL,
                    "dictionary capacity must be at least "
                            + ZstdDictionaryBuilder.MINIMUM_DICTIONARY_CAPACITY + " bytes"
            );
        }
        int sampleBytes = samples.position();
        if (sampleBytes < ZstdDictionaryBuilder.MINIMUM_SAMPLE_BYTES) {
            throw new ZstdDictionaryTrainingException(
                    ZstdDictionaryTrainingException.INSUFFICIENT_SAMPLES,
                    "at least " + ZstdDictionaryBuilder.MINIMUM_SAMPLE_BYTES
                            + " sample bytes are required"
            );
        }

        ByteBuffer source = samples.duplicate();
        source.flip();
        byte[] packedSamples = new byte[source.remaining()];
        source.get(packedSamples);
        byte[] dictionary;
        try {
            dictionary = ZstdDictionaryBuilder.build(
                    packedSamples,
                    Arrays.copyOf(sampleSizes, sampleCount),
                    dictionaryCapacity,
                    compressionLevel,
                    legacy
            );
        } catch (IllegalArgumentException exception) {
            throw new ZstdDictionaryTrainingException(
                    ZstdDictionaryTrainingException.DICTIONARY_CREATION_FAILED,
                    exception.getMessage(),
                    exception
            );
        }
        long id = ZstdDictionarySupport.dictionaryId(ByteBuffer.wrap(dictionary));
        return CompressionDictionary.of(dictionary, id);
    }

    /// Expands the sample-size array before accepting another sample.
    private void ensureSampleSizeCapacity() {
        if (sampleCount == sampleSizes.length) {
            int newLength = Math.addExact(sampleSizes.length, sampleSizes.length >>> 1);
            sampleSizes = Arrays.copyOf(sampleSizes, newLength);
        }
    }

    /// Validates a direct-buffer capacity represented by the public long-valued API.
    private static int positiveBufferSize(long value, String name) {
        if (value <= 0L || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " must be between 1 and " + Integer.MAX_VALUE);
        }
        return Math.toIntExact(value);
    }
}
