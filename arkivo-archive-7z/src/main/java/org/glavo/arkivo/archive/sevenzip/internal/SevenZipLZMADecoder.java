// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.PasswordPurpose;
import org.glavo.arkivo.codec.bcj.BCJTransforms;
import org.glavo.arkivo.codec.transform.TransformingInputStream;
import org.glavo.arkivo.codec.transform.ByteTransform.Direction;
import org.glavo.arkivo.codec.delta.DeltaTransform;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// Opens supported coder streams stored in 7z folders.
@NotNullByDefault
final class SevenZipLZMADecoder {
    /// Indicates that a packed input size is unavailable to an internal compatibility overload.
    private static final long UNKNOWN_SIZE = -1L;

    /// The 7z Copy method ID.
    static final byte[] COPY_METHOD_ID = new byte[]{0x00};

    /// The 7z LZMA method ID.
    static final byte[] LZMA_METHOD_ID = new byte[]{0x03, 0x01, 0x01};

    /// The 7z LZMA2 method ID.
    static final byte[] LZMA2_METHOD_ID = new byte[]{0x21};

    /// The 7z Deflate method ID.
    static final byte[] DEFLATE_METHOD_ID = new byte[]{0x04, 0x01, 0x08};

    /// The 7z Deflate64 method ID.
    static final byte[] DEFLATE64_METHOD_ID = new byte[]{0x04, 0x01, 0x09};

    /// The 7z BZip2 method ID.
    static final byte[] BZIP2_METHOD_ID = new byte[]{0x04, 0x02, 0x02};

    /// The 7z Zstandard method ID.
    static final byte[] ZSTANDARD_METHOD_ID = new byte[]{0x04, (byte) 0xf7, 0x11, 0x01};

    /// The 7z PPMd7 method ID.
    static final byte[] PPMD_METHOD_ID = new byte[]{0x03, 0x04, 0x01};

    /// The 7z AES-256/SHA-256 encryption method ID.
    static final byte[] AES_METHOD_ID = new byte[]{0x06, (byte) 0xf1, 0x07, 0x01};

    /// The 7z Delta filter method ID.
    static final byte[] DELTA_METHOD_ID = new byte[]{0x03};

    /// The 7z x86 BCJ filter method ID.
    static final byte[] X86_FILTER_METHOD_ID = new byte[]{0x03, 0x03, 0x01, 0x03};

    /// The 7z x86 BCJ2 four-input filter method ID.
    static final byte[] BCJ2_FILTER_METHOD_ID = new byte[]{0x03, 0x03, 0x01, 0x1b};

    /// The 7z PowerPC BCJ filter method ID.
    static final byte[] POWERPC_FILTER_METHOD_ID = new byte[]{0x03, 0x03, 0x02, 0x05};

    /// The 7z IA-64 BCJ filter method ID.
    static final byte[] IA64_FILTER_METHOD_ID = new byte[]{0x03, 0x03, 0x04, 0x01};

    /// The 7z ARM BCJ filter method ID.
    static final byte[] ARM_FILTER_METHOD_ID = new byte[]{0x03, 0x03, 0x05, 0x01};

    /// The 7z ARM-Thumb BCJ filter method ID.
    static final byte[] ARM_THUMB_FILTER_METHOD_ID = new byte[]{0x03, 0x03, 0x07, 0x01};

    /// The 7z SPARC BCJ filter method ID.
    static final byte[] SPARC_FILTER_METHOD_ID = new byte[]{0x03, 0x03, 0x08, 0x05};

    /// The 7z ARM64 BCJ filter method ID.
    private static final byte @Unmodifiable [] ARM64_FILTER_METHOD_ID = new byte[]{0x0a};

    /// The 7z RISC-V BCJ filter method ID.
    private static final byte @Unmodifiable [] RISCV_FILTER_METHOD_ID = new byte[]{0x0b};

    /// Creates no instances.
    private SevenZipLZMADecoder() {
    }

