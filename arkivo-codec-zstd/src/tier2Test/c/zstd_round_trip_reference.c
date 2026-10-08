/* Copyright (c) Meta Platforms, Inc. and affiliates.
 * Copyright (c) 2026 Glavo
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Runs the unmodified Zstandard 1.5.7 simple_round_trip.c assertions.
 * See LICENSES/Zstd-BSD-3-Clause.txt. Native code is used only for testing.
 */

#define STATEFUL_FUZZING
#include "simple_round_trip.c"
#include "zstd_errors.h"

/* Emits a complete frame as a single hexadecimal field. */
static void writeFrame(const unsigned char* bytes, size_t size) {
    size_t i;
    for (i = 0; i < size; i++) printf("%02x", (unsigned)bytes[i]);
    putchar('\n');
}

/* Demonstrates the upstream stateful target's stale decoder-block-limit failure without aborting. */
static void retainedLimitRegression(void) {
    unsigned char source[16384];
    unsigned char restored[sizeof(source)];
    size_t capacity = ZSTD_compressBound(sizeof(source));
    unsigned char* small = (unsigned char*)FUZZ_malloc(capacity);
    unsigned char* large = (unsigned char*)FUZZ_malloc(capacity);
    ZSTD_CCtx* compressor = ZSTD_createCCtx();
    ZSTD_DCtx* decoder = ZSTD_createDCtx();
    size_t smallSize;
    size_t largeSize;
    size_t result;
    size_t i;
    FUZZ_ASSERT(compressor && decoder);
    for (i = 0; i < sizeof(source); i++) source[i] = (unsigned char)(i % 251);
    FUZZ_ZASSERT(ZSTD_CCtx_setParameter(compressor, ZSTD_c_windowLog, 15));
    FUZZ_ZASSERT(ZSTD_CCtx_setParameter(compressor, ZSTD_c_contentSizeFlag, 0));
    FUZZ_ZASSERT(ZSTD_CCtx_setParameter(compressor, ZSTD_c_maxBlockSize, 1024));
    smallSize = ZSTD_compress2(compressor, small, capacity, source, sizeof(source));
    FUZZ_ZASSERT(smallSize);
    FUZZ_ZASSERT(ZSTD_DCtx_setParameter(decoder, ZSTD_d_maxBlockSize, 1024));
    result = ZSTD_decompressDCtx(decoder, restored, sizeof(restored), small, smallSize);
    FUZZ_ZASSERT(result);
    FUZZ_ASSERT(result == sizeof(source) && !memcmp(source, restored, result));
    FUZZ_ZASSERT(ZSTD_CCtx_setParameter(compressor, ZSTD_c_maxBlockSize, ZSTD_BLOCKSIZE_MAX));
    largeSize = ZSTD_compress2(compressor, large, capacity, source, sizeof(source));
    FUZZ_ZASSERT(largeSize);
    result = ZSTD_decompressDCtx(decoder, restored, sizeof(restored), large, largeSize);
    FUZZ_ASSERT(ZSTD_getErrorCode(result) == ZSTD_error_dstSize_tooSmall);
    FUZZ_ZASSERT(ZSTD_DCtx_setParameter(decoder, ZSTD_d_maxBlockSize, 0));
    result = ZSTD_decompressDCtx(decoder, restored, sizeof(restored), large, largeSize);
    FUZZ_ZASSERT(result);
    FUZZ_ASSERT(result == sizeof(source) && !memcmp(source, restored, result));
    puts("retained-limit-rejected-and-cleared");
    writeFrame(small, smallSize);
    writeFrame(large, largeSize);
    ZSTD_freeCCtx(compressor);
    ZSTD_freeDCtx(decoder);
    free(small);
    free(large);
}

/* Emits a requested context parameter after the original target has completed. */
static void parameter(const char* name, ZSTD_cParameter key) {
    int value;
    FUZZ_ZASSERT(ZSTD_CCtx_getParameter(cctx, key, &value));
    printf(" %s=%d", name, value);
}

