/* Copyright (c) Meta Platforms, Inc. and affiliates.
 * Copyright (c) 2026 Glavo
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Adapts the prefix lifetime tests in Zstandard 1.5.7 tests/zstreamtest.c.
 * See LICENSES/Zstd-BSD-3-Clause.txt. Native code is used only for testing.
 */

#define ZSTD_STATIC_LINKING_ONLY
#include "zstd.h"
#include "fuzz_helpers.h"
#include <string.h>

/* Owns one bounded file read or output allocation. */
typedef struct {
    void* data;
    size_t size;
} Buffer;

/* Reads an entire test input; malformed command input must not cause an unbounded allocation. */
static Buffer readFile(const char* name) {
    FILE* file = fopen(name, "rb");
    long size;
    Buffer result;
    FUZZ_ASSERT(file != NULL);
    FUZZ_ASSERT(fseek(file, 0, SEEK_END) == 0);
    size = ftell(file);
    FUZZ_ASSERT(size >= 0 && size <= 32 * 1024 * 1024);
    FUZZ_ASSERT(fseek(file, 0, SEEK_SET) == 0);
    result.size = (size_t)size;
    result.data = FUZZ_malloc(result.size);
    FUZZ_ASSERT(fread(result.data, 1, result.size, file) == result.size);
    FUZZ_ASSERT(fclose(file) == 0);
    return result;
}

/* Writes a generated frame for independent Java decoding. */
static void writeFile(const char* name, const void* data, size_t size) {
    FILE* file = fopen(name, "wb");
    FUZZ_ASSERT(file != NULL);
    FUZZ_ASSERT(fwrite(data, 1, size, file) == size);
    FUZZ_ASSERT(fclose(file) == 0);
}

/* Ends one complete frame without resetting the compressor or reattaching a prefix. */
static size_t compressFrame(ZSTD_CCtx* context, Buffer plain, Buffer compressed) {
    ZSTD_inBuffer input = {plain.data, plain.size, 0};
    ZSTD_outBuffer output = {compressed.data, compressed.size, 0};
    size_t result = ZSTD_compressStream2(context, &output, &input, ZSTD_e_end);
    FUZZ_ZASSERT(result);
    FUZZ_ASSERT(result == 0 && input.pos == input.size);
    return output.pos;
}

/* Verifies exact plaintext, one-frame prefix consumption, and recovery by reattaching the prefix. */
static void checkPrefix(ZSTD_DCtx* context, Buffer dictionary, Buffer frame, Buffer plain,
                        Buffer output, int raw) {
    unsigned attempt;
    FUZZ_ZASSERT(ZSTD_DCtx_reset(context, ZSTD_reset_session_and_parameters));
    for (attempt = 0; attempt < 3; attempt++) {
        ZSTD_inBuffer input = {frame.data, frame.size, 0};
        ZSTD_outBuffer decoded = {output.data, output.size, 0};
        size_t result;
        if (attempt != 1) {
            if (attempt == 2) FUZZ_ZASSERT(ZSTD_DCtx_reset(context, ZSTD_reset_session_only));
            if (raw) {
                FUZZ_ZASSERT(ZSTD_DCtx_refPrefix(context, dictionary.data, dictionary.size));
            } else {
                FUZZ_ZASSERT(ZSTD_DCtx_refPrefix_advanced(context, dictionary.data, dictionary.size, ZSTD_dct_auto));
            }
        }
        result = ZSTD_decompressStream(context, &decoded, &input);
        if (attempt == 1) {
            FUZZ_ASSERT(ZSTD_isError(result));
        } else {
            FUZZ_ZASSERT(result);
            FUZZ_ASSERT(result == 0 && input.pos == input.size && decoded.pos == plain.size);
            FUZZ_ASSERT(FUZZ_memcmp(output.data, plain.data, plain.size) == 0);
        }
    }
    /* A separate context must also reject the frame without its prefix. */
    FUZZ_ASSERT(ZSTD_isError(ZSTD_decompress(output.data, output.size, frame.data, frame.size)));
}

/* Exchanges frames using raw or auto-detected full prefixes and checks native one-frame lifetimes. */
int main(int argc, char** argv) {
    int raw;
    Buffer dictionary, plain, javaFrame, compressed, decoded;
    ZSTD_CCtx* compressor;
    ZSTD_DCtx* decoder;
    size_t size;
    if (argc != 2 || (strcmp(argv[1], "raw") != 0 && strcmp(argv[1], "full") != 0)) return 1;
    raw = strcmp(argv[1], "raw") == 0;
    dictionary = readFile("dictionary.bin");
    plain = readFile("plain.bin");
    javaFrame = readFile("java.zst");
    FUZZ_ASSERT(ZSTD_getDictID_fromDict(dictionary.data, dictionary.size) != 0);
    compressed.size = ZSTD_compressBound(plain.size);
    compressed.data = FUZZ_malloc(compressed.size);
    decoded.size = plain.size;
    decoded.data = FUZZ_malloc(decoded.size);
    compressor = ZSTD_createCCtx();
    decoder = ZSTD_createDCtx();
    FUZZ_ASSERT(compressor && decoder);
    FUZZ_ZASSERT(ZSTD_CCtx_setParameter(compressor, ZSTD_c_compressionLevel, 1));
    FUZZ_ZASSERT(ZSTD_CCtx_setParameter(compressor, ZSTD_c_checksumFlag, 1));
    FUZZ_ZASSERT(ZSTD_CCtx_loadDictionary(compressor, dictionary.data, dictionary.size));
    if (raw) {
        /* The same formatted bytes become raw history, replacing the previously loaded dictionary. */
        FUZZ_ZASSERT(ZSTD_CCtx_refPrefix(compressor, dictionary.data, dictionary.size));
    }
    size = compressFrame(compressor, plain, compressed);
    {
        Buffer frame = {compressed.data, size};
        FUZZ_ASSERT(ZSTD_getDictID_fromFrame(frame.data, frame.size) ==
                    (raw ? 0 : ZSTD_getDictID_fromDict(dictionary.data, dictionary.size)));
        checkPrefix(decoder, dictionary, frame, plain, decoded, raw);
    }
    writeFile("prefix.zst", compressed.data, size);
    checkPrefix(decoder, dictionary, javaFrame, plain, decoded, raw);

    /* Loaded dictionaries persist and require explicit removal. Raw refPrefix must clear itself. */
    if (!raw) FUZZ_ZASSERT(ZSTD_CCtx_loadDictionary(compressor, NULL, 0));
    size = compressFrame(compressor, plain, compressed);
    FUZZ_ASSERT(ZSTD_getDictID_fromFrame(compressed.data, size) == 0);
    {
        size_t decodedSize = ZSTD_decompress(decoded.data, decoded.size, compressed.data, size);
        FUZZ_ZASSERT(decodedSize);
        FUZZ_ASSERT(decodedSize == plain.size && FUZZ_memcmp(decoded.data, plain.data, plain.size) == 0);
    }
    writeFile("independent.zst", compressed.data, size);
    ZSTD_freeCCtx(compressor);
    ZSTD_freeDCtx(decoder);
    free(dictionary.data);
    free(plain.data);
    free(javaFrame.data);
    free(compressed.data);
    free(decoded.data);
    puts("prefix-consumed independent-frame-verified");
    return fflush(stdout) == 0 ? 0 : 2;
}