    /// Returns whether the method ID identifies the Copy method.
    static boolean isCopy(byte[] methodId) {
        return Arrays.equals(methodId, COPY_METHOD_ID);
    }

    /// Returns whether the method ID identifies the LZMA method.
    static boolean isLZMA(byte[] methodId) {
        return Arrays.equals(methodId, LZMA_METHOD_ID);
    }

    /// Returns whether the method ID identifies the LZMA2 method.
    static boolean isLZMA2(byte[] methodId) {
        return Arrays.equals(methodId, LZMA2_METHOD_ID);
    }

    /// Returns whether the method ID identifies the Deflate method.
    static boolean isDeflate(byte[] methodId) {
        return Arrays.equals(methodId, DEFLATE_METHOD_ID);
    }

    /// Returns whether the method ID identifies the Deflate64 method.
    static boolean isDeflate64(byte[] methodId) {
        return Arrays.equals(methodId, DEFLATE64_METHOD_ID);
    }

    /// Returns whether the method ID identifies the BZip2 method.
    static boolean isBZip2(byte[] methodId) {
        return Arrays.equals(methodId, BZIP2_METHOD_ID);
    }

    /// Returns whether the method ID identifies the Zstandard method.
    static boolean isZstandard(byte[] methodId) {
        return Arrays.equals(methodId, ZSTANDARD_METHOD_ID);
    }

    /// Returns whether the method ID identifies the PPMd7 method.
    static boolean isPpmd(byte[] methodId) {
        return Arrays.equals(methodId, PPMD_METHOD_ID);
    }

    /// Returns whether the method ID identifies the AES-256/SHA-256 encryption filter.
    static boolean isAes(byte[] methodId) {
        return Arrays.equals(methodId, AES_METHOD_ID);
    }

    /// Returns whether the method ID identifies the Delta filter.
    static boolean isDelta(byte[] methodId) {
        return Arrays.equals(methodId, DELTA_METHOD_ID);
    }

    /// Returns whether the method ID identifies the x86 BCJ filter.
    static boolean isX86Filter(byte[] methodId) {
        return Arrays.equals(methodId, X86_FILTER_METHOD_ID);
    }

    /// Returns whether the method ID identifies the x86 BCJ2 multi-stream filter.
    static boolean isBcj2Filter(byte[] methodId) {
        return Arrays.equals(methodId, BCJ2_FILTER_METHOD_ID);
    }

    /// Returns whether the method ID identifies the PowerPC BCJ filter.
    static boolean isPowerPCFilter(byte[] methodId) {
        return Arrays.equals(methodId, POWERPC_FILTER_METHOD_ID);
    }

    /// Returns whether the method ID identifies the IA-64 BCJ filter.
    static boolean isIA64Filter(byte[] methodId) {
        return Arrays.equals(methodId, IA64_FILTER_METHOD_ID);
    }

    /// Returns whether the method ID identifies the ARM BCJ filter.
    static boolean isARMFilter(byte[] methodId) {
        return Arrays.equals(methodId, ARM_FILTER_METHOD_ID);
    }

    /// Returns whether the method ID identifies the ARM-Thumb BCJ filter.
    static boolean isARMThumbFilter(byte[] methodId) {
        return Arrays.equals(methodId, ARM_THUMB_FILTER_METHOD_ID);
    }

    /// Returns whether the method ID identifies the SPARC BCJ filter.
    static boolean isSPARCFilter(byte[] methodId) {
        return Arrays.equals(methodId, SPARC_FILTER_METHOD_ID);
    }

    /// Returns whether the method ID identifies the ARM64 BCJ filter.
    static boolean isARM64Filter(byte[] methodId) {
        return Arrays.equals(methodId, ARM64_FILTER_METHOD_ID);
    }

    /// Returns whether the method ID identifies the RISC-V BCJ filter.
    static boolean isRiscVFilter(byte[] methodId) {
        return Arrays.equals(methodId, RISCV_FILTER_METHOD_ID);
    }

