/* Copyright (c) Meta Platforms, Inc. and affiliates.
 * Copyright (c) 2026 Glavo
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Observes Zstandard 1.5.7 stream_round_trip.c without changing its operation loop.
 * See LICENSES/Zstd-BSD-3-Clause.txt. Native code is used only for testing.
 */

#define ZSTD_STATIC_LINKING_ONLY
#include "zstd.h"
#include "zstd_helpers.h"
#include "fuzz_helpers.h"

/* Accepted plaintext, emitted encoding and completed frames within the current input. */
static size_t accepted;
static size_t produced;
static size_t frames;

/* Emits one requested native parameter after the original parameter producer has run. */
static void parameter(ZSTD_CCtx* context, const char* name, ZSTD_cParameter key) {
    int value;
    FUZZ_ZASSERT(ZSTD_CCtx_getParameter(context, key, &value));
    printf(" %s=%d", name, value);
}

/* Records immutable Java-applicable settings, including each mid-seed reconfiguration. */
static void traceParameters(ZSTD_CCtx* context, size_t size, FUZZ_dataProducer_t* producer) {
    FUZZ_setRandomParameters(context, size, producer);
    printf("P");
    parameter(context, "level", ZSTD_c_compressionLevel);
    parameter(context, "window", ZSTD_c_windowLog);
    parameter(context, "hash", ZSTD_c_hashLog);
    parameter(context, "chain", ZSTD_c_chainLog);
    parameter(context, "search", ZSTD_c_searchLog);
    parameter(context, "match", ZSTD_c_minMatch);
    parameter(context, "target", ZSTD_c_targetLength);
    parameter(context, "strategy", ZSTD_c_strategy);
    parameter(context, "contentSize", ZSTD_c_contentSizeFlag);
    parameter(context, "checksum", ZSTD_c_checksumFlag);
    parameter(context, "dictionaryId", ZSTD_c_dictIDFlag);
    parameter(context, "ldm", ZSTD_c_enableLongDistanceMatching);
    parameter(context, "ldmHash", ZSTD_c_ldmHashLog);
    parameter(context, "ldmMatch", ZSTD_c_ldmMinMatch);
    parameter(context, "ldmBucket", ZSTD_c_ldmBucketSizeLog);
    parameter(context, "ldmRate", ZSTD_c_ldmHashRateLog);
    parameter(context, "workers", ZSTD_c_nbWorkers);
    putchar('\n');
}

/* Observes real input acceptance and completed actions; native output-only retries still run unchanged. */
static size_t traceCompress(ZSTD_CCtx* context, ZSTD_outBuffer* out,
                            ZSTD_inBuffer* in, ZSTD_EndDirective directive) {
    size_t before = in->pos;
    size_t beforeOutput = out->pos;
    size_t result = ZSTD_compressStream2(context, out, in, directive);
    FUZZ_ZASSERT(result);
    if (in->pos != before) {
        printf("I %zu %zu %zu\n", accepted, in->pos - before, out->size);
        accepted += in->pos - before;
    }
    produced += out->pos - beforeOutput;
    if (directive == ZSTD_e_continue && in->size == 0 && out->size == 0) puts("N");
    if (directive == ZSTD_e_flush && result == 0) printf("F %zu %zu %zu\n", accepted, produced, out->size);
    if (directive == ZSTD_e_end && result == 0) {
        printf("E %zu %zu %zu\n", accepted, produced, out->size);
        frames++;
    }
    return result;
}

#define STATEFUL_FUZZING
#define FUZZ_setRandomParameters traceParameters
#define ZSTD_compressStream2 traceCompress
#include "stream_round_trip.c"
#undef FUZZ_setRandomParameters
#undef ZSTD_compressStream2

/* Runs original finite inputs and exports their logical actions and concatenated physical frames. */
int main(int argc, char** argv) {
    int original = argc == 2 && strcmp(argv[1], "--original-reuse") == 0;
    int reuse = original || (argc == 2 && strcmp(argv[1], "--reuse") == 0);
    int fields;
    size_t inputSize;
    size_t request = 0;
    if (argc != 1 && !reuse) return 1;
    while ((fields = scanf("%zu", &inputSize)) != EOF) {
        unsigned char* source;
        FUZZ_dataProducer_t* producer;
        size_t size;
        size_t i;
        if (fields != 1 || inputSize > 2 * 1024 * 1024) return 1;
        source = (unsigned char*)FUZZ_malloc(inputSize);
        for (i = 0; i < inputSize; i++) {
            unsigned value;
            if (scanf("%2x", &value) != 1) {
                free(source);
                return 1;
            }
            source[i] = (unsigned char)value;
        }
        producer = FUZZ_dataProducer_create(source, inputSize);
        size = FUZZ_dataProducer_reserveDataPrefix(producer);
        FUZZ_dataProducer_free(producer);
        fprintf(stderr, "request=%zu size=%zu\n", request++, size);
        printf("B %zu %zu\n", size, ZSTD_compressBound(size) * 15);
        accepted = produced = frames = 0;
        /* As in simple_round_trip.c, the optional decoder limit can otherwise leak between independent seeds. */
        if (!original && dctx) FUZZ_ZASSERT(ZSTD_DCtx_setParameter(dctx, ZSTD_d_maxBlockSize, 0));
        LLVMFuzzerTestOneInput(source, inputSize);
        FUZZ_ASSERT(accepted == size && produced <= bufSize && frames > 0);
        printf("R %zu %zu ", produced, frames);
        for (i = 0; i < produced; i++) printf("%02x", (unsigned)cBuf[i]);
        putchar('\n');
        free(source);
        if (!reuse) {
            ZSTD_freeCCtx(cctx); cctx = NULL;
            ZSTD_freeDCtx(dctx); dctx = NULL;
        }
    }
    ZSTD_freeCCtx(cctx);
    ZSTD_freeDCtx(dctx);
    free(cBuf);
    free(rBuf);
    return ferror(stdin) || fflush(stdout) != 0 ? 2 : 0;
}
