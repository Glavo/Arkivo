// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.all;

import org.glavo.arkivo.codec.CompressionDictionary;
import org.glavo.arkivo.codec.RawCompressionDictionary;
import org.glavo.arkivo.codec.deflate.ZlibDictionary;
import org.glavo.arkivo.codec.lz4.LZ4Dictionary;
import org.glavo.arkivo.codec.zstd.ZstdDictionary;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnmodifiableView;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies copying and view isolation for byte-backed dictionaries without imposing a common representation API.
@NotNullByDefault
final class DictionaryContentContractTest {
    /// Supplies all array/buffer factory pairs with their format-specific accessors.
    private static Stream<DictionaryCase<?>> dictionaries() {
        return Stream.of(
                new DictionaryCase<>("raw", RawCompressionDictionary::of, RawCompressionDictionary::of,
                        RawCompressionDictionary::bytes, RawCompressionDictionary::buffer, RawCompressionDictionary::size),
                new DictionaryCase<>("zlib", ZlibDictionary::of, ZlibDictionary::of,
                        ZlibDictionary::bytes, ZlibDictionary::buffer, ZlibDictionary::size),
                new DictionaryCase<>("lz4-raw", LZ4Dictionary::rawContent, LZ4Dictionary::rawContent,
                        LZ4Dictionary::bytes, LZ4Dictionary::buffer, LZ4Dictionary::size),
                new DictionaryCase<>("lz4-identified", bytes -> LZ4Dictionary.identified(0xffff_ffffL, bytes),
                        buffer -> LZ4Dictionary.identified(0xffff_ffffL, buffer),
                        LZ4Dictionary::bytes, LZ4Dictionary::buffer, LZ4Dictionary::size),
                new DictionaryCase<>("lz4-content-id", LZ4Dictionary::identifiedByContent,
                        LZ4Dictionary::identifiedByContent,
                        LZ4Dictionary::bytes, LZ4Dictionary::buffer, LZ4Dictionary::size),
                new DictionaryCase<>("zstd-auto", ZstdDictionary::of, ZstdDictionary::of,
                        ZstdDictionary::bytes, ZstdDictionary::buffer, ZstdDictionary::size),
                new DictionaryCase<>("zstd-raw", ZstdDictionary::rawContent, ZstdDictionary::rawContent,
                        ZstdDictionary::bytes, ZstdDictionary::buffer, ZstdDictionary::size),
                new DictionaryCase<>("zstd-full", ZstdDictionary::fullDictionary, ZstdDictionary::fullDictionary,
                        ZstdDictionary::bytes, ZstdDictionary::buffer, ZstdDictionary::size));
    }

    /// Covers empty dictionaries where supported, plus minimum and longer formatted dictionary representations.
    private static Stream<Arguments> contents() {
        return dictionaries().flatMap(test -> sizes(test)
                .map(size -> Arguments.of(test, size)));
    }

    /// Omits empty content only for Zstandard, whose factories require at least eight bytes.
    private static Stream<Integer> sizes(DictionaryCase<?> test) {
        return Stream.of(0, 8, 256, 32768).filter(size -> size != 0 || !test.name().startsWith("zstd-"));
    }

    /// Exercises sliced heap and direct buffers, with and without write access.
    private static Stream<Arguments> buffers() {
        return dictionaries().flatMap(test -> sizes(test).flatMap(size -> Stream.of(false, true)
                .flatMap(direct -> Stream.of(false, true)
                        .map(readOnly -> Arguments.of(test, size, direct, readOnly)))));
    }

    /// Array factories detach content from the caller, and returned arrays cannot mutate the dictionary.
    @ParameterizedTest(name = "{0}, size={1}")
    @MethodSource("contents")
    <D extends CompressionDictionary> void copiesArrays(DictionaryCase<D> test, int size) {
        byte[] expected = content(size);
        byte[] source = expected.clone();
        D dictionary = test.fromArray().apply(source);
        if (size > 0) source[0] ^= 1;
        assertContent(test, dictionary, expected);
    }

