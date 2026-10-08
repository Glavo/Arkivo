/* Copyright (c) Meta Platforms, Inc. and affiliates.
 * Copyright (c) 2026 Glavo
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Adapts Zstandard 1.5.7 tests/fuzz/huf_round_trip.c. The verified source
 * download retains the upstream LICENSE; see LICENSES/Zstd-BSD-3-Clause.txt.
 * This test-only adapter preserves
 * parameter consumption and native assertions and exports the generated
 * table and bitstream for independent Java decoding.
 */

#include "common/cpu.h"
#include "common/bits.h"
#include "common/huf.h"
#include "compress/hist.h"
#include "fuzz_helpers.h"
#include <string.h>

/* Writes one nonempty byte sequence as a whitespace-delimited hexadecimal field. */
static void writeHex(const void* bytes, size_t size) {
    const unsigned char* data = (const unsigned char*)bytes;
    size_t i;
    FUZZ_ASSERT(size > 0);
    for (i = 0; i < size; i++) printf("%02x", (unsigned)data[i]);
}

/* Preserves the upstream alphabet-dependent minimum table size. */
static unsigned adjustTableLog(unsigned tableLog, unsigned maxSymbol) {
    unsigned alphabetSize = maxSymbol + 1;
    unsigned minimum = ZSTD_highbit32(alphabetSize) + 1;
    if ((alphabetSize & (alphabetSize - 1)) != 0) minimum++;
    FUZZ_ASSERT(minimum <= 9);
    return tableLog < minimum ? minimum : tableLog;
}