    /// Returns whether the method ID identifies a supported BCJ filter.
    static boolean isBcjFilter(byte[] methodId) {
        return isX86Filter(methodId)
                || isPowerPCFilter(methodId)
                || isIA64Filter(methodId)
                || isARMFilter(methodId)
                || isARMThumbFilter(methodId)
                || isSPARCFilter(methodId)
                || isARM64Filter(methodId)
                || isRiscVFilter(methodId);
    }

    /// Returns whether the method ID identifies a supported decoder.
    static boolean isSupported(byte[] methodId) {
        return isCopy(methodId)
                || isLZMA(methodId)
                || isLZMA2(methodId)
                || isDeflate(methodId)
                || isDeflate64(methodId)
                || isBZip2(methodId)
                || isZstandard(methodId)
                || isPpmd(methodId)
                || isAes(methodId)
                || isDelta(methodId)
                || isBcj2Filter(methodId)
                || isBcjFilter(methodId);
    }

    /// Opens the decoder pipeline for a 7z folder method.
    static InputStream openFolder(
            InputStream input,
            SevenZipFolderMethod method,
            long finalOutputLimit
    ) throws IOException {
        return openFolder(input, method, finalOutputLimit, null);
    }

    /// Opens the decoder pipeline for a 7z folder method.
    static InputStream openFolder(
            InputStream input,
            SevenZipFolderMethod method,
            long finalOutputLimit,
            @Nullable ArkivoPasswordProvider passwordProvider
    ) throws IOException {
        return openFolder(List.of(input), method, finalOutputLimit, passwordProvider);
    }

    /// Opens the decoder graph for a 7z folder without exact physical packed input sizes.
    static InputStream openFolder(
            List<? extends InputStream> packedInputs,
            SevenZipFolderMethod method,
            long finalOutputLimit,
            @Nullable ArkivoPasswordProvider passwordProvider
    ) throws IOException {
        Objects.requireNonNull(packedInputs, "packedInputs");
        long[] packedInputSizes = new long[packedInputs.size()];
        Arrays.fill(packedInputSizes, UNKNOWN_SIZE);
        return openFolder(packedInputs, packedInputSizes, method, finalOutputLimit, passwordProvider);
    }

    /// Opens the decoder graph for a 7z folder with all physical packed inputs and their exact sizes.
    static InputStream openFolder(
            List<? extends InputStream> packedInputs,
            long[] packedInputSizes,
            SevenZipFolderMethod method,
            long finalOutputLimit,
            @Nullable ArkivoPasswordProvider passwordProvider
    ) throws IOException {
        return openFolder(
                packedInputs,
                packedInputSizes,
                method,
                finalOutputLimit,
                passwordProvider,
                PasswordPurpose.ARCHIVE_CONTENT,
                null,
                ArchiveReadLimits.UNLIMITED
        );
    }

    /// Opens the decoder graph with password context for a 7z folder.
    static InputStream openFolder(
            List<? extends InputStream> packedInputs,
            long[] packedInputSizes,
            SevenZipFolderMethod method,
            long finalOutputLimit,
            @Nullable ArkivoPasswordProvider passwordProvider,
            PasswordPurpose passwordPurpose,
            @Nullable String entryPath
    ) throws IOException {
        return openFolder(
                packedInputs,
                packedInputSizes,
                method,
                finalOutputLimit,
                passwordProvider,
                passwordPurpose,
                entryPath,
                ArchiveReadLimits.UNLIMITED
        );
    }

