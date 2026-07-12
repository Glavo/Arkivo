// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.glavo.arkivo.archive.rar.RarArkivoFileSystemProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/// Represents a path inside a RAR Arkivo file system.
@NotNullByDefault
final class RarArkivoPath implements Path {
    /// The RAR file system that owns this path.
    private final RarArkivoFileSystemImpl fileSystem;

    /// Whether this path starts at the RAR file system root.
    private final boolean absolute;

    /// The path name elements.
    private final @Unmodifiable List<String> names;

    /// The cached path text.
    private final String text;

    /// Creates a RAR path.
    private RarArkivoPath(RarArkivoFileSystemImpl fileSystem, boolean absolute, List<String> names) {
        this.fileSystem = Objects.requireNonNull(fileSystem, "fileSystem");
        this.absolute = absolute;
        this.names = List.copyOf(names);
        this.text = pathText(absolute, this.names);
    }

    /// Returns the root path for the given file system.
    static RarArkivoPath root(RarArkivoFileSystemImpl fileSystem) {
        return new RarArkivoPath(fileSystem, true, List.of());
    }

    /// Returns a RAR path from path text components.
    static RarArkivoPath of(RarArkivoFileSystemImpl fileSystem, String first, String... more) {
        Objects.requireNonNull(first, "first");
        StringBuilder builder = new StringBuilder(first);
        for (String part : more) {
            Objects.requireNonNull(part, "part");
            if (!builder.isEmpty() && builder.charAt(builder.length() - 1) != '/') {
                builder.append('/');
            }
            builder.append(part);
        }
        return parse(fileSystem, builder.toString());
    }

