/* Copyright (c) Meta Platforms, Inc. and affiliates.
 * Copyright (c) 2026 Glavo
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Observes Zstandard 1.5.7 dictionary_round_trip.c without changing its assertions.
 * See LICENSES/Zstd-BSD-3-Clause.txt. Native code is used only for testing.
 */

#define ZSTD_STATIC_LINKING_ONLY
#include "zstd.h"
#include "zstd_helpers.h"
#include "fuzz_helpers.h"
#include <string.h>

/* Owns exported bytes independently of the original target's allocations. */
static unsigned char* dictionary;
static size_t dictionarySize;
static unsigned char* frame;
static size_t frameSize;
static size_t frameCapacity;

/* Records the original compression branch, repeated calls and final decoder loading mode. */
static int simple;
static int simpleLevel;
static unsigned calls;
static int contentType;
static int prefix;
static int load;

/* Preserves the original trainer's samples, parameters, failures and producer consumption. */
static FUZZ_dict_t traceTrain(const void* source, size_t size, FUZZ_dataProducer_t* producer) {
    FUZZ_dict_t result = FUZZ_train(source, size, producer);
    FUZZ_ASSERT(dictionary == NULL);
    dictionarySize = result.size;
    dictionary = (unsigned char*)FUZZ_malloc(result.size);
    if (result.size) memcpy(dictionary, result.buff, result.size);
    return result;
}

/* Retains each compression result while leaving determinism assertions to the upstream target. */
static size_t capture(const void* output, size_t capacity, size_t result) {
    FUZZ_ZASSERT(result);
    free(frame);
    frame = (unsigned char*)FUZZ_malloc(result);
    memcpy(frame, output, result);
    frameSize = result;
    frameCapacity = capacity;
    calls++;
    return result;
}

/* Observes the advanced compression branch without changing context state. */
static size_t traceCompress(ZSTD_CCtx* context, void* output, size_t capacity,
                            const void* source, size_t size) {
    return capture(output, capacity, ZSTD_compress2(context, output, capacity, source, size));
}

/* Records the explicit level used by the legacy dictionary compression branch. */
static size_t traceSimple(ZSTD_CCtx* context, void* output, size_t capacity,
                          const void* source, size_t size, const void* dict, size_t dictSize, int level) {
    simple = 1;
    simpleLevel = level;
    return capture(output, capacity,
            ZSTD_compress_usingDict(context, output, capacity, source, size, dict, dictSize, level));
}

/* Observes the decoder's exact content interpretation and copied/referenced loading mode. */
static size_t traceLoad(ZSTD_DCtx* context, const void* dict, size_t size,
                        ZSTD_dictLoadMethod_e mode, ZSTD_dictContentType_e type) {
    prefix = 0;
    contentType = (int)type;
    load = (int)mode;
    return ZSTD_DCtx_loadDictionary_advanced(context, dict, size, mode, type);
}

/* Observes single-use prefix interpretation without replacing it with persistent loading. */
static size_t tracePrefix(ZSTD_DCtx* context, const void* dict, size_t size, ZSTD_dictContentType_e type) {
    prefix = 1;
    contentType = (int)type;
    load = -1;
    return ZSTD_DCtx_refPrefix_advanced(context, dict, size, type);
}

#define STATEFUL_FUZZING
#define FUZZ_train traceTrain
#define ZSTD_compress2 traceCompress
#define ZSTD_compress_usingDict traceSimple
#define ZSTD_DCtx_loadDictionary_advanced traceLoad
#define ZSTD_DCtx_refPrefix_advanced tracePrefix
#include "dictionary_round_trip.c"
#undef FUZZ_train
#undef ZSTD_compress2
#undef ZSTD_compress_usingDict
#undef ZSTD_DCtx_loadDictionary_advanced
#undef ZSTD_DCtx_refPrefix_advanced

/* Writes one requested parameter; simple compression exports its explicit level separately. */
static void parameter(const char* name, ZSTD_cParameter key) {
    int value;
    FUZZ_ZASSERT(ZSTD_CCtx_getParameter(cctx, key, &value));
    if (key == ZSTD_c_compressionLevel && simple) value = simpleLevel;
    printf(" %s=%d", name, value);
}

