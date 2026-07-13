// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
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

/// Implements immutable slash-separated paths used by Arkivo file systems.
///
/// @param <F> the owning file system type
@NotNullByDefault
public abstract class AbstractArkivoPath<F extends FileSystem> implements Path {
    /// The file system that owns this path.
    private final F fileSystem;

    /// Whether this path starts at the file system root.
    private final boolean absolute;

    /// The immutable path name elements.
    private final @Unmodifiable List<String> names;

    /// The cached path text.
    private final String text;

    /// Creates a path from parsed components.
    protected AbstractArkivoPath(F fileSystem, boolean absolute, List<String> names) {
        this.fileSystem = Objects.requireNonNull(fileSystem, "fileSystem");
        this.absolute = absolute;
        this.names = List.copyOf(names);
        this.text = pathText(absolute, this.names);
    }

    /// Creates a path from one or more path text components.
    protected AbstractArkivoPath(F fileSystem, String first, String... more) {
        this(fileSystem, joinedPathText(first, more));
    }

    /// Creates a path from joined path text.
    private AbstractArkivoPath(F fileSystem, String value) {
        this(fileSystem, value.startsWith("/"), pathNames(value));
    }

    /// Creates another path of the concrete format-specific type.
    protected abstract AbstractArkivoPath<F> createPath(boolean absolute, List<String> names);

    /// Returns the URI scheme for the concrete archive format.
    protected abstract String uriScheme();

    /// Returns the underlying archive URI, or `null` when this file system has no URI representation.
    protected abstract @Nullable URI archiveUri();

    /// Returns this path as the concrete path type owned by the expected file system.
    protected static <F extends FileSystem, P extends AbstractArkivoPath<F>> P requirePath(
            Path path,
            F expectedFileSystem,
            Class<P> type
    ) {
        Objects.requireNonNull(type, "type");
        if (type.isInstance(path)) {
            P archivePath = type.cast(path);
            if (archivePath.getFileSystem() == expectedFileSystem) {
                return archivePath;
            }
        }
        throw new ProviderMismatchException();
    }

    /// Returns this path as a normalized archive entry path without a leading separator.
    public final String archivePath() {
        return String.join("/", names);
    }

    /// Returns the owning file system.
    @Override
    public final F getFileSystem() {
        return fileSystem;
    }

    /// Returns whether this path starts at the file system root.
    @Override
    public final boolean isAbsolute() {
        return absolute;
    }

    /// Returns the root component of this path.
    @Override
    public final @Nullable Path getRoot() {
        return absolute ? createPath(true, List.of()) : null;
    }

    /// Returns the final name component of this path.
    @Override
    public final @Nullable Path getFileName() {
        return names.isEmpty() ? null : createPath(false, List.of(names.get(names.size() - 1)));
    }

    /// Returns the parent path.
    @Override
    public final @Nullable Path getParent() {
        if (names.isEmpty()) {
            return null;
        }
        if (names.size() == 1) {
            return absolute ? createPath(true, List.of()) : null;
        }
        return createPath(absolute, names.subList(0, names.size() - 1));
    }

    /// Returns the number of name elements.
    @Override
    public final int getNameCount() {
        return names.size();
    }

    /// Returns a name element by index.
    @Override
    public final Path getName(int index) {
        return createPath(false, List.of(names.get(index)));
    }

    /// Returns a relative path containing a range of name elements.
    @Override
    public final Path subpath(int beginIndex, int endIndex) {
        return createPath(false, names.subList(beginIndex, endIndex));
    }

    /// Returns whether this path starts with another path.
    @Override
    public final boolean startsWith(Path other) {
        AbstractArkivoPath<F> that = compatiblePath(other);
        if (that == null || absolute != that.absolute || names.size() < that.names.size()) {
            return false;
        }
        return names.subList(0, that.names.size()).equals(that.names);
    }

    /// Returns whether this path starts with parsed path text.
    @Override
    public final boolean startsWith(String other) {
        return startsWith(fileSystem.getPath(other));
    }

    /// Returns whether this path ends with another path.
    @Override
    public final boolean endsWith(Path other) {
        AbstractArkivoPath<F> that = compatiblePath(other);
        if (that == null || that.absolute && !absolute || names.size() < that.names.size()) {
            return false;
        }
        return names.subList(names.size() - that.names.size(), names.size()).equals(that.names);
    }

    /// Returns whether this path ends with parsed path text.
    @Override
    public final boolean endsWith(String other) {
        return endsWith(fileSystem.getPath(other));
    }

    /// Returns this path with `.` and resolvable `..` elements removed.
    @Override
    public final Path normalize() {
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
        return createPath(absolute, normalizedNames);
    }

    /// Resolves another path against this path.
    @Override
    public final Path resolve(Path other) {
        AbstractArkivoPath<F> that = requireCompatiblePath(other);
        if (that.absolute) {
            return that;
        }
        if (that.names.isEmpty()) {
            return this;
        }
        ArrayList<String> resolvedNames = new ArrayList<>(names);
        resolvedNames.addAll(that.names);
        return createPath(absolute, resolvedNames);
    }