    /// Returns a RAR path from a path text string.
    private static RarArkivoPath parse(RarArkivoFileSystemImpl fileSystem, String value) {
        boolean absolute = value.startsWith("/");
        ArrayList<String> names = new ArrayList<>();
        for (String name : value.split("/+")) {
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return new RarArkivoPath(fileSystem, absolute, names);
    }

    /// Returns this path as a RAR path owned by the expected file system.
    static RarArkivoPath require(Path path, RarArkivoFileSystemImpl expectedFileSystem) {
        if (path instanceof RarArkivoPath rarPath && rarPath.fileSystem == expectedFileSystem) {
            return rarPath;
        }
        throw new ProviderMismatchException();
    }

    /// Returns this path as a normalized archive node path.
    String archivePath() {
        return String.join("/", names);
    }

    /// Returns the owner RAR file system.
    @Override
    public RarArkivoFileSystemImpl getFileSystem() {
        return fileSystem;
    }

    /// Returns whether this path starts at the RAR file system root.
    @Override
    public boolean isAbsolute() {
        return absolute;
    }

    /// Returns the root component of this path.
    @Override
    public @Nullable Path getRoot() {
        return absolute ? root(fileSystem) : null;
    }

    /// Returns the final name component of this path.
    @Override
    public @Nullable Path getFileName() {
        if (names.isEmpty()) {
            return null;
        }
        return new RarArkivoPath(fileSystem, false, List.of(names.get(names.size() - 1)));
    }

    /// Returns the parent path.
    @Override
    public @Nullable Path getParent() {
        if (names.isEmpty()) {
            return null;
        }
        if (names.size() == 1) {
            return absolute ? root(fileSystem) : null;
        }
        return new RarArkivoPath(fileSystem, absolute, names.subList(0, names.size() - 1));
    }

    /// Returns the number of name elements.
    @Override
    public int getNameCount() {
        return names.size();
    }

    /// Returns a name element by index.
    @Override
    public Path getName(int index) {
        return new RarArkivoPath(fileSystem, false, List.of(names.get(index)));
    }

    /// Returns a relative path containing a range of name elements.
    @Override
    public Path subpath(int beginIndex, int endIndex) {
        return new RarArkivoPath(fileSystem, false, names.subList(beginIndex, endIndex));
    }

    /// Returns whether this path starts with another path.
    @Override
    public boolean startsWith(Path other) {
        RarArkivoPath that = asRarPath(other);
        if (that == null || absolute != that.absolute || names.size() < that.names.size()) {
            return false;
        }
        return names.subList(0, that.names.size()).equals(that.names);
    }

    /// Returns whether this path starts with another path.
    @Override
    public boolean startsWith(String other) {
        return startsWith(fileSystem.getPath(other));
    }

    /// Returns whether this path ends with another path.
    @Override
    public boolean endsWith(Path other) {
        RarArkivoPath that = asRarPath(other);
        if (that == null || that.absolute && !absolute || names.size() < that.names.size()) {
            return false;
        }
        return names.subList(names.size() - that.names.size(), names.size()).equals(that.names);
    }

    /// Returns whether this path ends with another path.
    @Override
    public boolean endsWith(String other) {
        return endsWith(fileSystem.getPath(other));
    }

    /// Returns this path with `.` and resolvable `..` elements removed.
    @Override
    public Path normalize() {
        ArrayList<String> normalizedNames = new ArrayList<>();
        for (String name : names) {
            if (".".equals(name)) {
                continue;
            }
            if ("..".equals(name)) {
                if (!normalizedNames.isEmpty() && !"..".equals(normalizedNames.get(normalizedNames.size() - 1))) {
                    normalizedNames.remove(normalizedNames.size() - 1);
                } else if (!absolute) {
                    normalizedNames.add(name);
                }
            } else {
                normalizedNames.add(name);
            }
        }
        return new RarArkivoPath(fileSystem, absolute, normalizedNames);
    }

    /// Resolves another path against this path.
    @Override
    public Path resolve(Path other) {
        RarArkivoPath that = requireRarPath(other);
        if (that.absolute) {
            return that;
        }
        if (that.names.isEmpty()) {
            return this;
        }
        ArrayList<String> resolvedNames = new ArrayList<>(names);
        resolvedNames.addAll(that.names);
        return new RarArkivoPath(fileSystem, absolute, resolvedNames);
    }

    /// Resolves another path against this path.
    @Override
    public Path resolve(String other) {
        return resolve(fileSystem.getPath(other));
    }

    /// Resolves another path against this path's parent.
    @Override
    public Path resolveSibling(Path other) {
        Path parent = getParent();
        return parent != null ? parent.resolve(other) : other;
    }

    /// Resolves another path against this path's parent.
    @Override
    public Path resolveSibling(String other) {
        return resolveSibling(fileSystem.getPath(other));
    }

    /// Returns a relative path from this path to another path.
    @Override
    public Path relativize(Path other) {
        RarArkivoPath that = requireRarPath(other);
        if (absolute != that.absolute) {
            throw new IllegalArgumentException("Cannot relativize paths with different root types");
        }

        int sharedNames = 0;
        while (sharedNames < names.size()
                && sharedNames < that.names.size()
                && names.get(sharedNames).equals(that.names.get(sharedNames))) {
            sharedNames++;
        }

        ArrayList<String> relativeNames = new ArrayList<>();
        for (int index = sharedNames; index < names.size(); index++) {
            relativeNames.add("..");
        }
        relativeNames.addAll(that.names.subList(sharedNames, that.names.size()));
        return new RarArkivoPath(fileSystem, false, relativeNames);
    }

    /// Converts this path to a URI.
    @Override
    public URI toUri() {
        RarArkivoPath absolutePath = (RarArkivoPath) toAbsolutePath().normalize();
        @Nullable URI archiveUri = fileSystem.archiveUri();
        if (archiveUri == null) {
            throw new UnsupportedOperationException("RAR paths backed by volume sources cannot be converted to URIs");
        }
        return URI.create(
                RarArkivoFileSystemProvider.SCHEME
                        + ":"
                        + archiveUri.toASCIIString()
                        + "!/"
                        + uriPath(absolutePath.names)
        );
    }

    /// Returns this path as an absolute path.
    @Override
    public Path toAbsolutePath() {
        return absolute ? this : root(fileSystem).resolve(this);
    }

    /// Resolves this path to a real path.
    @Override
    public Path toRealPath(LinkOption... options) throws IOException {
        Objects.requireNonNull(options, "options");
        Path realPath = toAbsolutePath().normalize();
        fileSystem.provider().checkAccess(realPath);
        return realPath;
    }

    /// Registers this path with a watch service.
    @Override
    public WatchKey register(
            WatchService watcher,
            WatchEvent.Kind<?>[] events,
            WatchEvent.Modifier... modifiers
    ) {
        throw new UnsupportedOperationException("RAR watch services are not supported");
    }

    /// Returns an iterator over name elements.
    @Override
    public Iterator<Path> iterator() {
        ArrayList<Path> paths = new ArrayList<>(names.size());
        for (String name : names) {
            paths.add(new RarArkivoPath(fileSystem, false, List.of(name)));
        }
        return List.copyOf(paths).iterator();
    }

    /// Compares this path with another path.
    @Override
    public int compareTo(Path other) {
        RarArkivoPath that = requireRarPath(other);
        return text.compareTo(that.text);
    }

    /// Compares this path with another object.
    @Override
    public boolean equals(@Nullable Object object) {
        return object instanceof RarArkivoPath that
                && fileSystem == that.fileSystem
                && absolute == that.absolute
                && names.equals(that.names);
    }

    /// Returns the hash code for this path.
    @Override
    public int hashCode() {
        return Objects.hash(System.identityHashCode(fileSystem), absolute, names);
    }

    /// Returns this path as RAR entry path text.
    @Override
    public String toString() {
        return text;
    }

    /// Returns this path as a RAR path from the same file system, or `null` when it is incompatible.
    private @Nullable RarArkivoPath asRarPath(Path path) {
        if (path instanceof RarArkivoPath that && that.fileSystem == fileSystem) {
            return that;
        }
        return null;
    }

    /// Returns this path as a RAR path from the same file system.
    private RarArkivoPath requireRarPath(Path path) {
        RarArkivoPath that = asRarPath(path);
        if (that == null) {
            throw new IllegalArgumentException("Path belongs to a different file system");
        }
        return that;
    }

    /// Returns an encoded URI path for RAR path names.
    private static String uriPath(List<String> names) {
        if (names.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String name : names) {
            if (!builder.isEmpty()) {
                builder.append('/');
            }
            builder.append(uriPathSegment(name));
        }
        return builder.toString();
    }

    /// Returns an encoded URI path segment.
    private static String uriPathSegment(String name) {
        try {
            String rawPath = new URI(null, null, "/" + name, null).getRawPath();
            return rawPath != null ? rawPath.substring(1) : "";
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid RAR path segment", exception);
        }
    }

    /// Returns path text for path components.
    private static String pathText(boolean absolute, List<String> names) {
        if (names.isEmpty()) {
            return absolute ? "/" : "";
        }
        String joinedNames = String.join("/", names);
        return absolute ? "/" + joinedNames : joinedNames;
    }
}
