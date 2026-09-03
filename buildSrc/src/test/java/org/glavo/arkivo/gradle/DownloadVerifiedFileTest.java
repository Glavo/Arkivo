// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.gradle;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the local integrity and safety boundaries of [DownloadVerifiedFile].
@NotNullByDefault
final class DownloadVerifiedFileTest {
    /// The exact marker content accepted by the download task.
    private static final String CACHE_MARKER_CONTENT = "Arkivo test data cache v1\n";

    /// Provides an isolated project and cache directory for each test.
    @TempDir
    Path temporaryDirectory;

    /// Verifies that a valid cached object is reusable without accessing the network.
    @Test
    void reusesValidCachedObjectOffline() throws IOException {
        byte[] content = {1, 2, 3, 4, 5};
        DownloadVerifiedFile task = newTask(content, true);
        Path target = destination(task);
        writeMarker(task);
        Files.createDirectories(target.getParent());
        Files.write(target, content);

        task.download();

        assertArrayEquals(content, Files.readAllBytes(target));
    }

    /// Verifies that an empty object is a valid content-addressed cache entry.
    @Test
    void reusesEmptyCachedObject() throws IOException {
        byte[] content = {};
        DownloadVerifiedFile task = newTask(content, true);
        Path target = destination(task);
        writeMarker(task);
        Files.createDirectories(target.getParent());
        Files.write(target, content);

        task.download();

        assertEquals(0L, Files.size(target));
    }

    /// Verifies that invalid digest metadata is rejected before the cache is initialized.
    @Test
    void rejectsInvalidDigestMetadata() {
        DownloadVerifiedFile task = newTask(new byte[]{1}, true);
        task.getExpectedSha256().set("not-a-sha256");

        GradleException exception = assertThrows(GradleException.class, task::download);

        assertTrue(exception.getMessage().contains("64 hexadecimal characters"));
        assertFalse(Files.exists(marker(task)));
    }

    /// Verifies that unsupported expected sizes are rejected before the cache is initialized.
    @Test
    void rejectsInvalidExpectedSizes() {
        DownloadVerifiedFile negative = newTask(new byte[]{1}, true);
        negative.getExpectedSize().set(-1L);
        DownloadVerifiedFile maximum = newTask("maximum", new byte[]{1}, true);
        maximum.getExpectedSize().set(Long.MAX_VALUE);

        GradleException negativeFailure = assertThrows(GradleException.class, negative::download);
        GradleException maximumFailure = assertThrows(GradleException.class, maximum::download);

        assertTrue(negativeFailure.getMessage().contains("between 0 and Long.MAX_VALUE - 1"));
        assertTrue(maximumFailure.getMessage().contains("between 0 and Long.MAX_VALUE - 1"));
        assertFalse(Files.exists(marker(negative)));
    }

    /// Verifies that a destination outside the content-addressed cache is rejected.
    @Test
    void rejectsDestinationOutsideCache() {
        DownloadVerifiedFile task = newTask(new byte[]{1}, true);
        task.getDestination().set(temporaryDirectory.resolve("outside.bin").toFile());

        GradleException exception = assertThrows(GradleException.class, task::download);

        assertTrue(exception.getMessage().contains("outside the content-addressed cache"));
        assertFalse(Files.exists(marker(task)));
    }

    /// Verifies that the destination directory must identify the declared digest.
    @Test
    void rejectsDestinationWithDifferentDigest() {
        DownloadVerifiedFile task = newTask(new byte[]{1}, true);
        Path root = cacheRoot(task);
        Path target = root.resolve("downloads/sha256/"
                + "0".repeat(64)
                + "/payload.bin");
        task.getDestination().set(target.toFile());

        GradleException exception = assertThrows(GradleException.class, task::download);

        assertTrue(exception.getMessage().contains("does not match its declared SHA-256"));
        assertFalse(Files.exists(marker(task)));
    }

    /// Verifies that an unmarked cache containing unrelated data is never adopted.
    @Test
    void rejectsUnmarkedNonEmptyCache() throws IOException {
        DownloadVerifiedFile task = newTask(new byte[]{1}, true);
        Path root = cacheRoot(task);
        Files.createDirectories(root);
        Files.writeString(root.resolve("unrelated.txt"), "keep");

        GradleException exception = assertThrows(GradleException.class, task::download);

        assertTrue(exception.getMessage().contains("non-empty test data cache without a marker"));
        assertFalse(Files.exists(marker(task)));
        assertEquals("keep", Files.readString(root.resolve("unrelated.txt")));
    }

    /// Verifies that only the exact marker content authorizes an existing cache.
    @Test
    void rejectsInvalidCacheMarker() throws IOException {
        DownloadVerifiedFile task = newTask(new byte[]{1}, true);
        Path marker = marker(task);
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "not an Arkivo cache\n");

        GradleException exception = assertThrows(GradleException.class, task::download);