    /// Opens the decoder graph with password and resource-limit context for a 7z folder.
    static InputStream openFolder(
            List<? extends InputStream> packedInputs,
            long[] packedInputSizes,
            SevenZipFolderMethod method,
            long finalOutputLimit,
            @Nullable ArkivoPasswordProvider passwordProvider,
            PasswordPurpose passwordPurpose,
            @Nullable String entryPath,
            ArchiveReadLimits readLimits
    ) throws IOException {
        Objects.requireNonNull(packedInputs, "packedInputs");
        Objects.requireNonNull(packedInputSizes, "packedInputSizes");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(passwordPurpose, "passwordPurpose");
        Objects.requireNonNull(readLimits, "readLimits");
        if (finalOutputLimit < 0) {
            throw new IllegalArgumentException("finalOutputLimit must be non-negative");
        }
        if (packedInputs.size() != method.packedStreamCount()
                || packedInputSizes.length != method.packedStreamCount()) {
            throw new IOException("7z packed input count does not match the folder graph");
        }
        ArrayList<InputStream> inputs = new ArrayList<>(packedInputs.size());
        for (InputStream input : packedInputs) {
            inputs.add(Objects.requireNonNull(input, "packedInputs element"));
        }
        long[] inputSizes = packedInputSizes.clone();
        for (long inputSize : inputSizes) {
            if (inputSize < UNKNOWN_SIZE) {
                throw new IllegalArgumentException("packedInputSizes must contain non-negative values or UNKNOWN_SIZE");
            }
        }

        GraphBuilder builder = new GraphBuilder(
                inputs,
                inputSizes,
                method,
                passwordProvider,
                passwordPurpose,
                entryPath,
                readLimits
        );
        boolean completed = false;
        Throwable failure = null;
        try {
            InputStream result = builder.openFinalOutput(finalOutputLimit);
            completed = true;
            return result;
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            if (!completed) {
                try {
                    builder.closeRoots();
                } catch (IOException | RuntimeException | Error exception) {
                    if (failure != null) {
                        failure.addSuppressed(exception);
                    } else {
                        throw exception;
                    }
                }
            }
        }
    }

    /// Opens one supported single-input coder.
    private static InputStream openSingleInputCoder(
            InputStream input,
            byte[] methodId,
            byte[] properties,
            long outputLimit,
            @Nullable ArkivoPasswordProvider passwordProvider,
            PasswordPurpose passwordPurpose,
            @Nullable String entryPath,
            ArchiveReadLimits readLimits
    ) throws IOException {
        if (isCopy(methodId)) {
            return input;
        }
        if (isLZMA(methodId)) {
            return openLZMA(input, outputLimit, properties, readLimits);
        }
        if (isLZMA2(methodId)) {
            return openLZMA2(input, properties, outputLimit, readLimits);
        }
        if (isDeflate(methodId)) {
            return openDeflate(input, properties, outputLimit, readLimits);
        }
        if (isDeflate64(methodId)) {
            return openDeflate64(input, properties, outputLimit, readLimits);
        }
        if (isBZip2(methodId)) {
            return openBZip2(input, properties, outputLimit, readLimits);
        }
        if (isZstandard(methodId)) {
            return openZstandard(input, properties, outputLimit, readLimits);
        }
        if (isPpmd(methodId)) {
            return openPpmd(input, properties, outputLimit, readLimits);
        }
        if (isAes(methodId)) {
            return new SevenZipBoundedInputStream(
                    openAes(input, properties, passwordProvider, passwordPurpose, entryPath),
                    outputLimit
            );
        }
        if (isDelta(methodId)) {
            return openDelta(input, properties);
        }
        if (isBcjFilter(methodId)) {
            return openBcjFilter(input, methodId, properties);
        }
        throw new IOException("Unsupported 7z coder method: " + Arrays.toString(methodId));
    }

    /// Builds decoder streams by resolving coder graph dependencies from the final output.
    @NotNullByDefault
    private static final class GraphBuilder {
        /// The physical packed input streams in folder order.
        private final List<InputStream> packedInputs;

        /// The exact physical packed input sizes, or `UNKNOWN_SIZE` where unavailable.
        private final long @Unmodifiable [] packedInputSizes;

        /// The folder coder graph.
        private final SevenZipFolderMethod method;

        /// The password provider used by AES coder nodes.
        private final @Nullable ArkivoPasswordProvider passwordProvider;