    /// Resolves parsed path text against this path.
    @Override
    public final Path resolve(String other) {
        return resolve(fileSystem.getPath(other));
    }

    /// Resolves another path against this path's parent.
    @Override
    public final Path resolveSibling(Path other) {
        Path parent = getParent();
        return parent != null ? parent.resolve(other) : other;
    }

    /// Resolves parsed path text against this path's parent.
    @Override
    public final Path resolveSibling(String other) {
        return resolveSibling(fileSystem.getPath(other));
    }

    /// Returns a relative path from this path to another path.
    @Override
    public final Path relativize(Path other) {
        AbstractArkivoPath<F> that = requireCompatiblePath(other);
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
        return createPath(false, relativeNames);
    }

    /// Converts this path to an archive URI.
    @Override
    public final URI toUri() {
        @Nullable URI sourceUri = archiveUri();
        if (sourceUri == null) {
            throw new UnsupportedOperationException("Archive paths without a source URI cannot be converted to URIs");
        }
        AbstractArkivoPath<F> absolutePath = requireCompatiblePath(toAbsolutePath().normalize());
        return URI.create(uriScheme() + ":" + sourceUri.toASCIIString() + "!/" + uriPath(absolutePath.names));
    }

    /// Returns this path as an absolute path.
    @Override
    public final Path toAbsolutePath() {
        return absolute ? this : createPath(true, List.of()).resolve(this);
    }

    /// Resolves this path to a checked normalized absolute path.
    @Override
    public final Path toRealPath(LinkOption... options) throws IOException {
        Objects.requireNonNull(options, "options");
        for (LinkOption option : options) {
            Objects.requireNonNull(option, "option");
        }
        Path realPath = toAbsolutePath().normalize();
        fileSystem.provider().checkAccess(realPath);
        return realPath;
    }

    /// Rejects watch registration because archive file systems do not support watch services.
    @Override
    public final WatchKey register(
            WatchService watcher,
            WatchEvent.Kind<?>[] events,
            WatchEvent.Modifier... modifiers
    ) {
        throw new UnsupportedOperationException("Archive watch services are not supported");
    }

    /// Returns an iterator over name elements.
    @Override
    public final Iterator<Path> iterator() {
        ArrayList<Path> paths = new ArrayList<>(names.size());
        for (String name : names) {
            paths.add(createPath(false, List.of(name)));
        }
        return List.copyOf(paths).iterator();
    }

    /// Compares this path with another compatible path.
    @Override
    public final int compareTo(Path other) {
        return text.compareTo(requireCompatiblePath(other).text);
    }

    /// Returns whether another object is the same path in the same file system.
    @Override
    public final boolean equals(@Nullable Object object) {
        if (object == this) {
            return true;
        }
        if (object == null || object.getClass() != getClass()) {
            return false;
        }
        AbstractArkivoPath<?> that = (AbstractArkivoPath<?>) object;
        return fileSystem == that.fileSystem
                && absolute == that.absolute
                && names.equals(that.names);
    }

    /// Returns the path hash code.
    @Override
    public final int hashCode() {
        return Objects.hash(System.identityHashCode(fileSystem), absolute, names);
    }

    /// Returns slash-separated path text.
    @Override
    public final String toString() {
        return text;
    }

    /// Returns a compatible path from the same concrete implementation and file system.
    @SuppressWarnings("unchecked")
    private @Nullable AbstractArkivoPath<F> compatiblePath(Path path) {
        if (path.getClass() == getClass()) {
            AbstractArkivoPath<F> that = (AbstractArkivoPath<F>) path;
            return that.fileSystem == fileSystem ? that : null;
        }
        return null;
    }

    /// Returns a compatible path from the same concrete implementation and file system.
    private AbstractArkivoPath<F> requireCompatiblePath(Path path) {
        AbstractArkivoPath<F> that = compatiblePath(path);
        if (that == null) {
            throw new IllegalArgumentException("Path belongs to a different file system");
        }
        return that;
    }

    /// Joins path text components with archive separators.
    private static String joinedPathText(String first, String... more) {
        Objects.requireNonNull(first, "first");
        StringBuilder builder = new StringBuilder(first);
        for (String part : more) {
            Objects.requireNonNull(part, "part");
            if (!builder.isEmpty() && builder.charAt(builder.length() - 1) != '/') {
                builder.append('/');
            }
            builder.append(part);
        }
        return builder.toString();
    }

    /// Parses non-empty slash-separated path names.
    private static @Unmodifiable List<String> pathNames(String value) {
        ArrayList<String> names = new ArrayList<>();
        for (String name : value.split("/+")) {
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    /// Returns an encoded URI path for the given names.
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
            throw new IllegalArgumentException("Invalid archive path segment", exception);
        }
    }

    /// Returns path text for parsed components.
    private static String pathText(boolean absolute, List<String> names) {
        if (names.isEmpty()) {
            return absolute ? "/" : "";
        }
        String joinedNames = String.join("/", names);
        return absolute ? "/" + joinedNames : joinedNames;
    }
}