/* Retains the original allocations and input partition, exporting the last deterministic frame. */
static void run(const unsigned char* source, size_t inputSize) {
    FUZZ_dataProducer_t* producer = FUZZ_dataProducer_create(source, inputSize);
    size_t size = FUZZ_dataProducer_reserveDataPrefix(producer);
    size_t capacity = ZSTD_compressBound(inputSize) - FUZZ_dataProducer_uint32Range(producer, 0, 1);
    size_t remaining = FUZZ_dataProducer_remainingBytes(producer);
    unsigned randomParameters = FUZZ_dataProducer_uint32Range(producer, 0, 1);
    void* result = FUZZ_malloc(inputSize);
    unsigned char* compressed = (unsigned char*)FUZZ_malloc(capacity);
    size_t compressedSize;
    FUZZ_dataProducer_rollBack(producer, remaining);
    if (!cctx) cctx = ZSTD_createCCtx();
    if (!dctx) dctx = ZSTD_createDCtx();
    FUZZ_ASSERT(cctx && dctx);
    /* The original target may leave a smaller prior input's decoder limit installed.
     * Clear only this input-specific restriction; roundTripTest may select it again.
     * --original-reuse retains the upstream failure for independent reproduction.
     */
    FUZZ_ZASSERT(ZSTD_DCtx_setParameter(dctx, ZSTD_d_maxBlockSize, 0));
    /* Includes parameter rollback, repeated-compression hashes and in-place native decompression. */
    roundTripTest(result, inputSize, compressed, capacity, source, size, producer);
    compressedSize = ZSTD_findFrameCompressedSize(compressed, capacity);
    FUZZ_ZASSERT(compressedSize);
    FUZZ_ASSERT(compressedSize <= capacity);
    printf("size=%zu capacity=%zu random=%u", size, capacity, randomParameters);
    parameter("level", ZSTD_c_compressionLevel);
    parameter("window", ZSTD_c_windowLog);
    parameter("hash", ZSTD_c_hashLog);
    parameter("chain", ZSTD_c_chainLog);
    parameter("search", ZSTD_c_searchLog);
    parameter("match", ZSTD_c_minMatch);
    parameter("target", ZSTD_c_targetLength);
    parameter("strategy", ZSTD_c_strategy);
    parameter("contentSize", ZSTD_c_contentSizeFlag);
    parameter("checksum", ZSTD_c_checksumFlag);
    parameter("dictionaryId", ZSTD_c_dictIDFlag);
    parameter("ldm", ZSTD_c_enableLongDistanceMatching);
    parameter("ldmHash", ZSTD_c_ldmHashLog);
    parameter("ldmMatch", ZSTD_c_ldmMinMatch);
    parameter("ldmBucket", ZSTD_c_ldmBucketSizeLog);
    parameter("ldmRate", ZSTD_c_ldmHashRateLog);
    parameter("workers", ZSTD_c_nbWorkers);
    parameter("rsyncable", ZSTD_c_rsyncable);
    parameter("row", ZSTD_c_useRowMatchFinder);
    parameter("literal", ZSTD_c_literalCompressionMode);
    parameter("splitter", ZSTD_c_blockSplitterLevel);
    parameter("maxBlock", ZSTD_c_maxBlockSize);
    parameter("targetBlock", ZSTD_c_targetCBlockSize);
    putchar(' ');
    writeFrame(compressed, compressedSize);
    free(result);
    free(compressed);
    FUZZ_dataProducer_free(producer);
}

/* Reads bounded length/hex requests, with optional context retention between complete seeds. */
int main(int argc, char** argv) {
    int original = argc == 2 && strcmp(argv[1], "--original-reuse") == 0;
    int reuse = original || (argc == 2 && strcmp(argv[1], "--reuse") == 0);
    int fields;
    size_t size;
    size_t request = 0;
    if (argc == 2 && strcmp(argv[1], "--retained-limit") == 0) {
        retainedLimitRegression();
        return fflush(stdout) != 0 ? 2 : 0;
    }
    if (argc != 1 && !reuse) return 1;
    while ((fields = scanf("%zu", &size)) != EOF) {
        unsigned char* source;
        size_t i;
        if (fields != 1 || size > 2 * 1024 * 1024) return 1;
        source = (unsigned char*)FUZZ_malloc(size);
        for (i = 0; i < size; i++) {
            unsigned value;
            if (scanf("%2x", &value) != 1) {
                free(source);
                return 1;
            }
            source[i] = (unsigned char)value;
        }
        fprintf(stderr, "request=%zu size=%zu\n", request++, size);
        if (original) LLVMFuzzerTestOneInput(source, size);
        else run(source, size);
        free(source);
        if (!reuse) {
            ZSTD_freeCCtx(cctx); cctx = NULL;
            ZSTD_freeDCtx(dctx); dctx = NULL;
        }
    }
    ZSTD_freeCCtx(cctx);
    ZSTD_freeDCtx(dctx);
    return ferror(stdin) || fflush(stdout) != 0 ? 2 : 0;
}
