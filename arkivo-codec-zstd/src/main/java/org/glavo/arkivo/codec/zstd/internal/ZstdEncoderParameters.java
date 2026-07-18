// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.glavo.arkivo.codec.zstd.ZstdDictionary;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/// Holds validated parameters used by the pure Java Zstandard encoder.
@NotNullByDefault
public final class ZstdEncoderParameters {
    /// Maximum Zstandard block size.
    private static final int MAX_BLOCK_SIZE = 128 * 1024;

    /// Minimum effective size of a parallel compression job.
    private static final int MIN_PARALLEL_JOB_SIZE = 512 * 1024;

    /// Maximum effective size of a parallel compression job.
    private static final int MAX_PARALLEL_JOB_SIZE = 1 << 30;

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

    /// Effective match-finding and sequence-parsing strategy number.
    private final int strategy;

    /// Whether frame checksums are emitted.
    private final boolean checksum;

    /// Whether a known pledged source size is emitted.
    private final boolean contentSize;

    /// Whether a known dictionary identifier is emitted.
    private final boolean dictionaryId;

    /// Whether frame-wide long-distance matching is enabled.
    private final boolean longDistanceMatching;

    /// Effective long-distance hash-table logarithm.
    private final int longDistanceHashLog;

    /// Effective minimum long-distance match length.
    private final int longDistanceMinimumMatch;

    /// Effective long-distance collision-bucket logarithm.
    private final int longDistanceBucketSizeLog;

    /// Effective long-distance sampling-rate logarithm.
    private final int longDistanceHashRateLog;

    /// Number of parallel job-compression workers, or zero for synchronous compression.
    private final int workerCount;

    /// Effective uncompressed bytes assigned to each parallel job.
    private final int jobSize;

    /// Frame-tail bytes reloaded as the match prefix of a later parallel job.
    private final int overlapSize;

    /// Exact source size pledged for every frame, or the unknown sentinel.
    private final long pledgedSourceSize;

    /// Parsed dictionary context.
    private final ZstdDictionaryContext dictionary;