        /// The protected material associated with AES password requests.
        private final PasswordPurpose passwordPurpose;

        /// The logical entry path associated with AES password requests, or `null`.
        private final @Nullable String entryPath;

        /// The archive-wide decoder resource limits.
        private final ArchiveReadLimits readLimits;

        /// The currently unowned stream roots that must be closed after setup failure.
        private final Set<InputStream> roots = Collections.newSetFromMap(new IdentityHashMap<>());

        /// Creates a graph builder over physical packed inputs.
        private GraphBuilder(
                List<InputStream> packedInputs,
                long[] packedInputSizes,
                SevenZipFolderMethod method,
                @Nullable ArkivoPasswordProvider passwordProvider,
                PasswordPurpose passwordPurpose,
                @Nullable String entryPath,
                ArchiveReadLimits readLimits
        ) {
            this.packedInputs = List.copyOf(packedInputs);
            this.packedInputSizes = packedInputSizes.clone();
            this.method = method;
            this.passwordProvider = passwordProvider;
            this.passwordPurpose = passwordPurpose;
            this.entryPath = entryPath;
            this.readLimits = Objects.requireNonNull(readLimits, "readLimits");
            roots.addAll(packedInputs);
        }

        /// Opens the sole unbound folder output stream.
        private InputStream openFinalOutput(long outputLimit) throws IOException {
            int finalOutputStreamIndex = method.finalOutputStreamIndex();
            if (outputLimit > method.unpackSize(finalOutputStreamIndex)) {
                throw new IOException("7z requested output exceeds the folder unpack size");
            }
            return openOutput(finalOutputStreamIndex, outputLimit);
        }

        /// Opens one folder output and recursively resolves all coder inputs.
        private InputStream openOutput(int outputStreamIndex, long outputLimit) throws IOException {
            int coderIndex = method.coderIndexForOutput(outputStreamIndex);
            if (method.outputStreamCount(coderIndex) != 1
                    || method.firstOutputStreamIndex(coderIndex) != outputStreamIndex) {
                throw new IOException("Unsupported 7z multi-output coder");
            }

            int inputCount = method.inputStreamCount(coderIndex);
            int firstInput = method.firstInputStreamIndex(coderIndex);
            ArrayList<InputStream> coderInputs = new ArrayList<>(inputCount);
            long[] coderInputSizes = new long[inputCount];
            for (int index = 0; index < inputCount; index++) {
                int inputStreamIndex = firstInput + index;
                int boundOutputStreamIndex = method.boundOutputStreamIndex(inputStreamIndex);
                if (boundOutputStreamIndex >= 0) {
                    long inputSize = method.unpackSize(boundOutputStreamIndex);
                    coderInputs.add(openOutput(boundOutputStreamIndex, inputSize));
                    coderInputSizes[index] = inputSize;
                } else {
                    int packedStreamOrdinal = method.packedStreamOrdinal(inputStreamIndex);
                    if (packedStreamOrdinal < 0) {
                        throw new IOException("7z folder input stream has no source");
                    }
                    coderInputs.add(packedInputs.get(packedStreamOrdinal));
                    coderInputSizes[index] = packedInputSizes[packedStreamOrdinal];
                }
            }

            byte[] methodId = method.methodId(coderIndex);
            byte[] properties = method.properties(coderIndex);
            InputStream output;
            if (isBcj2Filter(methodId)) {
                for (long coderInputSize : coderInputSizes) {
                    if (coderInputSize == UNKNOWN_SIZE) {
                        Arrays.fill(coderInputSizes, UNKNOWN_SIZE);
                        break;
                    }
                }
                if (inputCount != 4) {
                    throw new IOException("7z BCJ2 coder must consume four input streams");
                }
                if (properties.length != 0) {
                    throw new IOException("7z BCJ2 coder properties must be empty");
                }
                try {
                    output = new SevenZipBcj2InputStream(
                            coderInputs.get(0),
                            coderInputs.get(1),
                            coderInputs.get(2),
                            coderInputs.get(3),
                            coderInputSizes[0],
                            coderInputSizes[1],
                            coderInputSizes[2],
                            coderInputSizes[3],
                            outputLimit,
                            method.unpackSize(outputStreamIndex)
                    );
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Invalid 7z BCJ2 stream sizes", exception);
                }
            } else {
                if (inputCount != 1) {
                    throw new IOException("Unsupported 7z multi-input coder");
                }
                output = openSingleInputCoder(
                        coderInputs.get(0),
                        methodId,
                        properties,
                        outputLimit,
                        passwordProvider,
                        passwordPurpose,
                        entryPath,
                        readLimits
                );
            }
            for (InputStream coderInput : coderInputs) {
                roots.remove(coderInput);
            }
            roots.add(output);
            return output;
        }

