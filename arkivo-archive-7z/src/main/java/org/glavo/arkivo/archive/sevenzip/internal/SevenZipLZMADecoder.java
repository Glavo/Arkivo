// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.codec.bzip2.internal.BZip2InputStream;
import org.glavo.arkivo.codec.deflate64.internal.Deflate64InputStream;
import org.glavo.arkivo.codec.bcj.BCJTransforms;
import org.glavo.arkivo.codec.transform.TransformingInputStream;
import org.glavo.arkivo.codec.delta.DeltaTransform;
import org.glavo.arkivo.codec.lzma.internal.Lzma2InputStream;
import org.glavo.arkivo.codec.lzma.internal.LzmaInputStream;
import org.glavo.arkivo.codec.lzma.internal.LzmaProperties;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/// Opens supported coder streams stored in 7z folders.
@NotNullByDefault
final class SevenZipLZMADecoder {
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

    /// Returns whether the method ID identifies a supported BCJ filter.
    static boolean isBcjFilter(byte[] methodId) {
        return isX86Filter(methodId)
                || isPowerPCFilter(methodId)
                || isIA64Filter(methodId)
                || isARMFilter(methodId)
                || isARMThumbFilter(methodId)
                || isSPARCFilter(methodId);
    }

    /// Returns whether the method ID identifies a supported decoder.
    static boolean isSupported(byte[] methodId) {
        return isCopy(methodId)
                || isLZMA(methodId)
                || isLZMA2(methodId)
                || isDeflate(methodId)
                || isDeflate64(methodId)
                || isBZip2(methodId)
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

    /// Opens the decoder graph for a 7z folder with all physical packed inputs.
    static InputStream openFolder(
            List<? extends InputStream> packedInputs,
            SevenZipFolderMethod method,
            long finalOutputLimit,
            @Nullable ArkivoPasswordProvider passwordProvider
    ) throws IOException {
        Objects.requireNonNull(packedInputs, "packedInputs");
        Objects.requireNonNull(method, "method");
        if (finalOutputLimit < 0) {
            throw new IllegalArgumentException("finalOutputLimit must be non-negative");
        }
        if (packedInputs.size() != method.packedStreamCount()) {
            throw new IOException("7z packed input count does not match the folder graph");
        }
        ArrayList<InputStream> inputs = new ArrayList<>(packedInputs.size());
        for (InputStream input : packedInputs) {
            inputs.add(Objects.requireNonNull(input, "packedInputs element"));
        }

        GraphBuilder builder = new GraphBuilder(inputs, method, passwordProvider);
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
            @Nullable ArkivoPasswordProvider passwordProvider
    ) throws IOException {
        if (isCopy(methodId)) {
            return input;
        }
        if (isLZMA(methodId)) {
            return openLZMA(input, outputLimit, properties);
        }
        if (isLZMA2(methodId)) {
            return openLZMA2(input, properties);
        }
        if (isDeflate(methodId)) {
            return openDeflate(input, properties);
        }
        if (isDeflate64(methodId)) {
            return openDeflate64(input, properties);
        }
        if (isBZip2(methodId)) {
            return openBZip2(input, properties);
        }
        if (isAes(methodId)) {
            return openAes(input, properties, passwordProvider);
        }
        if (isDelta(methodId)) {
            return openDelta(input, properties);
        }
        if (isBcjFilter(methodId)) {
            return openBcjFilter(input, methodId, properties);
        }
        throw new UnsupportedOperationException("Unsupported 7z coder method: " + Arrays.toString(methodId));
    }

    /// Builds decoder streams by resolving coder graph dependencies from the final output.
    @NotNullByDefault
    private static final class GraphBuilder {
        /// The physical packed input streams in folder order.
        private final List<InputStream> packedInputs;

        /// The folder coder graph.
        private final SevenZipFolderMethod method;

        /// The password provider used by AES coder nodes.
        private final @Nullable ArkivoPasswordProvider passwordProvider;

        /// The currently unowned stream roots that must be closed after setup failure.
        private final Set<InputStream> roots = Collections.newSetFromMap(new IdentityHashMap<>());

        /// Creates a graph builder over physical packed inputs.
        private GraphBuilder(
                List<InputStream> packedInputs,
                SevenZipFolderMethod method,
                @Nullable ArkivoPasswordProvider passwordProvider
        ) {
            this.packedInputs = List.copyOf(packedInputs);
            this.method = method;
            this.passwordProvider = passwordProvider;
            roots.addAll(packedInputs);
        }

        /// Opens the sole unbound folder output stream.
        private InputStream openFinalOutput(long outputLimit) throws IOException {
            return openOutput(method.finalOutputStreamIndex(), outputLimit);
        }

        /// Opens one folder output and recursively resolves all coder inputs.
        private InputStream openOutput(int outputStreamIndex, long outputLimit) throws IOException {
            int coderIndex = method.coderIndexForOutput(outputStreamIndex);
            if (method.outputStreamCount(coderIndex) != 1
                    || method.firstOutputStreamIndex(coderIndex) != outputStreamIndex) {
                throw new UnsupportedOperationException("Unsupported 7z multi-output coder");
            }

            int inputCount = method.inputStreamCount(coderIndex);
            int firstInput = method.firstInputStreamIndex(coderIndex);
            ArrayList<InputStream> coderInputs = new ArrayList<>(inputCount);
            for (int index = 0; index < inputCount; index++) {
                int inputStreamIndex = firstInput + index;
                int boundOutputStreamIndex = method.boundOutputStreamIndex(inputStreamIndex);
                if (boundOutputStreamIndex >= 0) {
                    coderInputs.add(openOutput(
                            boundOutputStreamIndex,
                            method.unpackSize(boundOutputStreamIndex)
                    ));
                } else {
                    int packedStreamOrdinal = method.packedStreamOrdinal(inputStreamIndex);
                    if (packedStreamOrdinal < 0) {
                        throw new IOException("7z folder input stream has no source");
                    }
                    coderInputs.add(packedInputs.get(packedStreamOrdinal));
                }
            }

            byte[] methodId = method.methodId(coderIndex);
            byte[] properties = method.properties(coderIndex);
            InputStream output;
            if (isBcj2Filter(methodId)) {
                if (inputCount != 4) {
                    throw new IOException("7z BCJ2 coder must consume four input streams");
                }
                if (properties.length != 0) {
                    throw new IOException("7z BCJ2 coder properties must be empty");
                }
                output = new SevenZipBcj2InputStream(
                        coderInputs.get(0),
                        coderInputs.get(1),
                        coderInputs.get(2),
                        coderInputs.get(3),
                        outputLimit
                );
            } else {
                if (inputCount != 1) {
                    throw new UnsupportedOperationException("Unsupported 7z multi-input coder");
                }
                output = openSingleInputCoder(
                        coderInputs.get(0),
                        methodId,
                        properties,
                        outputLimit,
                        passwordProvider
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
            @Nullable ArkivoPasswordProvider passwordProvider
    ) throws IOException {
        return SevenZipAesCrypto.openDecryptingStream(input, properties, passwordProvider);
    }

    /// Opens a raw LZMA decoder for 7z coder properties.
    static InputStream openLZMA(InputStream input, long uncompressedSize, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(properties, "properties");
        if (properties.length != 5) {
            throw new IOException("7z LZMA coder properties must contain five bytes");
        }
        int dictionarySize = ByteBuffer.wrap(properties, 1, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        return new LzmaInputStream(input, uncompressedSize, Byte.toUnsignedInt(properties[0]), dictionarySize);
    }

    /// Opens a raw LZMA2 decoder for 7z coder properties.
    static InputStream openLZMA2(InputStream input, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(properties, "properties");
        if (properties.length != 1) {
            throw new IOException("7z LZMA2 coder properties must contain one byte");
        }
        int property = Byte.toUnsignedInt(properties[0]);
        if (property > 37) {
            throw new IOException("Unsupported 7z LZMA2 dictionary property");
        }
        int dictionarySize = (2 | (property & 1)) << ((property >>> 1) + 11);
        if (dictionarySize > LzmaProperties.MAXIMUM_DICTIONARY_SIZE) {
            throw new IOException("Unsupported 7z LZMA2 dictionary size: " + dictionarySize);
        }
        return new Lzma2InputStream(input, dictionarySize);
    }

    /// Opens a raw Deflate decoder for 7z coder properties.
    static InputStream openDeflate(InputStream input, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(properties, "properties");
        if (properties.length != 0) {
            throw new IOException("7z Deflate coder properties must be empty");
        }
        return new EndingInflaterInputStream(input);
    }

    /// Opens a Deflate64 decoder for 7z coder properties.
    static InputStream openDeflate64(InputStream input, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(properties, "properties");
        if (properties.length != 0) {
            throw new IOException("7z Deflate64 coder properties must be empty");
        }
        return new Deflate64InputStream(input);
    }

    /// Opens a BZip2 decoder for 7z coder properties.
    static InputStream openBZip2(InputStream input, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(properties, "properties");
        if (properties.length != 0) {
            throw new IOException("7z BZip2 coder properties must be empty");
        }
        return new BZip2InputStream(input);
    }

    /// Opens a raw Delta filter decoder for 7z coder properties.
    static InputStream openDelta(InputStream input, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(properties, "properties");
        if (properties.length != 1) {
            throw new IOException("7z Delta coder properties must contain one byte");
        }
        int distance = Byte.toUnsignedInt(properties[0]) + 1;
        return new TransformingInputStream(input, new DeltaTransform(false, distance));
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
        throw new UnsupportedOperationException("Unsupported 7z coder method: " + Arrays.toString(methodId));
    }

    /// Opens a raw x86 BCJ filter decoder for 7z coder properties.
    static InputStream openX86Filter(InputStream input, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        int startOffset = bcjStartOffset(properties, "7z x86 BCJ coder");
        return new TransformingInputStream(input, BCJTransforms.x86(false, startOffset));
    }

    /// Opens a raw PowerPC BCJ filter decoder for 7z coder properties.
    static InputStream openPowerPCFilter(InputStream input, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        int startOffset = bcjStartOffset(properties, "7z PowerPC BCJ coder");
        return new TransformingInputStream(input, BCJTransforms.powerPc(false, startOffset));
    }

    /// Opens a raw IA-64 BCJ filter decoder for 7z coder properties.
    static InputStream openIA64Filter(InputStream input, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        int startOffset = bcjStartOffset(properties, "7z IA-64 BCJ coder");
        return new TransformingInputStream(input, BCJTransforms.ia64(false, startOffset));
    }

    /// Opens a raw ARM BCJ filter decoder for 7z coder properties.
    static InputStream openARMFilter(InputStream input, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        int startOffset = bcjStartOffset(properties, "7z ARM BCJ coder");
        return new TransformingInputStream(input, BCJTransforms.arm(false, startOffset));
    }

    /// Opens a raw ARM-Thumb BCJ filter decoder for 7z coder properties.
    static InputStream openARMThumbFilter(InputStream input, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        int startOffset = bcjStartOffset(properties, "7z ARM-Thumb BCJ coder");
        return new TransformingInputStream(input, BCJTransforms.armThumb(false, startOffset));
    }

    /// Opens a raw SPARC BCJ filter decoder for 7z coder properties.
    static InputStream openSPARCFilter(InputStream input, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        int startOffset = bcjStartOffset(properties, "7z SPARC BCJ coder");
        return new TransformingInputStream(input, BCJTransforms.sparc(false, startOffset));
    }

    /// Reads BCJ filter properties as an optional little-endian start offset.
    private static int bcjStartOffset(byte[] properties, String description) throws IOException {
        Objects.requireNonNull(properties, "properties");
        if (properties.length == 0) {
            return 0;
        }
        if (properties.length != 4) {
            throw new IOException(description + " properties must contain zero or four bytes");
        }
        return ByteBuffer.wrap(properties).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    /// Inflates raw Deflate input and releases the backing inflater.
    @NotNullByDefault
    private static final class EndingInflaterInputStream extends InflaterInputStream {
        /// The inflater owned by this stream.
        private final Inflater inflater;

        /// Whether the inflater has been released.
        private boolean released;

        /// Creates a raw Deflate input stream.
        private EndingInflaterInputStream(InputStream input) {
            this(input, new Inflater(true));
        }

        /// Creates a raw Deflate input stream with the given inflater.
        private EndingInflaterInputStream(InputStream input, Inflater inflater) {
            super(input, inflater);
            this.inflater = inflater;
        }

        /// Closes the stream and releases the inflater.
        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                if (!released) {
                    released = true;
                    inflater.end();
                }
            }
        }
    }
}
