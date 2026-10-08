/* Copyright (c) Meta Platforms, Inc. and affiliates.
 * Copyright (c) 2026 Glavo
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Observes Zstandard 1.5.7 dictionary_decompress.c without replacing its trainer or loader.
 * See LICENSES/Zstd-BSD-3-Clause.txt. Native code is used only for testing.
 */

#define ZSTD_STATIC_LINKING_ONLY
#include "zstd.h"
#include "zstd_errors.h"
#include "zstd_helpers.h"
#include "fuzz_helpers.h"
#include <string.h>

/* Owns exported training and successful decoding results before upstream frees its buffers. */
static unsigned char* dictionary;
static size_t dictionarySize;
static unsigned char* output;
static size_t outputSize;
static size_t capacity;
static unsigned error;
static unsigned calls;

/* Records prepared, loaded and prefix modes with their actual C argument-evaluation results. */
static int mode;
static int contentType;
static int load;

/* Retains official sample selection, trainer settings, failure behavior and parameter consumption. */
static FUZZ_dict_t traceTrain(const void* source, size_t size, FUZZ_dataProducer_t* producer) {
    FUZZ_dict_t result = FUZZ_train(source, size, producer);
    FUZZ_ASSERT(dictionary == NULL);
    dictionarySize = result.size;
    dictionary = (unsigned char*)FUZZ_malloc(result.size);
    if (result.size) memcpy(dictionary, result.buff, result.size);
    return result;
}

/* Observes the independently prepared dictionary branch. */
static ZSTD_DDict* traceCreate(const void* dict, size_t size) {
    mode = 0;
    contentType = 0;
    load = 0;
    return ZSTD_createDDict(dict, size);
}

/* Records the native loader's content interpretation and ownership choice. */
static size_t traceLoad(ZSTD_DCtx* context, const void* dict, size_t size,
                        ZSTD_dictLoadMethod_e loading, ZSTD_dictContentType_e type) {
    mode = 1;
    contentType = (int)type;
    load = (int)loading;
    return ZSTD_DCtx_loadDictionary_advanced(context, dict, size, loading, type);
}

/* Records the original single-use prefix branch. */
static size_t tracePrefix(ZSTD_DCtx* context, const void* dict, size_t size, ZSTD_dictContentType_e type) {
    mode = 2;
    contentType = (int)type;
    load = -1;
    return ZSTD_DCtx_refPrefix_advanced(context, dict, size, type);
}

/* Captures success or error without asserting that malformed input must decode. */
static size_t capture(const void* decoded, size_t bound, size_t result) {
    capacity = bound;
    error = (unsigned)ZSTD_getErrorCode(result);
    outputSize = ZSTD_isError(result) ? 0 : result;
    FUZZ_ASSERT(outputSize <= bound && calls++ == 0);
    output = (unsigned char*)FUZZ_malloc(outputSize);
    if (outputSize) memcpy(output, decoded, outputSize);
    return result;
}

/* Observes decoding with a prepared dictionary, preserving the original output capacity. */
static size_t tracePrepared(ZSTD_DCtx* context, void* decoded, size_t bound,
                            const void* source, size_t size, const ZSTD_DDict* dict) {
    return capture(decoded, bound, ZSTD_decompress_usingDDict(context, decoded, bound, source, size, dict));
}

/* Observes decoding with loaded state or a prefix. */
static size_t traceDecode(ZSTD_DCtx* context, void* decoded, size_t bound, const void* source, size_t size) {
    return capture(decoded, bound, ZSTD_decompressDCtx(context, decoded, bound, source, size));
}

#define STATEFUL_FUZZING
#define FUZZ_train traceTrain
#define ZSTD_createDDict traceCreate
#define ZSTD_DCtx_loadDictionary_advanced traceLoad
#define ZSTD_DCtx_refPrefix_advanced tracePrefix
#define ZSTD_decompress_usingDDict tracePrepared
#define ZSTD_decompressDCtx traceDecode
#include "dictionary_decompress.c"
#undef FUZZ_train
#undef ZSTD_createDDict
#undef ZSTD_DCtx_loadDictionary_advanced
#undef ZSTD_DCtx_refPrefix_advanced
#undef ZSTD_decompress_usingDDict
#undef ZSTD_decompressDCtx

/* Writes one whitespace-free byte field, including an explicit empty token. */
static void writeBytes(const unsigned char* bytes, size_t size) {
    size_t i;
    if (!size) putchar('-');
    for (i = 0; i < size; i++) printf("%02x", (unsigned)bytes[i]);
}

/* Runs bounded original seeds with fresh or retained native decoder state. */
int main(int argc, char** argv) {
    int reuse = argc == 2 && strcmp(argv[1], "--reuse") == 0;
    size_t request = 0;
    size_t size;
    int fields;
    if (argc != 1 && !reuse) return 1;
    while ((fields = scanf("%zu", &size)) != EOF) {
        unsigned char* source;
        FUZZ_dataProducer_t* producer;
        size_t payloadSize;
        size_t i;
        FUZZ_ASSERT(fields == 1 && size <= 2 * 1024 * 1024);
        source = (unsigned char*)FUZZ_malloc(size);
        if (!size) {
            char empty;
            FUZZ_ASSERT(scanf(" %c", &empty) == 1 && empty == '-');
        }
        for (i = 0; i < size; i++) {
            unsigned value;
            FUZZ_ASSERT(scanf("%2x", &value) == 1);
            source[i] = (unsigned char)value;
        }
        producer = FUZZ_dataProducer_create(source, size);
        payloadSize = FUZZ_dataProducer_reserveDataPrefix(producer);
        FUZZ_dataProducer_free(producer);
        fprintf(stderr, "request=%zu size=%zu payload=%zu\n", request++, size, payloadSize);
        calls = 0;
        mode = contentType = load = -1;
        LLVMFuzzerTestOneInput(source, size);
        FUZZ_ASSERT(calls == 1 && capacity <= 10 * payloadSize && mode >= 0);
        printf("%zu %zu %d %d %d %u %zu ", payloadSize, capacity, mode, contentType, load, error, outputSize);
        writeBytes(dictionary, dictionarySize);
        putchar(' ');
        writeBytes(output, outputSize);
        putchar('\n');
        free(dictionary); dictionary = NULL;
        free(output); output = NULL;
        free(source);
        if (!reuse) {
            ZSTD_freeDCtx(dctx); dctx = NULL;
        }
    }
    ZSTD_freeDCtx(dctx);
    return ferror(stdin) || fflush(stdout) != 0 ? 2 : 0;
}
