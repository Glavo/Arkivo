/* Copyright (c) Meta Platforms, Inc. and affiliates.
 * Copyright (c) 2026 Glavo
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Adapts Zstandard 1.5.7 tests/fuzz/block_round_trip.c and block_decompress.c.
 * See LICENSES/Zstd-BSD-3-Clause.txt. Native code is used only for testing.
 */

#define ZSTD_STATIC_LINKING_ONLY
#define ZSTD_DISABLE_DEPRECATE_WARNINGS
#include "zstd.h"
#include "fuzz_helpers.h"
#include <string.h>

/* Contexts optionally retained between requests, matching STATEFUL_FUZZING. */
static ZSTD_CCtx* compressor;
static ZSTD_DCtx* decoder;

/* Work buffers retain their allocation across requests, but not a larger advertised capacity. */
static void* compressed;
static void* restored;
static size_t allocated;

/* Preserves the upstream zero-size allocation and growth rules. */
static void ensureBuffers(size_t size) {
    if (size > allocated || !compressed || !restored) {
        free(compressed);
        free(restored);
        compressed = FUZZ_malloc(size);
        restored = FUZZ_malloc(size);
        allocated = size;
    }
}

/* Emits one hexadecimal field, using a dash for empty data. */
static void writeHex(const void* bytes, size_t size) {
    const unsigned char* data = (const unsigned char*)bytes;
    size_t i;
    if (size == 0) putchar('-');
    for (i = 0; i < size; i++) printf("%02x", (unsigned)data[i]);
    putchar('\n');
}

/* Preserves the original prefix partition, -3..19 level range and untruncated output capacity. */
static void roundTrip(const unsigned char* source, size_t inputSize) {
    FUZZ_dataProducer_t* producer = FUZZ_dataProducer_create(source, inputSize);
    size_t capacity = FUZZ_dataProducer_reserveDataPrefix(producer);
    int level = FUZZ_dataProducer_int32Range(producer, -3, 19);
    size_t size = capacity > ZSTD_BLOCKSIZE_MAX ? ZSTD_BLOCKSIZE_MAX : capacity;
    ZSTD_parameters params = ZSTD_getParams(level, size, 0);
    size_t compressedSize;
    size_t restoredSize;
    ensureBuffers(capacity);
    if (!compressor) compressor = ZSTD_createCCtx();
    if (!decoder) decoder = ZSTD_createDCtx();
    FUZZ_ASSERT(compressor && decoder);
    FUZZ_ZASSERT(ZSTD_compressBegin_advanced(compressor, NULL, 0, params, size));
    compressedSize = ZSTD_compressBlock(compressor, compressed, capacity, source, size);
    FUZZ_ZASSERT(compressedSize);
    if (compressedSize == 0) {
        if (size != 0) memcpy(restored, source, size);
        restoredSize = size;
    } else {
        FUZZ_ZASSERT(ZSTD_decompressBegin(decoder));
        restoredSize = ZSTD_decompressBlock(decoder, restored, capacity, compressed, compressedSize);
        FUZZ_ZASSERT(restoredSize);
    }
    FUZZ_ASSERT(restoredSize == size && FUZZ_memcmp(source, restored, size) == 0);
    printf("%d %zu %zu %zu ", level, capacity, size, compressedSize);
    writeHex(compressed, compressedSize);
    FUZZ_dataProducer_free(producer);
}

/* Decodes the complete input into exactly the upstream 128-KiB capacity, resetting before every attempt. */
static void decompress(const unsigned char* source, size_t size) {
    size_t result;
    ensureBuffers(ZSTD_BLOCKSIZE_MAX);
    if (!decoder) decoder = ZSTD_createDCtx();
    FUZZ_ASSERT(decoder);
    FUZZ_ZASSERT(ZSTD_decompressBegin(decoder));
    result = ZSTD_decompressBlock(decoder, restored, ZSTD_BLOCKSIZE_MAX, source, size);
    if (ZSTD_isError(result)) {
        puts("error");
    } else {
        FUZZ_ASSERT(result <= ZSTD_BLOCKSIZE_MAX);
        printf("ok %zu ", result);
        writeHex(restored, result);
    }
}

/* Reads R (round-trip) or D (decode) requests; each contains a length and hexadecimal bytes. */
int main(int argc, char** argv) {
    int reuse = argc == 2 && strcmp(argv[1], "--reuse") == 0;
    int fields;
    char mode;
    size_t size;
    if (argc != 1 && !reuse) return 1;
    while ((fields = scanf(" %c %zu", &mode, &size)) != EOF) {
        unsigned char* source;
        size_t i;
        if (fields != 2 || (mode != 'R' && mode != 'D') || size > 1024 * 1024) return 1;
        source = (unsigned char*)FUZZ_malloc(size);
        for (i = 0; i < size; i++) {
            unsigned value;
            if (scanf("%2x", &value) != 1) {
                free(source);
                return 1;
            }
            source[i] = (unsigned char)value;
        }
        if (mode == 'R') roundTrip(source, size);
        else decompress(source, size);
        free(source);
        if (!reuse) {
            ZSTD_freeCCtx(compressor);
            compressor = NULL;
            ZSTD_freeDCtx(decoder);
            decoder = NULL;
        }
    }
    ZSTD_freeCCtx(compressor);
    ZSTD_freeDCtx(decoder);
    free(compressed);
    free(restored);
    return ferror(stdin) || fflush(stdout) != 0 ? 2 : 0;
}
