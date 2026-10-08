/* Copyright (c) Meta Platforms, Inc. and affiliates.
 * Copyright (c) 2026 Glavo
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Observes Zstandard 1.5.7 raw_dictionary_round_trip.c without changing its assertions.
 * See LICENSES/Zstd-BSD-3-Clause.txt. Native code is used only for testing.
 */

#define ZSTD_STATIC_LINKING_ONLY
#include "zstd.h"
#include "fuzz_helpers.h"
#include "fuzz_data_producer.h"
#include <string.h>

/* Owns a copy of the frame before the original target frees its output allocation. */
static unsigned char* frame;
static size_t frameSize;
static size_t frameCapacity;

/* Records native copy/reference loading; minus one means the single-use prefix path was selected. */
static int compressionLoad;
static int decompressionLoad;

/* Exports native parameters after the original compressor has adjusted them and disabled checksums. */
static void parameter(ZSTD_CCtx* context, const char* name, ZSTD_cParameter key) {
    int value;
    FUZZ_ZASSERT(ZSTD_CCtx_getParameter(context, key, &value));
    printf(" %s=%d", name, value);
}

/* Preserves the original compression capacity and result, retaining only the produced bytes. */
static size_t traceCompress(ZSTD_CCtx* context, void* output, size_t capacity, const void* input, size_t size) {
    size_t result = ZSTD_compress2(context, output, capacity, input, size);
    FUZZ_ZASSERT(result);
    frame = (unsigned char*)FUZZ_malloc(result);
    memcpy(frame, output, result);
    frameSize = result;
    frameCapacity = capacity;
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
    return result;
}

/* Records compression dictionary ownership while invoking the original native loader. */
static size_t traceCompressionLoad(ZSTD_CCtx* context, const void* dictionary, size_t size,
                                   ZSTD_dictLoadMethod_e mode, ZSTD_dictContentType_e type) {
    compressionLoad = (int)mode;
    return ZSTD_CCtx_loadDictionary_advanced(context, dictionary, size, mode, type);
}

/* Records decompression dictionary ownership while invoking the original native loader. */
static size_t traceDecompressionLoad(ZSTD_DCtx* context, const void* dictionary, size_t size,
                                     ZSTD_dictLoadMethod_e mode, ZSTD_dictContentType_e type) {
    decompressionLoad = (int)mode;
    return ZSTD_DCtx_loadDictionary_advanced(context, dictionary, size, mode, type);
}

#define STATEFUL_FUZZING
#define ZSTD_compress2 traceCompress
#define ZSTD_CCtx_loadDictionary_advanced traceCompressionLoad
#define ZSTD_DCtx_loadDictionary_advanced traceDecompressionLoad
#include "raw_dictionary_round_trip.c"
#undef ZSTD_compress2
#undef ZSTD_CCtx_loadDictionary_advanced
#undef ZSTD_DCtx_loadDictionary_advanced

/* Reads one bounded hexadecimal field, including an explicit token for empty bytes. */
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

/* Writes one complete output field without requiring dictionary bytes to be valid UTF-8. */
static void writeBytes(const unsigned char* bytes, size_t size) {
    size_t i;
    if (!size) putchar('-');
    for (i = 0; i < size; i++) printf("%02x", (unsigned)bytes[i]);
    putchar('\n');
}

/* Runs finite original inputs with fresh or retained contexts, and independently decodes Java frames as raw history. */
int main(int argc, char** argv) {
    int reuse = argc == 2 && strcmp(argv[1], "--reuse") == 0;
    char operation;
    size_t request = 0;
    if (argc != 1 && !reuse) return 1;
    while (scanf(" %c", &operation) == 1) {
        size_t size;
        unsigned char* source = readBytes(&size);
        FUZZ_dataProducer_t* producer = FUZZ_dataProducer_create(source, size);
        size_t total = FUZZ_dataProducer_reserveDataPrefix(producer);
        size_t plainSize = FUZZ_dataProducer_uint32Range(producer, 0, total);
        size_t capacity = ZSTD_compressBound(plainSize) - FUZZ_dataProducer_uint32Range(producer, 0, 1);
        unsigned prefix = FUZZ_dataProducer_uint32Range(producer, 0, 1);
        size_t dictSize = total - plainSize;
        FUZZ_dataProducer_free(producer);
        fprintf(stderr, "request=%zu operation=%c size=%zu dictionary=%zu prefix=%u\n",
                request++, operation, plainSize, dictSize, prefix);
        if (operation == 'R') {
            compressionLoad = decompressionLoad = -1;
            printf("size=%zu dictionary=%zu capacity=%zu prefix=%u", plainSize, dictSize, capacity, prefix);
            LLVMFuzzerTestOneInput(source, size);
            FUZZ_ASSERT(frameCapacity == capacity && frameSize <= capacity);
            printf(" compressionLoad=%d decompressionLoad=%d ", compressionLoad, decompressionLoad);
            writeBytes(frame, frameSize);
            free(frame); frame = NULL;
        } else if (operation == 'D') {
            size_t encodedSize;
            unsigned char* encoded = readBytes(&encodedSize);
            unsigned char* restored = (unsigned char*)FUZZ_malloc(plainSize);
            ZSTD_DCtx* decoder = ZSTD_createDCtx();
            size_t result;
            FUZZ_ASSERT(decoder);
            if (prefix) FUZZ_ZASSERT(ZSTD_DCtx_refPrefix_advanced(decoder, source + plainSize, dictSize, ZSTD_dct_rawContent));
            else FUZZ_ZASSERT(ZSTD_DCtx_loadDictionary_advanced(decoder, source + plainSize, dictSize,
                    ZSTD_dlm_byCopy, ZSTD_dct_rawContent));
            result = ZSTD_decompressDCtx(decoder, restored, plainSize, encoded, encodedSize);
            FUZZ_ZASSERT(result);
            FUZZ_ASSERT(result == plainSize && !FUZZ_memcmp(restored, source, plainSize));
            printf("%zu ", result);
            writeBytes(restored, result);
            ZSTD_freeDCtx(decoder);
            free(restored);
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
