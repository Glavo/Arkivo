// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

/// Checks model loading and candidate availability in a JVM without the optional JDK charset module.
@NotNullByDefault
public final class ZipMetadataCharsetProbe {
    /// Creates no instances.
    private ZipMetadataCharsetProbe() {
    }

    /// Verifies reduced-runtime decoding and an ASCII call whose class loader cannot supply model weights.
    public static void main(String[] arguments) throws Exception {
        if (Charset.isSupported("x-MacRoman")) throw new AssertionError("Optional charsets are still available");
        float[] biases = new float[ZipMetadataModel.ENCODINGS.size()];
        Arrays.fill(biases, -100);
        biases[ZipMetadataModel.ENCODINGS.indexOf("x-MacRoman")] = 100;
        biases[0] = 0;
        var model = new ZipMetadataModel(new short[biases.length * ZipMetadataModel.FEATURES], biases,
                new ZipMetadataModel.Parameters(0, 0, 0));
        String expected = "é";
        if (!model.decode(ByteBuffer.wrap(expected.getBytes(StandardCharsets.UTF_8)), null).text().equals(expected)) {
            throw new AssertionError("Unavailable candidate was not skipped");
        }
        ZipMetadataModel.bundled();
        String[] paths = System.getProperty("java.class.path").split(java.io.File.pathSeparator);
        URL[] urls = new URL[paths.length];
        for (int index = 0; index < paths.length; index++) urls[index] = Path.of(paths[index]).toUri().toURL();
        try (var loader = new WithoutModel(urls)) {
            Class<?> type = loader.loadClass(ZipLearnedMetadataDecoder.class.getName());
            Object decoder = type.getConstructor().newInstance();
            Object text = type.getMethod("decode", ByteBuffer.class).invoke(decoder,
                    ByteBuffer.wrap("file001.txt".getBytes(StandardCharsets.US_ASCII)));
            if (!text.equals("file001.txt") || loader.modelRequested) throw new AssertionError("ASCII loaded model data");
        }
    }

    /// Isolates application classes and records any attempt to obtain the missing model resource.
    @NotNullByDefault
    private static final class WithoutModel extends URLClassLoader {
        /// Whether non-ASCII model initialization was attempted.
        private boolean modelRequested;

        /// Loads only platform classes through the parent to prevent reuse of an already initialized model.
        private WithoutModel(URL[] urls) {
            super(urls, ClassLoader.getPlatformClassLoader());
        }

        @Override
        public @Nullable InputStream getResourceAsStream(String name) {
            if (name.endsWith("metadata-model.bin")) {
                modelRequested = true;
                return null;
            }
            return super.getResourceAsStream(name);
        }
    }
}
