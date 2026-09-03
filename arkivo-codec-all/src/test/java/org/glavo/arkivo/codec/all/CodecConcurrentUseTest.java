// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.all;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.CompressionFormats;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies immutable codec configurations create independent operations when shared by concurrent callers.
@NotNullByDefault
final class CodecConcurrentUseTest {
    /// Number of simultaneous callers used for every codec.
    private static final int CALLER_COUNT = 4;

    /// Number of round trips performed by each simultaneous caller.
    private static final int ROUND_TRIPS_PER_CALLER = 2;

    /// Maximum time allowed for one caller or executor shutdown.
    private static final long TIMEOUT_SECONDS = 30L;

    /// Common fixed-size payload from which each caller derives distinct content.
    private static final byte @Unmodifiable [] CONTENT = (
            "shared immutable codec concurrent round trip 0123456789abcdef;".repeat(23)
    ).getBytes(StandardCharsets.UTF_8);

    /// Shares every installed codec across simultaneous allocating buffer round trips.
    @Test
    void concurrentlyReusesEveryInstalledCodec() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(CALLER_COUNT);
        try {
            for (CompressionFormat format : CompressionFormats.installed()) {
                CompressionCodec<?> codec = CodecContractConfigurations.decoderCodec(
                        format.defaultCodec(),
                        CONTENT.length
                ).withMaximumOutputSize(CONTENT.length);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Void>> callers = new ArrayList<>(CALLER_COUNT);
                for (int caller = 0; caller < CALLER_COUNT; caller++) {
                    int callerIndex = caller;
                    callers.add(executor.submit(() -> {
                        start.await();
                        for (int iteration = 0; iteration < ROUND_TRIPS_PER_CALLER; iteration++) {
                            roundTrip(codec, format.name(), callerIndex, iteration);
                        }
                        return null;
                    }));
                }

                start.countDown();
                for (Future<Void> caller : callers) {
                    await(caller, format.name());
                }
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }
    }

    /// Completes one independent round trip with caller-specific heap or direct input.
    private static void roundTrip(
            CompressionCodec<?> codec,
            String context,
            int callerIndex,
            int iteration
    ) throws Exception {
        byte[] expected = CONTENT.clone();
        int mutationIndex = Math.floorMod(callerIndex * 31 + iteration * 17, expected.length);
        expected[mutationIndex] ^= (byte) (1 + callerIndex * 7 + iteration);

        ByteBuffer source;
        if (((callerIndex + iteration) & 1) == 0) {
            source = ByteBuffer.wrap(expected);
        } else {
            source = ByteBuffer.allocateDirect(expected.length);
            source.put(expected).flip();
        }

        ByteBuffer encoded = codec.compress(source);
        assertEquals(source.limit(), source.position(), context);
        ByteBuffer decoded = codec.decompress(encoded);
        assertEquals(encoded.limit(), encoded.position(), context);
        assertArrayEquals(expected, remainingBytes(decoded), context);
    }

    /// Returns the remaining bytes of a buffer without retaining it.
    private static byte @Unmodifiable [] remainingBytes(ByteBuffer buffer) {
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }

    /// Waits for one concurrent caller and rethrows its original checked or unchecked failure.
    private static void await(Future<Void> caller, String context) throws Exception {
        try {
            caller.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError("Unexpected concurrent codec failure: " + context, cause);
        } catch (TimeoutException exception) {
            throw new AssertionError("Concurrent codec operation timed out: " + context, exception);
        }
    }
}