/* Emits the upstream selected sizes, skip reason or independently verified Huffman encoding. */
static void roundTrip(const unsigned char* source, size_t inputSize, int bmi2) {
    FUZZ_dataProducer_t* producer = FUZZ_dataProducer_create(source, inputSize);
    int streams = FUZZ_dataProducer_int32Range(producer, 0, 1);
    int symbols = FUZZ_dataProducer_int32Range(producer, 0, 1);
    int flags = (bmi2 && FUZZ_dataProducer_int32Range(producer, 0, 1) ? HUF_flags_bmi2 : 0)
        | (FUZZ_dataProducer_int32Range(producer, 0, 1) ? HUF_flags_optimalDepth : 0)
        | (FUZZ_dataProducer_int32Range(producer, 0, 1) ? HUF_flags_preferRepeat : 0)
        | (FUZZ_dataProducer_int32Range(producer, 0, 1) ? HUF_flags_suspectUncompressible : 0)
        | (FUZZ_dataProducer_int32Range(producer, 0, 1) ? HUF_flags_disableAsm : 0)
        | (FUZZ_dataProducer_int32Range(producer, 0, 1) ? HUF_flags_disableFast : 0);
    size_t capacity = FUZZ_dataProducer_uint32Range(producer, 0, 4 * inputSize);
    unsigned requestedLog = FUZZ_dataProducer_uint32Range(producer, 1, 12);
    size_t size = FUZZ_dataProducer_remainingBytes(producer);
    unsigned maxSymbol = 255;
    unsigned count[256];
    size_t mostFrequent;
    unsigned tableLog;
    U64 workspace[HUF_WORKSPACE_SIZE_U64];
    HUF_CElt ct[HUF_CTABLE_SIZE_ST(255)];
    HUF_DTable dt[HUF_DTABLE_SIZE(12)];
    unsigned char table[HUF_CTABLEBOUND];
    unsigned char weights[256];
    unsigned rankStats[HUF_TABLELOG_MAX + 1];
    unsigned weightCount;
    unsigned weightLog;
    void* compressed;
    void* restored;
    size_t tableSize;
    size_t built;
    size_t compressedSize;
    size_t restoredSize;
    if (size > 256 * 1024) size = 256 * 1024;
    printf("%d %zu %zu %u ", streams, size, capacity, requestedLog);
    if (size <= 1) {
        puts("small");
        FUZZ_dataProducer_free(producer);
        return;
    }
    mostFrequent = HIST_count(count, &maxSymbol, source, size);
    FUZZ_ZASSERT(mostFrequent);
    if (mostFrequent == size) {
        puts("rle");
        FUZZ_dataProducer_free(producer);
        return;
    }
    tableLog = adjustTableLog(requestedLog, maxSymbol);
    dt[0] = tableLog * 0x01000001;
    tableLog = HUF_optimalTableLog(tableLog, size, maxSymbol, workspace, sizeof(workspace), ct, count, flags);
    FUZZ_ASSERT(tableLog <= 12);
    built = HUF_buildCTable_wksp(ct, count, maxSymbol, tableLog, workspace, sizeof(workspace));
    FUZZ_ZASSERT(built);
    tableLog = (unsigned)built;
    compressed = FUZZ_malloc(capacity);
    restored = FUZZ_malloc(size);
    tableSize = HUF_writeCTable_wksp(compressed, capacity, ct, maxSymbol, tableLog, workspace, sizeof(workspace));
    if (ERR_isError(tableSize)) {
        puts("table");
        goto cleanup;
    }
    FUZZ_ASSERT(tableSize > 0 && tableSize <= sizeof(table));
    memcpy(table, compressed, tableSize);
    if (symbols == 0) {
        FUZZ_ZASSERT(HUF_readDTableX1_wksp(dt, compressed, tableSize, workspace, sizeof(workspace), flags));
    } else {
        size_t result = HUF_readDTableX2_wksp(dt, compressed, tableSize, workspace, sizeof(workspace), flags);
        if (ERR_getErrorCode(result) == ZSTD_error_tableLog_tooLarge) {
            FUZZ_ZASSERT(HUF_readDTableX1_wksp(dt, compressed, tableSize, workspace, sizeof(workspace), flags));
        } else {
            FUZZ_ZASSERT(result);
        }
    }
    compressedSize = streams == 0
        ? HUF_compress1X_usingCTable(compressed, capacity, source, size, ct, flags)
        : HUF_compress4X_usingCTable(compressed, capacity, source, size, ct, flags);
    FUZZ_ZASSERT(compressedSize);
    if (compressedSize == 0) {
        puts("no-stream");
        goto cleanup;
    }
    restoredSize = streams == 0
        ? HUF_decompress1X_usingDTable(restored, size, compressed, compressedSize, dt, flags)
        : HUF_decompress4X_usingDTable(restored, size, compressed, compressedSize, dt, flags);
    FUZZ_ZASSERT(restoredSize);
    FUZZ_ASSERT(restoredSize == size && memcmp(source, restored, size) == 0);
    FUZZ_ASSERT(HUF_readStats(weights, sizeof(weights), rankStats, &weightCount, &weightLog, table, tableSize) == tableSize);
    FUZZ_ASSERT(weightLog == tableLog && weightCount == maxSymbol + 1);
    printf("ok %u ", tableLog);
    writeHex(weights, weightCount);
    putchar(' ');
    writeHex(table, tableSize);
    putchar(' ');
    writeHex(compressed, compressedSize);
    putchar('\n');
cleanup:
    free(compressed);
    free(restored);
    FUZZ_dataProducer_free(producer);
}

/* Reads bounded, length-prefixed hexadecimal fuzz seeds; --cpu reports the conditional parameter layout. */
int main(int argc, char** argv) {
    int bmi2 = ZSTD_cpuid_bmi2(ZSTD_cpuid()) != 0;
    size_t size;
    int fields;
    if (argc == 2 && strcmp(argv[1], "--cpu") == 0) {
        printf("%d\n", bmi2);
        return 0;
    }
    if (argc != 1) return 1;
    while ((fields = scanf("%zu", &size)) != EOF) {
        unsigned char* source;
        size_t i;
        if (fields != 1 || size > 1024 * 1024) return 1;
        source = (unsigned char*)FUZZ_malloc(size);
        for (i = 0; i < size; i++) {
            unsigned value;
            if (scanf("%2x", &value) != 1) {
                free(source);
                return 1;
            }
            source[i] = (unsigned char)value;
        }
        roundTrip(source, size, bmi2);
        free(source);
    }
    return ferror(stdin) || fflush(stdout) != 0 ? 2 : 0;
}