        /// Closes every stream root retained after a failed graph setup.
        private void closeRoots() throws IOException {
            IOException failure = null;
            RuntimeException runtimeFailure = null;
            Error errorFailure = null;
            for (InputStream root : roots) {
                try {
                    root.close();
                } catch (IOException exception) {
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                } catch (RuntimeException exception) {
                    if (runtimeFailure == null) {
                        runtimeFailure = exception;
                    } else {
                        runtimeFailure.addSuppressed(exception);
                    }
                } catch (Error exception) {
                    if (errorFailure == null) {
                        errorFailure = exception;
                    } else {
                        errorFailure.addSuppressed(exception);
                    }
                }
            }
            if (failure != null) {
                if (runtimeFailure != null) {
                    failure.addSuppressed(runtimeFailure);
                }
                if (errorFailure != null) {
                    failure.addSuppressed(errorFailure);
                }
                throw failure;
            }
            if (runtimeFailure != null) {
                if (errorFailure != null) {
                    runtimeFailure.addSuppressed(errorFailure);
                }
                throw runtimeFailure;
            }
            if (errorFailure != null) {
                throw errorFailure;
            }
        }
    }

    /// Opens a 7z AES decrypting stream for coder properties.
    static InputStream openAes(
            InputStream input,
            byte[] properties,
            @Nullable ArkivoPasswordProvider passwordProvider,
            PasswordPurpose passwordPurpose,
            @Nullable String entryPath
    ) throws IOException {
        return SevenZipAesCrypto.openDecryptingStream(
                input,
                properties,
                passwordProvider,
                SevenZipPasswordSupport.request(passwordPurpose, entryPath)
        );
    }

