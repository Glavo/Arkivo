/* Copyright (c) 2026 Glavo
 * SPDX-License-Identifier: MPL-2.0
 */

#define FSE_STATIC_LINKING_ONLY
#include "fse.h"
#include <stdio.h>
#include <string.h>

/* Reads batches of normalized counts and Java table descriptions from stdin.
 * Each request contains tableLog, maxSymbol, inputSize, signed counts and input
 * bytes as hexadecimal. The response contains the native description and native
 * decoding states, serialized explicitly rather than as host-dependent structs.
 */
int main(void) {
    unsigned tableLog;
    unsigned maxSymbol;
    unsigned inputSize;
    unsigned caseNumber = 0;
    int fields;
    while ((fields = scanf("%u %u %u", &tableLog, &maxSymbol, &inputSize)) != EOF) {
        short counts[256] = {0};
        short decoded[256] = {0};
        unsigned char input[512];
        unsigned char encoded[512];
        FSE_DTable table[FSE_DTABLE_SIZE_U32(FSE_MAX_TABLELOG)];
        unsigned workspace[FSE_BUILD_DTABLE_WKSP_SIZE_U32(FSE_MAX_TABLELOG, 255)];
        unsigned total = 0;
        unsigned symbol;
        unsigned decodedMax = 255;
        unsigned decodedLog = 0;
        size_t encodedSize;
        size_t consumed;
        size_t built;
        const FSE_decode_t* states;
        caseNumber++;
        if (fields != 3 || tableLog < FSE_MIN_TABLELOG || tableLog > FSE_MAX_TABLELOG
                || maxSymbol > 255 || inputSize == 0 || inputSize > sizeof(input)) goto invalid;
        for (symbol = 0; symbol <= maxSymbol; symbol++) {
            int count;
            if (scanf("%d", &count) != 1 || count < -1 || count > (1 << tableLog)) goto invalid;
            counts[symbol] = (short)count;
            total += count < 0 ? 1 : (unsigned)count;
        }
        if (total != (1U << tableLog) || counts[maxSymbol] == 0) goto invalid;
        for (symbol = 0; symbol < inputSize; symbol++) {
            unsigned value;
            if (scanf("%2x", &value) != 1) goto invalid;
            input[symbol] = (unsigned char)value;
        }
        encodedSize = FSE_writeNCount(encoded, sizeof(encoded), counts, maxSymbol, tableLog);
        if (FSE_isError(encodedSize)) goto invalid;
        consumed = FSE_readNCount(decoded, &decodedMax, &decodedLog, input, inputSize);
        if (FSE_isError(consumed) || consumed != encodedSize || decodedMax != maxSymbol
                || decodedLog != tableLog || memcmp(counts, decoded, (maxSymbol + 1) * sizeof(short)) != 0)
            goto invalid;
        built = FSE_buildDTable_wksp(table, decoded, decodedMax, decodedLog, workspace, sizeof(workspace));
        if (FSE_isError(built)) goto invalid;
        for (symbol = 0; symbol < encodedSize; symbol++) printf("%02x", (unsigned)encoded[symbol]);
        putchar(' ');
        states = (const FSE_decode_t*)(table + 1);
        for (symbol = 0; symbol < (1U << tableLog); symbol++) {
            printf("%02x%02x%02x%02x", (unsigned)states[symbol].symbol, (unsigned)states[symbol].nbBits,
                (unsigned)states[symbol].newState & 255, (unsigned)states[symbol].newState >> 8);
        }
        putchar('\n');
        continue;
    invalid:
        fprintf(stderr, "Invalid FSE request or reference mismatch at case %u\n", caseNumber);
        return 1;
    }
    return ferror(stdin) || fflush(stdout) != 0 ? 2 : 0;
}
