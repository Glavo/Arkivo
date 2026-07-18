// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/// Downloads one HTTPS resource into a verified content-addressed project cache.
@NotNullByDefault
@DisableCachingByDefault(because = "The verified file is already stored in a persistent content-addressed cache")
public abstract class DownloadVerifiedFile extends DefaultTask {
    /// The exact marker content authorizing explicit deletion of a cache directory.
    private static final String CACHE_MARKER_CONTENT = "Arkivo test data cache v1\n";

    /// Creates a verified download task and makes local file integrity part of its up-to-date predicate.
    public DownloadVerifiedFile() {
        getOutputs().upToDateWhen(task -> isUpToDate());
    }

    /// Returns the HTTPS source URL.
    @Input
    public abstract Property<String> getSourceUrl();

    /// Returns the expected lowercase or uppercase SHA-256.
    @Input
    public abstract Property<String> getExpectedSha256();

    /// Returns the expected byte size.
    @Input
    public abstract Property<Long> getExpectedSize();

    /// Returns whether this invocation must avoid network access.
    @Input
    public abstract Property<Boolean> getOffline();

    /// Returns the persistent project cache root.
    @Internal
    public abstract DirectoryProperty getCacheRoot();

    /// Returns the marker file authorizing cache deletion.
    @Internal
    public abstract RegularFileProperty getCacheMarker();

    /// Returns the final content-addressed file.
    @OutputFile
    public abstract RegularFileProperty getDestination();

    /// Reuses a verified object or downloads, verifies, and atomically publishes it.
    @TaskAction
    public final void download() throws IOException {
        validateInputs();
        Path root = getCacheRoot().get().getAsFile().toPath().toAbsolutePath().normalize();
        Path target = getDestination().get().getAsFile().toPath().toAbsolutePath().normalize();
        Path downloadRoot = root.resolve("downloads").resolve("sha256");
        if (!target.startsWith(downloadRoot)) {
            throw new GradleException("Test data destination is outside the content-addressed cache: " + target);
        }
        Path relativeTarget = downloadRoot.relativize(target);
        if (relativeTarget.getNameCount() != 2
                || !relativeTarget.getName(0).toString().equalsIgnoreCase(getExpectedSha256().get())) {
            throw new GradleException("Test data destination does not match its declared SHA-256: " + target);
        }
        ensureCacheMarker(root);
        Path parent = target.getParent();
        rejectSymbolicLinks(root, parent);
        if (verify(target, true)) {
            return;
        }
        if (getOffline().get()) {
            throw new GradleException(
                    "Verified test data is not cached and Gradle is running offline: " + target
            );
        }

        URI uri = URI.create(getSourceUrl().get());
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new GradleException("Test data downloads require HTTPS: " + uri);
        }