    /// Opens a raw LZMA decoder for 7z coder properties.
    static InputStream openLZMA(
            InputStream input,
            long uncompressedSize,
            byte[] properties,
            ArchiveReadLimits readLimits
    ) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(properties, "properties");
        if (properties.length != 5) {
            throw new IOException("7z LZMA coder properties must contain five bytes");
        }
        long dictionarySize = Integer.toUnsignedLong(ByteArrayAccess.readIntLittleEndian(properties, 1));
        return SevenZipCompressionFormats.openRawLZMADecoder(
                input,
                Byte.toUnsignedInt(properties[0]),
                dictionarySize,
                uncompressedSize,
                readLimits
        );
    }

    /// Opens a raw LZMA2 decoder for 7z coder properties.
    static InputStream openLZMA2(
            InputStream input,
            byte[] properties,
            long maximumOutputSize,
            ArchiveReadLimits readLimits
    ) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(properties, "properties");
        if (properties.length != 1) {
            throw new IOException("7z LZMA2 coder properties must contain one byte");
        }
        int property = Byte.toUnsignedInt(properties[0]);
        if (property > 37) {
            throw new IOException("Unsupported 7z LZMA2 dictionary property");
        }
        long dictionarySize = (long) (2 | (property & 1)) << ((property >>> 1) + 11);
        return SevenZipCompressionFormats.openLZMA2Decoder(
                input,
                dictionarySize,
                maximumOutputSize,
                readLimits
        );
    }

    /// Opens a size-limited raw Deflate decoder for 7z coder properties.
    static InputStream openDeflate(
            InputStream input,
            byte[] properties,
            long maximumOutputSize,
            ArchiveReadLimits readLimits
    ) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(properties, "properties");
        if (properties.length != 0) {
            throw new IOException("7z Deflate coder properties must be empty");
        }
        return SevenZipCompressionFormats.newInputStream("deflate", input, maximumOutputSize, readLimits);
    }

    /// Opens a size-limited Deflate64 decoder for 7z coder properties.
    static InputStream openDeflate64(
            InputStream input,
            byte[] properties,
            long maximumOutputSize,
            ArchiveReadLimits readLimits
    ) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(properties, "properties");
        if (properties.length != 0) {
            throw new IOException("7z Deflate64 coder properties must be empty");
        }
        return SevenZipCompressionFormats.newInputStream("deflate64", input, maximumOutputSize, readLimits);
    }

    /// Opens a size-limited BZip2 decoder for 7z coder properties.
    static InputStream openBZip2(
            InputStream input,
            byte[] properties,
            long maximumOutputSize,
            ArchiveReadLimits readLimits
    ) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(properties, "properties");
        if (properties.length != 0) {
            throw new IOException("7z BZip2 coder properties must be empty");
        }
        return SevenZipCompressionFormats.newInputStream("bzip2", input, maximumOutputSize, readLimits);
    }

    /// Opens a size-limited Zstandard decoder for 7z coder properties.
    static InputStream openZstandard(
            InputStream input,
            byte[] properties,
            long maximumOutputSize,
            ArchiveReadLimits readLimits
    ) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(properties, "properties");
        if (properties.length != 0
                && properties.length != 1
                && properties.length != 3
                && properties.length != 5) {
            throw new IOException("7z Zstandard coder properties must contain zero, one, three, or five bytes");
        }
        return SevenZipCompressionFormats.newInputStream("zstd", input, maximumOutputSize, readLimits);
    }

    /// Opens an exactly sized PPMd7 decoder for 7z coder properties.
    static InputStream openPpmd(
            InputStream input,
            byte[] properties,
            long uncompressedSize,
            ArchiveReadLimits readLimits
    ) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(properties, "properties");
        if (properties.length != 5) {
            throw new IOException("7z PPMd coder properties must contain five bytes");
        }
        int maximumOrder = Byte.toUnsignedInt(properties[0]);
        long memorySize = Integer.toUnsignedLong(ByteArrayAccess.readIntLittleEndian(properties, 1));
        if (maximumOrder < 2 || maximumOrder > 64) {
            throw new IOException("Unsupported 7z PPMd maximum order: " + maximumOrder);
        }
        if (memorySize < 1L << 11 || memorySize > 256L << 20) {
            throw new IOException("Unsupported 7z PPMd memory size: " + memorySize);
        }
        return SevenZipCompressionFormats.openPpmdDecoder(
                input,
                maximumOrder,
                memorySize,
                uncompressedSize,
                readLimits
        );
    }

    /// Opens a raw Delta filter decoder for 7z coder properties.
    static InputStream openDelta(InputStream input, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(properties, "properties");
        if (properties.length != 1) {
            throw new IOException("7z Delta coder properties must contain one byte");
        }
        int distance = Byte.toUnsignedInt(properties[0]) + 1;
        return new TransformingInputStream(input, new DeltaTransform(Direction.DECODE, distance));
    }

    /// Opens a raw BCJ filter decoder for 7z coder properties.
    static InputStream openBcjFilter(InputStream input, byte[] methodId, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(methodId, "methodId");
        if (isX86Filter(methodId)) {
            return openX86Filter(input, properties);
        }
        if (isPowerPCFilter(methodId)) {
            return openPowerPCFilter(input, properties);
        }
        if (isIA64Filter(methodId)) {
            return openIA64Filter(input, properties);
        }
        if (isARMFilter(methodId)) {
            return openARMFilter(input, properties);
        }
        if (isARMThumbFilter(methodId)) {
            return openARMThumbFilter(input, properties);
        }
        if (isSPARCFilter(methodId)) {
            return openSPARCFilter(input, properties);
        }
        if (isARM64Filter(methodId)) {
            return openARM64Filter(input, properties);
        }
        if (isRiscVFilter(methodId)) {
            return openRiscVFilter(input, properties);
        }
        throw new IOException("Unsupported 7z coder method: " + Arrays.toString(methodId));
    }

    /// Opens a raw x86 BCJ filter decoder for 7z coder properties.
    static InputStream openX86Filter(InputStream input, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        long startOffset = bcjStartOffset(properties, "7z x86 BCJ coder", 1);
        return new TransformingInputStream(input, BCJTransforms.x86(Direction.DECODE, startOffset));
    }

    /// Opens a raw PowerPC BCJ filter decoder for 7z coder properties.
    static InputStream openPowerPCFilter(InputStream input, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        long startOffset = bcjStartOffset(properties, "7z PowerPC BCJ coder", 4);
        return new TransformingInputStream(input, BCJTransforms.powerPC(Direction.DECODE, startOffset));
    }

    /// Opens a raw IA-64 BCJ filter decoder for 7z coder properties.
    static InputStream openIA64Filter(InputStream input, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        long startOffset = bcjStartOffset(properties, "7z IA-64 BCJ coder", 16);
        return new TransformingInputStream(input, BCJTransforms.ia64(Direction.DECODE, startOffset));
    }

    /// Opens a raw ARM BCJ filter decoder for 7z coder properties.
    static InputStream openARMFilter(InputStream input, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        long startOffset = bcjStartOffset(properties, "7z ARM BCJ coder", 4);
        return new TransformingInputStream(input, BCJTransforms.arm(Direction.DECODE, startOffset));
    }

    /// Opens a raw ARM-Thumb BCJ filter decoder for 7z coder properties.
    static InputStream openARMThumbFilter(InputStream input, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        long startOffset = bcjStartOffset(properties, "7z ARM-Thumb BCJ coder", 2);
        return new TransformingInputStream(input, BCJTransforms.armThumb(Direction.DECODE, startOffset));
    }

    /// Opens a raw SPARC BCJ filter decoder for 7z coder properties.
    static InputStream openSPARCFilter(InputStream input, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        long startOffset = bcjStartOffset(properties, "7z SPARC BCJ coder", 4);
        return new TransformingInputStream(input, BCJTransforms.sparc(Direction.DECODE, startOffset));
    }

    /// Opens a raw ARM64 BCJ filter decoder for 7z coder properties.
    static InputStream openARM64Filter(InputStream input, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        long startOffset = bcjStartOffset(properties, "7z ARM64 BCJ coder", 4);
        return new TransformingInputStream(input, BCJTransforms.arm64(Direction.DECODE, startOffset));
    }

    /// Opens a raw RISC-V BCJ filter decoder for 7z coder properties.
    static InputStream openRiscVFilter(InputStream input, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        long startOffset = bcjStartOffset(properties, "7z RISC-V BCJ coder", 2);
        return new TransformingInputStream(input, BCJTransforms.riscV(Direction.DECODE, startOffset));
    }

    /// Reads BCJ filter properties as an optional little-endian start offset.
    private static long bcjStartOffset(
            byte[] properties,
            String description,
            int alignment
    ) throws IOException {
        Objects.requireNonNull(properties, "properties");
        if (properties.length == 0) {
            return 0;
        }
        if (properties.length != 4) {
            throw new IOException(description + " properties must contain zero or four bytes");
        }
        long startOffset = Integer.toUnsignedLong(ByteArrayAccess.readIntLittleEndian(properties, 0));
        if ((startOffset & (alignment - 1)) != 0) {
            throw new IOException(description + " start offset must be aligned to " + alignment);
        }
        return startOffset;
    }

}
