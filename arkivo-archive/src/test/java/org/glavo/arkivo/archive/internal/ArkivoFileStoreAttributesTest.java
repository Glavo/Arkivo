// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileStoreAttributeView;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies shared archive file-store attribute name resolution and failure propagation.
@NotNullByDefault
final class ArkivoFileStoreAttributesTest {
    /// Verifies unqualified and basic-qualified names resolve every common file-store attribute.
    @Test
    void resolvesCommonAttributes() throws IOException {
        TrackingFileStore store = new TrackingFileStore();

        assertEquals("test archive", ArkivoFileStoreAttributes.get(store, "name"));
        assertEquals("test-format", ArkivoFileStoreAttributes.get(store, "basic:type"));
        assertEquals(true, ArkivoFileStoreAttributes.get(store, "readOnly"));
        assertEquals(100L, ArkivoFileStoreAttributes.get(store, "basic:totalSpace"));
        assertEquals(60L, ArkivoFileStoreAttributes.get(store, "usableSpace"));
        assertEquals(70L, ArkivoFileStoreAttributes.get(store, "basic:unallocatedSpace"));
    }

    /// Verifies unsupported views, empty names, and additional separators are rejected consistently.
    @Test
    void rejectsUnsupportedAttributeNames() {
        TrackingFileStore store = new TrackingFileStore();

        for (String attribute : new String[]{
                "posix:name",
                "basic:",
                "basic:unknown",
                "basic:name:extra",
                ":name",
                ""
        }) {
            UnsupportedOperationException exception = assertThrows(
                    UnsupportedOperationException.class,
                    () -> ArkivoFileStoreAttributes.get(store, attribute),
                    attribute
            );
            assertEquals("Unsupported file store attribute: " + attribute, exception.getMessage());
        }
    }

    /// Verifies checked failures from space queries remain the primary exception.
    @Test
    void propagatesSpaceQueryFailures() {
        TrackingFileStore store = new TrackingFileStore();
        store.failSpaceQueries = true;

        assertSame(store.spaceFailure, assertThrows(
                IOException.class,
                () -> ArkivoFileStoreAttributes.get(store, "totalSpace")
        ));
        assertSame(store.spaceFailure, assertThrows(
                IOException.class,
                () -> ArkivoFileStoreAttributes.get(store, "usableSpace")
        ));
        assertSame(store.spaceFailure, assertThrows(
                IOException.class,
                () -> ArkivoFileStoreAttributes.get(store, "unallocatedSpace")
        ));
    }

    /// Supplies deterministic file-store values and an injectable checked failure.
    @NotNullByDefault
    private static final class TrackingFileStore extends FileStore {
        /// Failure reported by configured space queries.
        private final IOException spaceFailure = new IOException("space failure");

        /// Whether space queries should fail.
        private boolean failSpaceQueries;

        /// Creates a deterministic test file store.
        private TrackingFileStore() {
        }

        /// Returns the test store name.
        @Override
        public String name() {
            return "test archive";
        }

        /// Returns the test store type.
        @Override
        public String type() {
            return "test-format";
        }

        /// Returns that the test store is read-only.
        @Override
        public boolean isReadOnly() {
            return true;
        }

        /// Returns the configured total space.
        @Override
        public long getTotalSpace() throws IOException {
            checkSpaceQuery();
            return 100L;
        }

        /// Returns the configured usable space.
        @Override
        public long getUsableSpace() throws IOException {
            checkSpaceQuery();
            return 60L;
        }

        /// Returns the configured unallocated space.
        @Override
        public long getUnallocatedSpace() throws IOException {
            checkSpaceQuery();
            return 70L;
        }

        /// Reports no supported file-attribute view classes.
        @Override
        public boolean supportsFileAttributeView(Class<? extends FileAttributeView> type) {
            return false;
        }

        /// Reports no supported file-attribute view names.
        @Override
        public boolean supportsFileAttributeView(String name) {
            return false;
        }

        /// Returns no file-store attribute view.
        @Override
        public <V extends FileStoreAttributeView> @Nullable V getFileStoreAttributeView(Class<V> type) {
            return null;
        }

        /// Rejects direct attribute queries, which are outside this helper's scope.
        @Override
        public Object getAttribute(String attribute) {
            throw new UnsupportedOperationException(attribute);
        }

        /// Reports the configured checked failure when requested.
        private void checkSpaceQuery() throws IOException {
            if (failSpaceQueries) {
                throw spaceFailure;
            }
        }
    }
}