    /// Creates validated pure Java encoder parameters.
    ///
    /// @param compressionLevel requested compression level
    /// @param windowLog requested window logarithm, or zero for an encoder-selected value
    /// @param hashLog requested hash-table logarithm, or zero for an encoder-selected value
    /// @param chainLog requested chain-table logarithm, or zero for an encoder-selected value
    /// @param searchLog requested search-depth logarithm, or zero for an encoder-selected value
    /// @param minimumMatch requested minimum match length, or zero for an encoder-selected value
    /// @param targetLength requested target match length, or zero for no explicit target
    /// @param strategy match-finding strategy number from one through nine, or zero for a level-derived default
    /// @param checksum whether frame checksums are emitted
    /// @param contentSize whether a known pledged source size is emitted
    /// @param dictionaryId whether a known dictionary identifier is emitted
    /// @param longDistanceMatching whether long-distance matching was requested
    /// @param longDistanceHashLog requested LDM hash-table logarithm, or zero for `windowLog - 7`
    /// @param longDistanceMinimumMatch requested LDM minimum match length, or zero for 64
    /// @param longDistanceBucketSizeLog requested LDM bucket-size logarithm, or zero for three
    /// @param longDistanceHashRateLog requested LDM sampling-rate logarithm, or zero for an automatic value
    /// @param workerCount number of parallel job-compression workers, or zero for synchronous compression
    /// @param jobSize requested parallel job size in bytes, or zero for an encoder-selected value
    /// @param overlapLog requested worker overlap logarithm from zero through nine
    /// @param pledgedSourceSize exact source size for every frame, or the unknown sentinel
    /// @param dictionary configured dictionary, or null
    /// @throws IOException if the configured dictionary representation cannot initialize an encoding context
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
            int longDistanceHashLog,
            int longDistanceMinimumMatch,
            int longDistanceBucketSizeLog,
            int longDistanceHashRateLog,
            int workerCount,
            int jobSize,
            int overlapLog,
            long pledgedSourceSize,
            @Nullable ZstdDictionary dictionary
    ) throws IOException {
        if (workerCount < 0 || workerCount > MAX_WORKER_COUNT) {
            throw new IllegalArgumentException(
                    "Zstandard worker count must be between zero and " + MAX_WORKER_COUNT
            );
        }
        if (strategy < 0 || strategy > 9) {
            throw new IllegalArgumentException("Zstandard strategy must be between zero and nine");
        }
        if (jobSize < 0) {
            throw new IllegalArgumentException("Zstandard job size must not be negative");
        }
        if (overlapLog < 0 || overlapLog > 9) {
            throw new IllegalArgumentException("Zstandard overlap log must be between zero and nine");
        }
        this.windowLog = windowLog != 0 ? windowLog : longDistanceMatching ? 27 : 17;
        this.hashLog = hashLog != 0 ? hashLog : defaultHashLog(compressionLevel);
        int effectiveChainLog = chainLog != 0 ? chainLog : defaultChainLog(compressionLevel);
        this.chainLimit = 1 << effectiveChainLog;
        this.strategy = strategy != 0 ? strategy : defaultStrategy(compressionLevel);
        this.searchDepth = searchLog != 0
                ? 1 << searchLog
                : defaultSearchDepth(compressionLevel, this.strategy);
        this.minimumMatch = minimumMatch != 0 ? minimumMatch : compressionLevel < 0 ? 6 : 4;
        this.targetLength = targetLength;
        this.checksum = checksum;
        this.contentSize = contentSize;
        this.dictionaryId = dictionaryId;
        this.longDistanceMatching = longDistanceMatching;
        this.longDistanceHashLog = longDistanceHashLog != 0
                ? longDistanceHashLog
                : Math.max(6, Math.min(20, this.windowLog - 7));
        this.longDistanceMinimumMatch =
                longDistanceMinimumMatch != 0 ? longDistanceMinimumMatch : 64;
        int selectedBucketSizeLog =
                longDistanceBucketSizeLog != 0 ? longDistanceBucketSizeLog : 3;
        if (selectedBucketSizeLog > this.longDistanceHashLog) {
            throw new IllegalArgumentException(
                    "Zstandard LDM bucket-size log cannot exceed the LDM hash log"
            );
        }
        this.longDistanceBucketSizeLog = selectedBucketSizeLog;
        this.longDistanceHashRateLog = longDistanceHashRateLog != 0
                ? longDistanceHashRateLog
                : Math.max(0, this.windowLog - this.longDistanceHashLog);
        this.workerCount = workerCount;
        int targetJobLog = longDistanceMatching
                ? Math.max(21, Math.min(effectiveChainLog, 17) + 3)
                : Math.max(20, this.windowLog + 2);
        targetJobLog = Math.min(targetJobLog, 30);
        int selectedJobSize = jobSize == 0
                ? 1 << targetJobLog
                : Math.min(MAX_PARALLEL_JOB_SIZE, Math.max(MIN_PARALLEL_JOB_SIZE, jobSize));

        int selectedOverlapLog = overlapLog != 0 ? overlapLog : defaultOverlapLog(this.strategy);
        int overlapReduction = 9 - selectedOverlapLog;
        int overlapWindowLog = overlapReduction >= 8
                ? 0
                : longDistanceMatching
                ? Math.min(this.windowLog, targetJobLog - 2) - overlapReduction
                : this.windowLog - overlapReduction;
        long requestedOverlapSize = overlapWindowLog <= 0 ? 0L : 1L << overlapWindowLog;
        int windowSize = this.windowLog >= 30 ? Integer.MAX_VALUE : 1 << this.windowLog;
        int matchDistanceLimit = Math.min(windowSize, this.chainLimit);
        int selectedOverlapSize = (int) Math.min(requestedOverlapSize, matchDistanceLimit);

        this.jobSize = workerCount == 0
                ? 0
                : Math.max(selectedJobSize, selectedOverlapSize);
        this.overlapSize = workerCount == 0 ? 0 : selectedOverlapSize;
        this.pledgedSourceSize = pledgedSourceSize;
        this.dictionary = ZstdDictionaryContext.parse(dictionary);
        if (this.dictionary.id() > 0xffff_ffffL) {
            throw new IllegalArgumentException("Zstandard dictionary identifier exceeds 32 bits");
        }
    }

    /// Creates the ordinary single-threaded parameters used to analyze dictionary samples.
    static ZstdEncoderParameters forDictionaryTraining(int compressionLevel) {
        try {
            return new ZstdEncoderParameters(
                    compressionLevel,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    false,
                    false,
                    false,
                    false,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    -1L,
                    null
            );
        } catch (IOException exception) {
            throw new AssertionError(
                    "Dictionary-training parameters cannot contain an invalid dictionary",
                    exception
            );
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

    /// Returns the effective match-finding and sequence-parsing strategy number.
    int strategy() {
        return strategy;
    }

    /// Returns whether frame checksums are emitted.
    boolean checksum() {
        return checksum;
    }

    /// Returns whether a known pledged source size is emitted.
    boolean contentSize() {
        return contentSize;
    }

    /// Returns whether frame-wide long-distance matching is enabled.
    boolean longDistanceMatching() {
        return longDistanceMatching;
    }

    /// Returns the effective long-distance hash-table logarithm.
    int longDistanceHashLog() {
        return longDistanceHashLog;
    }

    /// Returns the effective minimum long-distance match length.
    int longDistanceMinimumMatch() {
        return longDistanceMinimumMatch;
    }

    /// Returns the effective long-distance collision-bucket logarithm.
    int longDistanceBucketSizeLog() {
        return longDistanceBucketSizeLog;
    }

    /// Returns the effective long-distance sampling-rate logarithm.
    int longDistanceHashRateLog() {
        return longDistanceHashRateLog;
    }

    /// Returns the number of parallel job-compression workers.
    int workerCount() {
        return workerCount;
    }

    /// Returns the effective uncompressed size of a parallel compression job.
    int jobSize() {
        return jobSize;
    }

    /// Returns the retained prefix size for later parallel compression jobs.
    int overlapSize() {
        return overlapSize;
    }

    /// Returns the exact source size pledged for every frame, or the unknown sentinel.
    long pledgedSourceSize() {
        return pledgedSourceSize;
    }

    /// Returns the parsed dictionary context.
    ZstdDictionaryContext dictionary() {
        return dictionary;
    }

    /// Returns the frame dictionary identifier, or the unknown sentinel when it should be omitted.
    long frameDictionaryId() {
        return dictionaryId && dictionary.id() > 0L
                ? dictionary.id()
                : ZstdDictionary.NO_DICTIONARY_ID;
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

    /// Selects a match-finding strategy from the requested compression level.
    private static int defaultStrategy(int compressionLevel) {
        if (compressionLevel < 0) {
            return 1;
        }
        if (compressionLevel <= 1) {
            return 2;
        }
        if (compressionLevel <= 3) {
            return 3;
        }
        if (compressionLevel <= 5) {
            return 4;
        }
        return Math.min(9, 5 + (compressionLevel - 6) / 4);
    }

    /// Selects the standard worker overlap logarithm for a match-finding strategy.
    private static int defaultOverlapLog(int strategy) {
        return switch (strategy) {
            case 9 -> 9;
            case 7, 8 -> 8;
            case 5, 6 -> 7;
            default -> 6;
        };
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
