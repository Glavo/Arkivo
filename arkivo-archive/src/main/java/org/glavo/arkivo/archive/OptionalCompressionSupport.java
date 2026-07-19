// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.glavo.arkivo.archive.internal.ArkivoStreamingSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Invokes the one official optional bridge between archive and compression modules.
@NotNullByDefault
final class OptionalCompressionSupport {
    /// The bridge class supplied only by the combined `arkivo-all` module.
    private static final String SUPPORT_CLASS_NAME =
            "org.glavo.arkivo.all.internal.CompressionStreamingSourceSupport";

    /// The cached bridge method, or `null` when the combined module is absent.
    private static final @Nullable Method PROBE_METHOD = findProbeMethod();

    /// Creates no instances.
    private OptionalCompressionSupport() {
    }

    /// Probes one source through the official compression bridge when it is installed.
    ///
    /// @param source  the channel whose ownership is transferred to the bridge
    /// @param options the archive-wide read limits and lifecycle options
    /// @return an owning bridge result, or `null` when `arkivo-all` is not installed
    /// @throws IOException if compression probing or decoder setup fails
    static @Nullable ArkivoStreamingSource probe(
            ReadableByteChannel source,
            ArchiveReadOptions options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        @Nullable Method method = PROBE_METHOD;
        if (method == null) {
            return null;
        }

        try {
            @Nullable Object result = method.invoke(null, source, options);
            if (!(result instanceof ArkivoStreamingSource streamingSource)) {
                throw new IllegalStateException("Official compression bridge returned an incompatible result");
            }
            return streamingSource;
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Official compression bridge is not accessible", exception);
        } catch (InvocationTargetException exception) {
            @Nullable Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException("Official compression bridge failed", cause == null ? exception : cause);
        }
    }

    /// Resolves the official bridge method without accepting arbitrary providers.
    private static @Nullable Method findProbeMethod() {
        try {
            Class<?> supportClass = Class.forName(
                    SUPPORT_CLASS_NAME,
                    false,
                    OptionalCompressionSupport.class.getClassLoader()
            );
            Method method = supportClass.getDeclaredMethod(
                    "probe",
                    ReadableByteChannel.class,
                    ArchiveReadOptions.class
            );
            if (!Modifier.isStatic(method.getModifiers())
                    || method.getReturnType() != ArkivoStreamingSource.class) {
                throw new IllegalStateException("Official compression bridge has an incompatible probe method");
            }
            if (!method.trySetAccessible()) {
                throw new IllegalStateException("Official compression bridge package is not open to Arkivo archive");
            }
            return method;
        } catch (ClassNotFoundException ignored) {
            return null;
        } catch (ReflectiveOperationException | LinkageError | SecurityException exception) {
            throw new IllegalStateException("Failed to load the official compression bridge", exception);
        }
    }
}