        Files.createDirectories(parent);
        rejectSymbolicLinks(root, parent);
        Path temporary = Files.createTempFile(parent, "." + target.getFileName() + ".", ".part");
        try {
            getLogger().lifecycle("Downloading test data from {}", uri);
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMinutes(5))
                    .header("User-Agent", "Arkivo-Gradle-Test-Data")
                    .GET()
                    .build();
            HttpResponse<InputStream> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new GradleException("Interrupted while downloading test data: " + uri, exception);
            }
            if (response.statusCode() != 200) {
                response.body().close();
                throw new GradleException(
                        "Test data download returned HTTP " + response.statusCode() + ": " + uri
                );
            }
            try (InputStream input = response.body();
                 OutputStream output = Files.newOutputStream(temporary)) {
                copyExpectedResponse(input, output, getExpectedSize().get(), uri);
            }
            if (!verify(temporary, true)) {
                throw new GradleException(
                        "Downloaded test data failed size or SHA-256 verification: " + uri
                );
            }

            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /// Validates integrity metadata before accessing the cache or network.
    private void validateInputs() {
        String sha256 = getExpectedSha256().get();
        if (!sha256.matches("[0-9a-fA-F]{64}")) {
            throw new GradleException("Expected SHA-256 must contain exactly 64 hexadecimal characters: " + sha256);
        }
        long size = getExpectedSize().get();
        if (size < 0L || size == Long.MAX_VALUE) {
            throw new GradleException("Expected test data size must be between 0 and Long.MAX_VALUE - 1: " + size);
        }
    }

    /// Returns whether every persistent output is present and valid.
    private boolean isUpToDate() {
        try {
            validateInputs();
            Path root = getCacheRoot().get().getAsFile().toPath().toAbsolutePath().normalize();
            Path target = getDestination().get().getAsFile().toPath().toAbsolutePath().normalize();
            Path marker = getCacheMarker().get().getAsFile().toPath().toAbsolutePath().normalize();
            Path downloadRoot = root.resolve("downloads").resolve("sha256");
            if (!root.equals(marker.getParent()) || !target.startsWith(downloadRoot)) {
                return false;
            }
            Path relativeTarget = downloadRoot.relativize(target);
            if (relativeTarget.getNameCount() != 2
                    || !relativeTarget.getName(0).toString().equalsIgnoreCase(getExpectedSha256().get())) {
                return false;
            }
            rejectSymbolicLinks(root, target.getParent());
            return hasValidCacheMarker()
                    && verify(target, false);
        } catch (IOException exception) {
            return false;
        }
    }

    /// Returns whether the configured cache marker has the exact expected content.
    private boolean hasValidCacheMarker() throws IOException {
        Path marker = getCacheMarker().get().getAsFile().toPath();
        return !Files.isSymbolicLink(marker)
                && Files.isRegularFile(marker)
                && CACHE_MARKER_CONTENT.equals(Files.readString(marker));
    }

    /// Initializes an empty or Gradle-prepared cache root, or validates the marker of an existing cache.
    private void ensureCacheMarker(Path root) throws IOException {
        Path marker = getCacheMarker().get().getAsFile().toPath().toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root)) {
            throw new GradleException("Refusing to use a symbolic-link test data cache directory: " + root);
        }
        if (!root.equals(marker.getParent())) {
            throw new GradleException("Test data cache marker is outside the cache root: " + marker);
        }
        if (Files.exists(marker)) {
            if (!hasValidCacheMarker()) {
                throw new GradleException("Test data cache marker is invalid: " + marker);
            }
            return;
        }

        if (Files.exists(root)) {
            if (!containsOnlyPreparedCacheDirectories(root)) {
                throw new GradleException(
                        "Refusing to initialize a non-empty test data cache without a marker: " + root
                );
            }
        } else {
            Files.createDirectories(root);
        }
        try {
            Files.writeString(marker, CACHE_MARKER_CONTENT, StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException exception) {
            if (!awaitValidCacheMarker()) {
                throw new GradleException("Concurrently initialized test data cache marker is invalid: " + marker);
            }
        }
    }

    /// Waits briefly for another task to finish publishing a concurrently created marker.
    private boolean awaitValidCacheMarker() throws IOException {
        for (int attempt = 0; attempt < 50; attempt++) {
            if (hasValidCacheMarker()) {
                return true;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new GradleException("Interrupted while waiting for the test data cache marker", exception);
            }
        }
        return false;
    }

    /// Copies a response while refusing to consume more than one byte beyond the declared size.
    private static void copyExpectedResponse(
            InputStream input,
            OutputStream output,
            long expectedSize,
            URI uri
    ) throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = Math.addExact(expectedSize, 1L);
        while (remaining > 0L) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) {
                return;
            }
            output.write(buffer, 0, count);
            remaining -= count;
        }
        throw new GradleException("Test data download exceeds its declared size: " + uri);
    }

    /// Rejects symbolic links in an existing cache-relative path before it is used for writes.
    private static void rejectSymbolicLinks(Path root, Path path) {
        if (!path.startsWith(root)) {
            throw new GradleException("Test data cache path is outside the cache root: " + path);
        }
        Path current = root;
        if (Files.isSymbolicLink(current)) {
            throw new GradleException("Refusing to use a symbolic-link test data cache path: " + current);
        }
        for (Path element : root.relativize(path)) {
            current = current.resolve(element);
            if (Files.isSymbolicLink(current)) {
                throw new GradleException("Refusing to use a symbolic-link test data cache path: " + current);
            }
        }
    }

    /// Returns whether a markerless root contains only empty content-addressed directories prepared by Gradle.
    private boolean containsOnlyPreparedCacheDirectories(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.allMatch(path -> {
                if (path.equals(root)) {
                    return true;
                }
                if (Files.isSymbolicLink(path) || !Files.isDirectory(path)) {
                    return false;
                }

                Path relative = root.relativize(path);
                int count = relative.getNameCount();
                if (count == 1) {
                    return relative.getName(0).toString().equals("downloads");
                }
                if (count == 2) {
                    return relative.getName(0).toString().equals("downloads")
                            && relative.getName(1).toString().equals("sha256");
                }
                return count == 3
                        && relative.getName(0).toString().equals("downloads")
                        && relative.getName(1).toString().equals("sha256")
                        && relative.getName(2).toString().matches("[0-9a-fA-F]{64}");
            });
        }
    }

    /// Returns whether a file matches the declared byte size and SHA-256.
    private boolean verify(Path path, boolean logFailure) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)) {
            return false;
        }

        long actualSize = Files.size(path);
        if (actualSize != getExpectedSize().get()) {
            if (logFailure) {
                getLogger().warn(
                        "Ignoring cached test data with size {} instead of {}: {}",
                        actualSize,
                        getExpectedSize().get(),
                        path
                );
            }
            return false;
        }

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is unavailable", exception);
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            while (true) {
                int count = input.read(buffer);
                if (count < 0) {
                    break;
                }
                digest.update(buffer, 0, count);
            }
        }
        String actualSha256 = HexFormat.of().formatHex(digest.digest());
        boolean valid = actualSha256.equalsIgnoreCase(getExpectedSha256().get());
        if (!valid && logFailure) {
            getLogger().warn(
                    "Ignoring cached test data with SHA-256 {} instead of {}: {}",
                    actualSha256,
                    getExpectedSha256().get(),
                    path
            );
        }
        return valid;
    }
}