        assertTrue(exception.getMessage().contains("cache marker is invalid"));
        assertEquals("not an Arkivo cache\n", Files.readString(marker));
    }

    /// Verifies that Gradle-prepared content-addressed directories may receive a new marker.
    @Test
    void initializesPreparedCacheBeforeReportingOfflineMiss() throws IOException {
        DownloadVerifiedFile task = newTask(new byte[]{1}, true);
        Files.createDirectories(destination(task).getParent());

        GradleException exception = assertThrows(GradleException.class, task::download);

        assertTrue(exception.getMessage().contains("Gradle is running offline"));
        assertEquals(CACHE_MARKER_CONTENT, Files.readString(marker(task)));
    }

    /// Verifies that a corrupt cached object is retained and rejected while offline.
    @Test
    void rejectsCorruptCachedObjectOffline() throws IOException {
        byte[] content = {1, 2, 3, 4};
        DownloadVerifiedFile task = newTask(content, true);
        Path target = destination(task);
        byte[] corrupt = {4, 3, 2, 1};
        writeMarker(task);
        Files.createDirectories(target.getParent());
        Files.write(target, corrupt);

        GradleException exception = assertThrows(GradleException.class, task::download);

        assertTrue(exception.getMessage().contains("Gradle is running offline"));
        assertArrayEquals(corrupt, Files.readAllBytes(target));
    }

    /// Verifies that non-HTTPS sources are rejected before any network request is made.
    @Test
    void rejectsNonHttpsSource() {
        DownloadVerifiedFile task = newTask(new byte[]{1}, false);
        task.getSourceUrl().set("http://example.invalid/payload.bin");

        GradleException exception = assertThrows(GradleException.class, task::download);

        assertTrue(exception.getMessage().contains("downloads require HTTPS"));
    }

    /// Verifies that an exact response is copied completely.
    @Test
    void copiesExactResponse() throws IOException {
        byte[] content = {1, 2, 3, 4};
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        DownloadVerifiedFile.copyExpectedResponse(
                new ByteArrayInputStream(content),
                output,
                content.length,
                URI.create("https://example.invalid/payload.bin")
        );

        assertArrayEquals(content, output.toByteArray());
    }

    /// Verifies that an oversized response is detected after consuming only one excess byte.
    @Test
    void boundsOversizedResponseConsumption() {
        byte[] content = {1, 2, 3, 4, 5, 6, 7};
        ByteArrayInputStream input = new ByteArrayInputStream(content);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        GradleException exception = assertThrows(GradleException.class, () ->
                DownloadVerifiedFile.copyExpectedResponse(
                        input,
                        output,
                        3L,
                        URI.create("https://example.invalid/payload.bin")
                )
        );

        assertTrue(exception.getMessage().contains("exceeds its declared size"));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, output.toByteArray());
        assertEquals(3, input.available());
    }

    /// Creates a configured download task in a new temporary Gradle project.
    private DownloadVerifiedFile newTask(byte[] content, boolean offline) {
        return newTask("download", content, offline);
    }

    /// Creates a named download task in a new temporary Gradle project.
    private DownloadVerifiedFile newTask(String name, byte[] content, boolean offline) {
        Path projectDirectory = temporaryDirectory.resolve(name + "-project");
        Project project = ProjectBuilder.builder()
                .withProjectDir(projectDirectory.toFile())
                .build();
        DownloadVerifiedFile task = project.getTasks().register(name, DownloadVerifiedFile.class).get();
        String sha256 = sha256(content);
        Path root = projectDirectory.resolve("cache");
        task.getSourceUrl().set("https://example.invalid/payload.bin");
        task.getExpectedSha256().set(sha256);
        task.getExpectedSize().set((long) content.length);
        task.getOffline().set(offline);
        task.getCacheRoot().set(root.toFile());
        task.getCacheMarker().set(root.resolve(".arkivo-test-data-cache").toFile());
        task.getDestination().set(root.resolve("downloads/sha256/")
                .resolve(sha256)
                .resolve("payload.bin")
                .toFile());
        return task;
    }

    /// Writes the exact cache marker expected by a task.
    private static void writeMarker(DownloadVerifiedFile task) throws IOException {
        Path marker = marker(task);
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, CACHE_MARKER_CONTENT);
    }

    /// Returns the configured cache root of a task.
    private static Path cacheRoot(DownloadVerifiedFile task) {
        return task.getCacheRoot().get().getAsFile().toPath();
    }

    /// Returns the configured cache marker of a task.
    private static Path marker(DownloadVerifiedFile task) {
        return task.getCacheMarker().get().getAsFile().toPath();
    }

    /// Returns the configured destination of a task.
    private static Path destination(DownloadVerifiedFile task) {
        return task.getDestination().get().getAsFile().toPath();
    }

    /// Returns the lowercase SHA-256 of the supplied bytes.
    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is unavailable", exception);
        }
    }
}