/* Reads one bounded hexadecimal byte string with an explicit empty token. */
static unsigned char* readBytes(size_t* size) {
    unsigned char* bytes;
    size_t i;
    FUZZ_ASSERT(scanf("%zu", size) == 1 && *size <= 2 * 1024 * 1024);
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

/* Writes one whitespace-free byte field without adding a line terminator. */
static void writeBytes(const unsigned char* bytes, size_t size) {
    size_t i;
    if (!size) putchar('-');
    for (i = 0; i < size; i++) printf("%02x", (unsigned)bytes[i]);
}

/* Runs original trained-dictionary seeds and independently decodes Java frames with exported dictionaries. */
int main(int argc, char** argv) {
    int reuse = argc == 2 && strcmp(argv[1], "--reuse") == 0;
    char operation;
    size_t request = 0;
    if (argc != 1 && !reuse) return 1;
    while (scanf(" %c", &operation) == 1) {
        size_t size;
        unsigned char* source = readBytes(&size);
        fprintf(stderr, "request=%zu operation=%c size=%zu\n", request++, operation, size);
        if (operation == 'R') {
            FUZZ_dataProducer_t* producer = FUZZ_dataProducer_create(source, size);
            size_t plainSize = FUZZ_dataProducer_reserveDataPrefix(producer);
            size_t capacity = ZSTD_compressBound(plainSize) - FUZZ_dataProducer_uint32Range(producer, 0, 1);
            FUZZ_dataProducer_free(producer);
            calls = 0;
            simple = 0;
            contentType = prefix = load = -1;
            LLVMFuzzerTestOneInput(source, size);
            FUZZ_ASSERT(calls == 2 && frameCapacity == capacity && frameSize <= capacity);
            FUZZ_ASSERT(contentType >= 0 && prefix >= 0);
            printf("size=%zu capacity=%zu dictionary=%zu type=%d prefix=%d load=%d simple=%d calls=%u",
                    plainSize, capacity, dictionarySize, contentType, prefix, load, simple, calls);
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
            putchar(' ');
            writeBytes(dictionary, dictionarySize);
            putchar(' ');
            writeBytes(frame, frameSize);
            putchar('\n');
            free(dictionary); dictionary = NULL;
            free(frame); frame = NULL;
        } else if (operation == 'D') {
            int type, usePrefix, loading;
            size_t dictSize, encodedSize;
            unsigned char* dict;
            unsigned char* encoded;
            unsigned char* restored = (unsigned char*)FUZZ_malloc(size);
            ZSTD_DCtx* decoder = ZSTD_createDCtx();
            size_t result;
            FUZZ_ASSERT(decoder);
            FUZZ_ASSERT(scanf("%d %d %d", &type, &usePrefix, &loading) == 3);
            FUZZ_ASSERT(type >= 0 && type <= 2 && (usePrefix == 0 || usePrefix == 1));
            FUZZ_ASSERT(usePrefix || loading == 0 || loading == 1);
            dict = readBytes(&dictSize);
            encoded = readBytes(&encodedSize);
            if (usePrefix) FUZZ_ZASSERT(ZSTD_DCtx_refPrefix_advanced(decoder, dict, dictSize, (ZSTD_dictContentType_e)type));
            else FUZZ_ZASSERT(ZSTD_DCtx_loadDictionary_advanced(decoder, dict, dictSize,
                    (ZSTD_dictLoadMethod_e)loading, (ZSTD_dictContentType_e)type));
            result = ZSTD_decompressDCtx(decoder, restored, size, encoded, encodedSize);
            FUZZ_ZASSERT(result);
            FUZZ_ASSERT(result == size && !FUZZ_memcmp(restored, source, size));
            printf("%zu ", result);
            writeBytes(restored, result);
            putchar('\n');
            ZSTD_freeDCtx(decoder);
            free(restored);
            free(dict);
            free(encoded);
        } else {
            free(source);
            return 1;
        }
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
