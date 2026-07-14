// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.glavo.arkivo.codec.CompressionDictionary;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/// Holds validated parameters used by the pure Java Zstandard encoder.
@NotNullByDefault
public final class ZstdEncoderParameters {
    /// Maximum Zstandard block size.
    private static final int MAX_BLOCK_SIZE = 128 * 1024;

    /// Maximum supported parallel block-compression worker count.
    private static final int MAX_WORKER_COUNT = 256;

    /// Effective window logarithm.
    private final int windowLog;

    /// Effective hash-table logarithm.
    private final int hashLog;

    /// Maximum distance followed through a hash chain.
    private final int chainLimit;

    /// Maximum candidates examined at each input position.
    private final int searchDepth;

    /// Minimum accepted match length.
    private final int minimumMatch;

    /// Match length at which candidate searching may stop early.
    private final int targetLength;

    /// Whether frame checksums are emitted.
    private final boolean checksum;

    /// Whether a known pledged source size is emitted.
    private final boolean contentSize;

    /// Whether a known dictionary identifier is emitted.
    private final boolean dictionaryId;

    /// Number of parallel block-compression workers, or zero for synchronous compression.
    private final int workerCount;

    /// Exact source size pledged for every frame, or the unknown sentinel.
    private final long pledgedSourceSize;

    /// Parsed dictionary context.
    private final ZstdDictionary dictionary;

    /// Creates validated pure Java encoder parameters.
    ///
    /// @param compressionLevel requested compression level
    /// @param windowLog requested window logarithm, or zero for an encoder-selected value
    /// @param hashLog requested hash-table logarithm, or zero for an encoder-selected value
    /// @param chainLog requested chain-table logarithm, or zero for an encoder-selected value
    /// @param searchLog requested search-depth logarithm, or zero for an encoder-selected value
    /// @param minimumMatch requested minimum match length, or zero for an encoder-selected value
    /// @param targetLength requested target match length, or zero for no explicit target
    /// @param strategy match-finding strategy number from one through nine
    /// @param checksum whether frame checksums are emitted
    /// @param contentSize whether a known pledged source size is emitted
    /// @param dictionaryId whether a known dictionary identifier is emitted
    /// @param longDistanceMatching whether long-distance matching was requested
    /// @param workerCount number of parallel block-compression workers, or zero for synchronous compression
    /// @param pledgedSourceSize exact source size for every frame, or the unknown sentinel
    /// @param dictionary configured dictionary, or null
    public ZstdEncoderParameters(
            int compressionLevel,
            int windowLog,
            int hashLog,
            int chainLog,
            int searchLog,
            int minimumMatch,
            int targetLength,
            int strategy,
            boolean checksum,
            boolean contentSize,
            boolean dictionaryId,
            boolean longDistanceMatching,
            int workerCount,
            long pledgedSourceSize,
            @Nullable CompressionDictionary dictionary
    ) throws IOException {
        if (workerCount < 0 || workerCount > MAX_WORKER_COUNT) {
            throw new IllegalArgumentException(
                    "Zstandard worker count must be between zero and " + MAX_WORKER_COUNT
            );
        }
        this.windowLog = windowLog != 0 ? windowLog : longDistanceMatching ? 27 : 17;
        this.hashLog = hashLog != 0 ? Math.min(hashLog, 18) : defaultHashLog(compressionLevel);
        int effectiveChainLog = chainLog != 0 ? chainLog : defaultChainLog(compressionLevel);
        this.chainLimit = 1 << Math.min(effectiveChainLog, 17);
        this.searchDepth = searchLog != 0
                ? 1 << Math.min(searchLog, 10)
                : defaultSearchDepth(compressionLevel, strategy);
        this.minimumMatch = minimumMatch != 0 ? minimumMatch : compressionLevel < 0 ? 6 : 4;
        this.targetLength = targetLength;
        this.checksum = checksum;
        this.contentSize = contentSize;
        this.dictionaryId = dictionaryId;
        this.workerCount = workerCount;
        this.pledgedSourceSize = pledgedSourceSize;
        this.dictionary = ZstdDictionary.parse(dictionary);
        if (this.dictionary.id() > 0xffff_ffffL) {
            throw new IllegalArgumentException("Zstandard dictionary identifier exceeds 32 bits");
        }
    }

    /// Returns the effective window logarithm.
    int windowLog() {
        return windowLog;
    }

    /// Returns the maximum block size permitted by the configured window.
    int blockSize() {
        return windowLog >= 17 ? MAX_BLOCK_SIZE : 1 << windowLog;
    }

    /// Returns the effective hash-table logarithm.
    int hashLog() {
        return hashLog;
    }

    /// Returns the maximum hash-chain distance.
    int chainLimit() {
        return chainLimit;
    }

    /// Returns the maximum candidates examined at each position.
    int searchDepth() {
        return searchDepth;
    }

    /// Returns the minimum accepted match length.
    int minimumMatch() {
        return minimumMatch;
    }

    /// Returns the target match length, or zero when no target is configured.
    int targetLength() {
        return targetLength;
    }

    /// Returns whether frame checksums are emitted.
    boolean checksum() {
        return checksum;
    }

    /// Returns whether a known pledged source size is emitted.
    boolean contentSize() {
        return contentSize;
    }

    /// Returns the number of parallel block-compression workers.
    int workerCount() {
        return workerCount;
    }

    /// Returns the exact source size pledged for every frame, or the unknown sentinel.
    long pledgedSourceSize() {
        return pledgedSourceSize;
    }

    /// Returns the parsed dictionary context.
    ZstdDictionary dictionary() {
        return dictionary;
    }

    /// Returns the frame dictionary identifier, or the unknown sentinel when it should be omitted.
    long frameDictionaryId() {
        return dictionaryId && dictionary.id() > 0L
                ? dictionary.id()
                : CompressionDictionary.UNKNOWN_ID;
    }

    /// Selects a bounded hash-table logarithm from the compression level.
    private static int defaultHashLog(int compressionLevel) {
        if (compressionLevel < 0) {
            return 14;
        }
        return Math.min(18, 15 + compressionLevel / 5);
    }

    /// Selects a bounded hash-chain logarithm from the compression level.
    private static int defaultChainLog(int compressionLevel) {
        if (compressionLevel < 0) {
            return 12;
        }
        return Math.min(17, 14 + compressionLevel / 4);
    }

    /// Selects a bounded search depth from the level and strategy.
    private static int defaultSearchDepth(int compressionLevel, int strategy) {
        int levelDepth;
        if (compressionLevel < 0) {
            levelDepth = 1;
        } else if (compressionLevel <= 3) {
            levelDepth = 4;
        } else if (compressionLevel <= 9) {
            levelDepth = 16;
        } else {
            levelDepth = 64;
        }
        return Math.min(1024, Math.max(levelDepth, 1 << Math.min(strategy - 1, 8)));
    }
}
