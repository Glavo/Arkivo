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
    @OutputFile
    public abstract RegularFileProperty getCacheMarker();

    /// Returns the final content-addressed file.
    @OutputFile
    public abstract RegularFileProperty getDestination();

    /// Reuses a verified object or downloads, verifies, and atomically publishes it.
    @TaskAction
    public final void download() throws IOException {
        validateInputs();
        ensureCacheMarker();
        Path target = getDestination().get().getAsFile().toPath().toAbsolutePath().normalize();
        Path downloadRoot = getCacheRoot().get().getAsFile().toPath().toAbsolutePath().normalize()
                .resolve("downloads")
                .resolve("sha256");
        if (!target.startsWith(downloadRoot)) {
            throw new GradleException("Test data destination is outside the content-addressed cache: " + target);
        }
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

        Path parent = target.getParent();
        Files.createDirectories(parent);
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
                input.transferTo(output);
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
        if (size < 0L) {
            throw new GradleException("Expected test data size must be non-negative: " + size);
        }
    }

    /// Returns whether every persistent output is present and valid.
    private boolean isUpToDate() {
        try {
            return hasValidCacheMarker()
                    && verify(getDestination().get().getAsFile().toPath(), false);
        } catch (IOException exception) {
            return false;
        }
    }

    /// Returns whether the configured cache marker has the exact expected content.
    private boolean hasValidCacheMarker() throws IOException {
        Path marker = getCacheMarker().get().getAsFile().toPath();
        return Files.isRegularFile(marker)
                && CACHE_MARKER_CONTENT.equals(Files.readString(marker));
    }

    /// Initializes an empty cache or validates the marker of an existing cache.
    private void ensureCacheMarker() throws IOException {
        Path root = getCacheRoot().get().getAsFile().toPath().toAbsolutePath().normalize();
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
            try (var entries = Files.list(root)) {
                if (entries.findAny().isPresent()) {
                    throw new GradleException(
                            "Refusing to initialize a non-empty test data cache without a marker: " + root
                    );
                }
            }
        } else {
            Files.createDirectories(root);
        }
        Files.writeString(marker, CACHE_MARKER_CONTENT, StandardOpenOption.CREATE_NEW);
    }

    /// Returns whether a file matches the declared byte size and SHA-256.
    private boolean verify(Path path, boolean logFailure) throws IOException {
        if (!Files.isRegularFile(path)) {
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
