// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdException;
import org.glavo.arkivo.codec.CompressionDictionary;
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
        this(sampleCapacity, dictionaryCapacity, Zstd.defaultCompressionLevel());
    }

    /// Creates a trainer with an explicit Zstandard compression level.
    public ZstdDictionaryTrainer(long sampleCapacity, long dictionaryCapacity, long compressionLevel) {
        int validatedSampleCapacity = positiveBufferSize(sampleCapacity, "sampleCapacity");
        int validatedDictionaryCapacity = positiveBufferSize(dictionaryCapacity, "dictionaryCapacity");
        if (compressionLevel < Zstd.minCompressionLevel() || compressionLevel > Zstd.maxCompressionLevel()) {
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

    /// Trains a dictionary using the current Zstandard training algorithm.
    public synchronized CompressionDictionary train() {
        return train(false);
    }

    /// Trains a dictionary using either the current or legacy Zstandard training algorithm.
    ///
    /// Legacy mode is provided for compatibility with dictionaries produced by older Zstandard tooling.
    public synchronized CompressionDictionary train(boolean legacy) {
        if (sampleCount == 0) {
            throw new IllegalStateException("At least one sample is required");
        }

        ByteBuffer dictionary = ByteBuffer.allocateDirect(dictionaryCapacity);
        long size;
        try {
            size = Zstd.trainFromBufferDirect(
                    samples.duplicate(),
                    Arrays.copyOf(sampleSizes, sampleCount),
                    dictionary,
                    legacy,
                    compressionLevel
            );
        } catch (ZstdException exception) {
            throw new ZstdDictionaryTrainingException(exception.getErrorCode(), exception);
        }
        if (Zstd.isError(size)) {
            throw new ZstdDictionaryTrainingException(size);
        }

        dictionary.limit(Math.toIntExact(size));
        long id = ZstdDictionarySupport.dictionaryId(dictionary);
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
