/* Copyright (c) Meta Platforms, Inc. and affiliates.
 * Copyright (c) 2026 Glavo
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Observes Zstandard 1.5.7 dictionary_loader.c without changing its assertions.
 * See LICENSES/Zstd-BSD-3-Clause.txt. Native code is used only for testing.
 */

#define ZSTD_STATIC_LINKING_ONLY
#include "zstd.h"
#include "zstd_errors.h"
#include "fuzz_helpers.h"
#include "fuzz_data_producer.h"

/* Writes a complete bounded result, with a nonempty field for empty byte strings. */
static void writeBytes(const void* data, size_t size) {
    const unsigned char* bytes = (const unsigned char*)data;
    size_t i;
    if (!size) putchar('-');
    for (i = 0; i < size; i++) printf("%02x", (unsigned)bytes[i]);
    putchar('\n');
}

/* Exports the original compression result before the target frees its buffer. */
static size_t traceCompress(ZSTD_CCtx* context, void* target, size_t capacity,
                            const void* source, size_t size) {
    size_t result = ZSTD_compress2(context, target, capacity, source, size);
    printf("%u %zu ", (unsigned)ZSTD_getErrorCode(result), ZSTD_isError(result) ? 0 : result);
    writeBytes(target, ZSTD_isError(result) ? 0 : result);
    return result;
}

#define ZSTD_compress2 traceCompress
#include "dictionary_loader.c"
#undef ZSTD_compress2

/* Reads one length-prefixed hexadecimal byte string, bounded independently of source metadata. */
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

/* Replays original loader inputs, or verifies Java frames with the same native dictionary-loading path. */
int main(void) {
    char operation;
    size_t request = 0;
    while (scanf(" %c", &operation) == 1) {
        size_t size;
        unsigned char* source = readBytes(&size);
        FUZZ_dataProducer_t* producer = FUZZ_dataProducer_create(source, size);
        unsigned prefix = FUZZ_dataProducer_uint32Range(producer, 0, 1);
        unsigned load = FUZZ_dataProducer_uint32Range(producer, 0, 1);
        unsigned type = FUZZ_dataProducer_uint32Range(producer, 0, 2);
        size_t remaining = FUZZ_dataProducer_remainingBytes(producer);
        fprintf(stderr, "request=%zu operation=%c size=%zu type=%u load=%u prefix=%u\n",
                request++, operation, remaining, type, load, prefix);
        FUZZ_dataProducer_free(producer);
        if (operation == 'L') {
            printf("%zu %u %u %u ", remaining, type, load, prefix);
            LLVMFuzzerTestOneInput(source, size);
        } else if (operation == 'D') {
            size_t frameSize;
            unsigned char* frame = readBytes(&frameSize);
            void* restored = FUZZ_malloc(remaining);
            size_t restoredSize = decompress(restored, remaining, frame, frameSize, source, remaining,
                    (ZSTD_dictLoadMethod_e)load, (ZSTD_dictContentType_e)type, (int)prefix);
            FUZZ_ASSERT(restoredSize == remaining && !FUZZ_memcmp(restored, source, remaining));
            printf("%zu ", restoredSize);
            writeBytes(restored, restoredSize);
            free(restored);
            free(frame);
        } else {
            free(source);
            return 1;
        }
        free(source);
    }
    return ferror(stdin) || fflush(stdout) != 0 ? 2 : 0;
}
