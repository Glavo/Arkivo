/* Copyright (c) Meta Platforms, Inc. and affiliates.
 * Copyright (c) 2026 Glavo
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Observes Zstandard 1.5.7 seekable_roundtrip.c without changing its assertions or parameter consumption.
 * See LICENSES/Zstd-BSD-3-Clause.txt. Native code is used only for testing.
 */

#include "zstd.h"
#include "zstd_seekable.h"
#include "fuzz_helpers.h"
#include "fuzz_data_producer.h"
#include <string.h>

/* Owns the original target's completed encoding after it releases its output buffer. */
static unsigned char* encoded;
static size_t encodedSize;

/* Copies the original end operation's output without changing its return value or retry policy. */
static size_t traceEnd(ZSTD_seekable_CStream* context, ZSTD_outBuffer* output) {
    size_t result = ZSTD_seekable_endStream(context, output);
    FUZZ_ZASSERT(result);
    free(encoded);
    encodedSize = output->pos;
    encoded = (unsigned char*)FUZZ_malloc(encodedSize);
    memcpy(encoded, output->dst, encodedSize);
    return result;
}

#define STATEFUL_FUZZING
#define ZSTD_seekable_endStream traceEnd
#include "seekable_roundtrip.c"
#undef ZSTD_seekable_endStream

/* Reads one bounded byte string, using a dash for empty input. */
static unsigned char* readBytes(size_t* size) {
    size_t i;
    unsigned char* bytes;
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

/* Verifies a range against independent plaintext after an arbitrary preceding seek. */
static void verifyRange(const unsigned char* plain, size_t size, size_t offset, size_t count) {
    unsigned char* output = (unsigned char*)FUZZ_malloc(count);
    size_t result;
    FUZZ_ASSERT(offset <= size && count <= size - offset);
    result = ZSTD_seekable_decompress(stream, output, count, offset);
    FUZZ_ZASSERT(result);
    FUZZ_ASSERT(result == count && !FUZZ_memcmp(plain + offset, output, count));
    free(output);
}

/* Executes original seeds or independently checks Java seekable streams using fresh or retained contexts. */
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
            int level = FUZZ_dataProducer_int32Range(producer, ZSTD_minCLevel(), ZSTD_maxCLevel());
            unsigned checksum = FUZZ_dataProducer_int32Range(producer, 0, 1);
            size_t count = FUZZ_dataProducer_uint32Range(producer, 0, plainSize);
            size_t offset = FUZZ_dataProducer_uint32Range(producer, 0, plainSize - count);
            size_t i;
            FUZZ_dataProducer_free(producer);
            LLVMFuzzerTestOneInput(source, size);
            printf("%zu %d %u %zu %zu ", plainSize, level, checksum, count, offset);
            for (i = 0; i < encodedSize; i++) printf("%02x", (unsigned)encoded[i]);
            putchar('\n');
        } else if (operation == 'D') {
            size_t compressedSize, offset, count;
            unsigned char* compressed = readBytes(&compressedSize);
            FUZZ_ASSERT(scanf("%zu %zu", &offset, &count) == 2);
            if (!stream) stream = ZSTD_seekable_create();
            FUZZ_ASSERT(stream);
            FUZZ_ZASSERT(ZSTD_seekable_initBuff(stream, compressed, compressedSize));
            verifyRange(source, size, offset, count);
            verifyRange(source, size, size, 0);
            if (size) {
                verifyRange(source, size, size - 1, 1);
                verifyRange(source, size, 0, 1);
            }
            verifyRange(source, size, 0, size);
            puts("OK");
            free(compressed);
        } else return 1;
        free(source);
        if (!reuse) {
            ZSTD_seekable_free(stream); stream = NULL;
            ZSTD_seekable_freeCStream(zscs); zscs = NULL;
        }
    }
    free(encoded);
    ZSTD_seekable_free(stream);
    ZSTD_seekable_freeCStream(zscs);
    return ferror(stdin) || fflush(stdout) != 0 ? 2 : 0;
}