    /// Buffer factories copy only remaining bytes, preserve the mark and state, and detach from writable backing storage.
    @ParameterizedTest(name = "{0}, size={1}, direct={2}, readOnly={3}")
    @MethodSource("buffers")
    <D extends CompressionDictionary> void copiesBufferWindows(
            DictionaryCase<D> test, int size, boolean direct, boolean readOnly) {
        byte[] expected = content(size);
        ByteBuffer storage = direct ? ByteBuffer.allocateDirect(size + 13) : ByteBuffer.allocate(size + 13);
        storage.position(7).put(expected);
        ByteBuffer source = storage.slice(4, size + 7);
        if (readOnly) source = source.asReadOnlyBuffer();
        source.order(ByteOrder.LITTLE_ENDIAN).position(1).mark().position(3).limit(size + 3);

        D dictionary = test.fromBuffer().apply(source);
        assertEquals(3, source.position());
        assertEquals(size + 3, source.limit());
        assertEquals(ByteOrder.LITTLE_ENDIAN, source.order());
        source.reset();
        assertEquals(1, source.position());
        if (size > 0) storage.put(7, (byte) (storage.get(7) ^ 1));
        assertContent(test, dictionary, expected);
    }

    /// Both factory overloads reject absent content.
    @ParameterizedTest(name = "{0}")
    @MethodSource("dictionaries")
    void rejectsNullContent(DictionaryCase<?> test) {
        assertThrows(NullPointerException.class, () -> test.fromArray().apply(null));
        assertThrows(NullPointerException.class, () -> test.fromBuffer().apply(null));
    }

    /// Checks copied bytes and independent, complete, read-only views after caller mutation.
    private static <D extends CompressionDictionary> void assertContent(
            DictionaryCase<D> test, D dictionary, byte[] expected) {
        assertEquals(expected.length, test.size().applyAsInt(dictionary));
        byte[] copy = test.bytes().apply(dictionary);
        assertArrayEquals(expected, copy);
        if (copy.length > 0) copy[copy.length - 1] ^= 1;
        assertArrayEquals(expected, test.bytes().apply(dictionary));

        @UnmodifiableView ByteBuffer first = test.buffer().apply(dictionary);
        @UnmodifiableView ByteBuffer second = test.buffer().apply(dictionary);
        assertNotSame(first, second);
        for (ByteBuffer view : new ByteBuffer[]{first, second}) {
            assertTrue(view.isReadOnly());
            assertEquals(0, view.position());
            assertEquals(expected.length, view.limit());
            byte[] actual = new byte[view.remaining()];
            view.duplicate().get(actual);
            assertArrayEquals(expected, actual);
            assertThrows(ReadOnlyBufferException.class, () -> view.put((byte) 1));
        }
        ByteOrder secondOrder = second.order();
        first.position(first.limit()).limit(0)
                .order(secondOrder == ByteOrder.BIG_ENDIAN ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        assertEquals(0, second.position());
        assertEquals(expected.length, second.limit());
        assertEquals(secondOrder, second.order());
        assertArrayEquals(expected, test.bytes().apply(dictionary));
    }

    /// Produces bytes with a valid Zstandard dictionary prefix; entropy-table validity is outside this storage test.
    private static byte[] content(int size) {
        byte[] bytes = new byte[size];
        for (int index = 0; index < size; index++) bytes[index] = (byte) (index * 31 + 7);
        if (size >= 8) {
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(0xec30_a437).putInt(0x1234_5678);
        }
        return bytes;
    }

    /// Adapts concrete dictionary APIs only inside this test; no byte access is required by the marker interface.
    ///
    /// @param name factory label
    /// @param fromArray copying array factory
    /// @param fromBuffer copying buffer factory
    /// @param bytes mutable-copy accessor
    /// @param buffer independent read-only-view accessor
    /// @param size effective content size accessor
    /// @param <D> concrete dictionary type
    @NotNullByDefault
    private record DictionaryCase<D extends CompressionDictionary>(
            String name, Function<byte[], D> fromArray, Function<ByteBuffer, D> fromBuffer,
            Function<D, byte[]> bytes, Function<D, ByteBuffer> buffer, ToIntFunction<D> size) {
        @Override
        public String toString() {
            return name;
        }
    }
}
