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

#ifdef ARKIVO_DICTIONARY_STREAM
/* Exports the original interpretation and lifetime without modifying the trainer or its entropy consumption. */
static void dictionary(const void* bytes, size_t size, int type, int prefix, int load) {
    size_t i;
    printf("D %d %d %d %zu ", type, prefix, load, size);
    if (!size) putchar('-');
    for (i = 0; i < size; i++) printf("%02x", (unsigned)((const unsigned char*)bytes)[i]);
    putchar('\n');
}

/* Observes persistent dictionary loading, including copied and referenced variants. */
static size_t traceLoad(ZSTD_CCtx* context, const void* bytes, size_t size,
                        ZSTD_dictLoadMethod_e load, ZSTD_dictContentType_e type) {
    dictionary(bytes, size, type, 0, load);
    return ZSTD_CCtx_loadDictionary_advanced(context, bytes, size, load, type);
}

/* Observes a prefix that is consumed by the first frame only. */
static size_t tracePrefix(ZSTD_CCtx* context, const void* bytes, size_t size, ZSTD_dictContentType_e type) {
    dictionary(bytes, size, type, 1, -1);
    return ZSTD_CCtx_refPrefix_advanced(context, bytes, size, type);
}

/* Reads bounded hexadecimal input for independent verification of Java-produced streams. */
static unsigned char* readBytes(size_t* size) {
    unsigned char* bytes;
    size_t i;
    FUZZ_ASSERT(scanf("%zu", size) == 1 && *size <= 32 * 1024 * 1024);
    bytes = (unsigned char*)FUZZ_malloc(*size);
    if (!*size) {
        char empty;
        FUZZ_ASSERT(scanf(" %c", &empty) == 1 && empty == '-');
    }
    for (i = 0; i < *size; i++) {
        unsigned value;
        FUZZ_ASSERT(scanf("%2x", &value) == 1);
        bytes[i] = (unsigned char)value;
    }
    return bytes;
}

/* Checks complete streams using the original dictionary interpretation and prefix lifetime. */
static int decodeRequests(void) {
    int type, prefix;
    while (scanf("%d %d", &type, &prefix) == 2) {
        size_t plainSize, dictSize, encodedSize, result;
        unsigned char* plain = readBytes(&plainSize);
        unsigned char* dict = readBytes(&dictSize);
        unsigned char* encoded = readBytes(&encodedSize);
        unsigned char* output = (unsigned char*)FUZZ_malloc(plainSize + 1);
        ZSTD_DCtx* decoder = ZSTD_createDCtx();
        FUZZ_ASSERT(decoder && type >= 0 && type <= 2 && (prefix == 0 || prefix == 1));
        if (prefix) FUZZ_ZASSERT(ZSTD_DCtx_refPrefix_advanced(decoder, dict, dictSize, (ZSTD_dictContentType_e)type));
        else FUZZ_ZASSERT(ZSTD_DCtx_loadDictionary_advanced(decoder, dict, dictSize,
                ZSTD_dlm_byCopy, (ZSTD_dictContentType_e)type));
        result = ZSTD_decompressDCtx(decoder, output, plainSize, encoded, encodedSize);
        FUZZ_ZASSERT(result);
        FUZZ_ASSERT(result == plainSize && !FUZZ_memcmp(plain, output, plainSize));
        /* Independently require every flush to expose its accepted plaintext without later input. */
        FUZZ_ZASSERT(ZSTD_DCtx_reset(decoder, ZSTD_reset_session_and_parameters));
        if (prefix) FUZZ_ZASSERT(ZSTD_DCtx_refPrefix_advanced(decoder, dict, dictSize, (ZSTD_dictContentType_e)type));
        else FUZZ_ZASSERT(ZSTD_DCtx_loadDictionary_advanced(decoder, dict, dictSize,
                ZSTD_dlm_byCopy, (ZSTD_dictContentType_e)type));
        {
            size_t count, index;
            ZSTD_inBuffer in = { encoded, 0, 0 };
            ZSTD_outBuffer out = { output, plainSize + 1, 0 };
            FUZZ_ASSERT(scanf("%zu", &count) == 1 && count <= encodedSize + 1);
            for (index = 0; index < count; index++) {
                size_t encodedEnd, decodedEnd;
                int frameEnd;
                FUZZ_ASSERT(scanf("%zu %zu %d", &encodedEnd, &decodedEnd, &frameEnd) == 3);
                FUZZ_ASSERT(encodedEnd >= in.pos && encodedEnd <= encodedSize && decodedEnd <= plainSize);
                in.size = encodedEnd;
                while (in.pos < in.size) {
                    size_t beforeInput = in.pos, beforeOutput = out.pos;
                    result = ZSTD_decompressStream(decoder, &out, &in);
                    FUZZ_ZASSERT(result);
                    FUZZ_ASSERT(in.pos > beforeInput || out.pos > beforeOutput);
                }
                FUZZ_ASSERT((result == 0) == frameEnd && out.pos == decodedEnd);
                FUZZ_ASSERT(!FUZZ_memcmp(plain, output, decodedEnd));
            }
            FUZZ_ASSERT(in.pos == encodedSize && out.pos == plainSize && result == 0);
        }
        puts("OK");
        ZSTD_freeDCtx(decoder);
        free(plain); free(dict); free(encoded); free(output);
    }
    return ferror(stdin) || fflush(stdout) != 0 ? 2 : 0;
}
#endif

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
#ifdef ARKIVO_DICTIONARY_STREAM
#define ZSTD_CCtx_loadDictionary_advanced traceLoad
#define ZSTD_CCtx_refPrefix_advanced tracePrefix
#include "dictionary_stream_round_trip.c"
#undef ZSTD_CCtx_loadDictionary_advanced
#undef ZSTD_CCtx_refPrefix_advanced
#else
#include "stream_round_trip.c"
#endif
#undef FUZZ_setRandomParameters
#undef ZSTD_compressStream2

/* Runs original finite inputs and exports their logical actions and concatenated physical frames. */
int main(int argc, char** argv) {
#ifdef ARKIVO_DICTIONARY_STREAM
    if (argc == 2 && strcmp(argv[1], "--decode") == 0) return decodeRequests();
#endif
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
