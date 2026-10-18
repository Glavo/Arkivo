// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.internal.ForceCloseable;
import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.PasswordPurpose;
import org.glavo.arkivo.archive.sevenzip.SevenZipCoderMethod;
import org.glavo.arkivo.codec.transform.TransformingInputStream;
import org.glavo.arkivo.codec.transform.ByteTransform;
import org.glavo.arkivo.codec.transform.ByteTransform.Direction;
import org.glavo.arkivo.codec.xz.internal.filter.BCJTransforms;
import org.glavo.arkivo.codec.xz.internal.filter.DeltaTransform;
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
final class SevenZipFolderDecoder {
    /// The Copy identifier used to recognize folders that need no decoding.
    private static final byte @Unmodifiable [] COPY_METHOD_ID = SevenZipCoderMethod.COPY.methodId();

    /// Creates no instances.
    private SevenZipFolderDecoder() {
    }

    /// Returns whether the method ID identifies the Copy method.
    static boolean isCopy(byte[] methodId) {
        return Arrays.equals(methodId, COPY_METHOD_ID);
    }

    /// Resolves a coder ID, reporting unsupported methods as I/O errors.
    static SevenZipCoderMethod parseMethod(byte[] methodId) throws IOException {
        try {
            return SevenZipCoderMethod.fromMethodId(methodId);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Unsupported 7z coder method: " + Arrays.toString(methodId), exception);
        }
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
            if (inputSize < 0L) {
                throw new IllegalArgumentException("packedInputSizes must contain only non-negative values");
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
                        if (failure != exception) {
                            failure.addSuppressed(exception);
                        }
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
            SevenZipCoderMethod method,
            byte[] properties,
            long outputLimit,
            @Nullable ArkivoPasswordProvider passwordProvider,
            PasswordPurpose passwordPurpose,
            @Nullable String entryPath,
            ArchiveReadLimits readLimits
    ) throws IOException {
        return switch (method) {
            case COPY -> input;
            case LZMA -> openLZMA(input, outputLimit, properties, readLimits);
            case LZMA2 -> openLZMA2(input, properties, outputLimit, readLimits);
            case DEFLATE -> openDeflate(input, properties, outputLimit, readLimits);
            case DEFLATE64 -> openDeflate64(input, properties, outputLimit, readLimits);
            case BZIP2 -> openBZip2(input, properties, outputLimit, readLimits);
            case ZSTANDARD -> openZstandard(input, properties, outputLimit, readLimits);
            case PPMD -> openPpmd(input, properties, outputLimit, readLimits);
            case AES -> new SevenZipBoundedInputStream(
                    openAes(input, properties, passwordProvider, passwordPurpose, entryPath), outputLimit);
            case DELTA -> openDelta(input, properties);
            case BCJ_X86 -> openX86Filter(input, properties);
            case BCJ_POWERPC -> openPowerPCFilter(input, properties);
            case BCJ_IA64 -> openIA64Filter(input, properties);
            case BCJ_ARM -> openARMFilter(input, properties);
            case BCJ_ARM_THUMB -> openARMThumbFilter(input, properties);
            case BCJ_SPARC -> openSPARCFilter(input, properties);
            case BCJ_ARM64 -> openARM64Filter(input, properties);
            case BCJ_RISCV -> openRiscVFilter(input, properties);
            case BCJ2 -> throw new IOException("7z BCJ2 coder must consume four input streams");
        };
    }

    /// Builds decoder streams by resolving coder graph dependencies from the final output.
    @NotNullByDefault
    private static final class GraphBuilder {
        /// The physical packed input streams in folder order.
        private final List<InputStream> packedInputs;

        /// The exact physical packed input sizes.
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

            SevenZipCoderMethod coderMethod = parseMethod(method.methodId(coderIndex));
            byte[] properties = method.properties(coderIndex);
            InputStream output;
            if (coderMethod == SevenZipCoderMethod.BCJ2) {
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
                        coderMethod,
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
                    ForceCloseable.forceClose(root);
                } catch (IOException exception) {
                    if (failure == null) {
                        failure = exception;
                    } else {
                        if (failure != exception) {
                            failure.addSuppressed(exception);
                        }
                    }
                } catch (RuntimeException exception) {
                    if (runtimeFailure == null) {
                        runtimeFailure = exception;
                    } else {
                        if (runtimeFailure != exception) {
                            runtimeFailure.addSuppressed(exception);
                        }
                    }
                } catch (Error exception) {
                    if (errorFailure == null) {
                        errorFailure = exception;
                    } else {
                        if (errorFailure != exception) {
                            errorFailure.addSuppressed(exception);
                        }
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
        return new FilteredInputStream(input, new DeltaTransform(Direction.DECODE, distance));
    }

    /// Opens a raw x86 BCJ filter decoder for 7z coder properties.
    static InputStream openX86Filter(InputStream input, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        long startOffset = bcjStartOffset(properties, "7z x86 BCJ coder", 1);
        return new FilteredInputStream(input, BCJTransforms.x86(Direction.DECODE, startOffset));
    }

    /// Opens a raw PowerPC BCJ filter decoder for 7z coder properties.
    static InputStream openPowerPCFilter(InputStream input, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        long startOffset = bcjStartOffset(properties, "7z PowerPC BCJ coder", 4);
        return new FilteredInputStream(input, BCJTransforms.powerPC(Direction.DECODE, startOffset));
    }

    /// Opens a raw IA-64 BCJ filter decoder for 7z coder properties.
    static InputStream openIA64Filter(InputStream input, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        long startOffset = bcjStartOffset(properties, "7z IA-64 BCJ coder", 16);
        return new FilteredInputStream(input, BCJTransforms.ia64(Direction.DECODE, startOffset));
    }

    /// Opens a raw ARM BCJ filter decoder for 7z coder properties.
    static InputStream openARMFilter(InputStream input, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        long startOffset = bcjStartOffset(properties, "7z ARM BCJ coder", 4);
        return new FilteredInputStream(input, BCJTransforms.arm(Direction.DECODE, startOffset));
    }

    /// Opens a raw ARM-Thumb BCJ filter decoder for 7z coder properties.
    static InputStream openARMThumbFilter(InputStream input, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        long startOffset = bcjStartOffset(properties, "7z ARM-Thumb BCJ coder", 2);
        return new FilteredInputStream(input, BCJTransforms.armThumb(Direction.DECODE, startOffset));
    }

    /// Opens a raw SPARC BCJ filter decoder for 7z coder properties.
    static InputStream openSPARCFilter(InputStream input, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        long startOffset = bcjStartOffset(properties, "7z SPARC BCJ coder", 4);
        return new FilteredInputStream(input, BCJTransforms.sparc(Direction.DECODE, startOffset));
    }

    /// Opens a raw ARM64 BCJ filter decoder for 7z coder properties.
    static InputStream openARM64Filter(InputStream input, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        long startOffset = bcjStartOffset(properties, "7z ARM64 BCJ coder", 4);
        return new FilteredInputStream(input, BCJTransforms.arm64(Direction.DECODE, startOffset));
    }

    /// Opens a raw RISC-V BCJ filter decoder for 7z coder properties.
    static InputStream openRiscVFilter(InputStream input, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        long startOffset = bcjStartOffset(properties, "7z RISC-V BCJ coder", 2);
        return new FilteredInputStream(input, BCJTransforms.riscV(Direction.DECODE, startOffset));
    }

    /// Applies a folder filter and cancels its owned input before waiting for graceful closure.
    @NotNullByDefault
    private static final class FilteredInputStream extends InputStream implements ForceCloseable {
        /// The owned input to cancel before waiting for ordinary filter closure.
        private final InputStream input;

        /// The stateful filter and its ordinary input lifecycle.
        private final TransformingInputStream filtered;

        /// Whether reads are forbidden and cleanup must cancel the input.
        private volatile boolean aborted;

        /// Creates one filter over its owned folder input.
        private FilteredInputStream(InputStream input, ByteTransform transform) {
            this.input = input;
            filtered = new TransformingInputStream(input, transform);
        }

        /// Reads one filtered byte unless cancellation has started.
        @Override
        public int read() throws IOException {
            requireNotAborted();
            return filtered.read();
        }

        /// Reads filtered bytes unless cancellation has started.
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            requireNotAborted();
            return filtered.read(bytes, offset, length);
        }

        /// Returns already filtered bytes unless cancellation has started.
        @Override
        public int available() throws IOException {
            requireNotAborted();
            return filtered.available();
        }

        /// Closes normally, or retries cancellation after an earlier abort request.
        @Override
        public void close() throws IOException {
            if (aborted) {
                forceClose();
            } else {
                filtered.close();
            }
        }

        /// Cancels the input before waiting for a concurrent graceful filter close.
        @Override
        public void forceClose() throws IOException {
            aborted = true;
            ForceCloseable.forceClose(input);
            filtered.close();
        }

        /// Rejects new input operations once cancellation has started, even if source cleanup failed.
        private void requireNotAborted() throws IOException {
            if (aborted) {
                throw new IOException("7z filter input is closed");
            }
        }
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
