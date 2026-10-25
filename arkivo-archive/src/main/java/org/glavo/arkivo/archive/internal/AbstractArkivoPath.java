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
import java.nio.file.FileSystemLoopException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.NotLinkException;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Implements immutable slash-separated paths used by Arkivo file systems.
///
/// @param <F> the owning file system type
@NotNullByDefault
public abstract class AbstractArkivoPath<F extends FileSystem> implements Path {
    /// The maximum number of symbolic links resolved by one real-path operation.
    private static final int MAXIMUM_SYMBOLIC_LINKS = 40;

    /// The file system that owns this path.
    private final F fileSystem;

    /// Whether this path starts at the file system root.
    private final boolean absolute;

    /// The non-empty path name elements; an empty relative path exposes one empty name through the Path API.
    private final @Unmodifiable List<String> names;

    /// The cached path text.
    private final String text;

    /// Creates a path from parsed components.
    ///
    /// @param fileSystem the file system that owns the path
    /// @param absolute whether the path begins at the archive root
    /// @param names the parsed name elements to copy, each nonempty and containing no `/`
    protected AbstractArkivoPath(F fileSystem, boolean absolute, List<String> names) {
        this.fileSystem = Objects.requireNonNull(fileSystem, "fileSystem");
        this.absolute = absolute;
        this.names = List.copyOf(names);
        this.text = pathText(absolute, this.names);
    }

    /// Creates a path from one or more path text components.
    ///
    /// @param fileSystem the file system that owns the path
    /// @param first the first path text component
    /// @param more additional path text components joined with archive separators
    protected AbstractArkivoPath(F fileSystem, String first, String... more) {
        this(fileSystem, joinedPathText(first, more));
    }

    /// Creates a path from joined path text.
    private AbstractArkivoPath(F fileSystem, String value) {
        this(fileSystem, value.startsWith("/"), pathNames(value));
    }

    /// Creates another path of the concrete format-specific type.
    ///
    /// @param absolute whether the path begins at the archive root
    /// @param names the immutable parsed name elements
    /// @return a path of the concrete type owned by this path's file system
    protected abstract AbstractArkivoPath<F> createPath(boolean absolute, List<String> names);

    /// Returns the URI scheme for the concrete archive format.
    ///
    /// @return the format-specific archive file-system URI scheme
    protected abstract String uriScheme();

    /// Returns the underlying archive URI, or `null` when this file system has no URI representation.
    ///
    /// @return the backing archive URI, or {@code null} if no stable URI exists
    protected abstract @Nullable URI archiveUri();

    /// Reads this entry without resolving links or invoking the provider's path-resolution entry points.
    ///
    /// @return the entry's basic attributes
    /// @throws IOException if this entry cannot be read
    protected abstract BasicFileAttributes readEntryAttributes() throws IOException;

    /// Reads this link's stored target without resolving any path components.
    ///
    /// @return the stored target path
    /// @throws IOException if this entry is not a link or its target cannot be read
    protected abstract Path readLinkTarget() throws IOException;

    /// Returns this path as the concrete path type owned by the expected file system.
    ///
    /// @param <F> the expected file-system type
    /// @param <P> the expected concrete path type
    /// @param path the path to validate and cast
    /// @param expectedFileSystem the required owning file-system instance
    /// @param type the required concrete path class
    /// @return {@code path} cast to {@code P}
    /// @throws ProviderMismatchException if the path type or owning file system does not match
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

    /// Returns this path without a leading separator, retaining `.` and `..` elements.
    ///
    /// @return slash-separated name elements without a root marker
    public final String archivePath() {
        return absolute ? text.substring(1) : text;
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
        return names.isEmpty() ? (absolute ? null : this) : createPath(false, List.of(names.get(names.size() - 1)));
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
        return text.isEmpty() ? 1 : names.size();
    }

    /// Returns a name element by index.
    ///
    /// @throws IllegalArgumentException if `index` is negative or not less than [#getNameCount()]
    @Override
    public final Path getName(int index) {
        if (index < 0 || index >= getNameCount()) {
            throw new IllegalArgumentException("Path name index is out of range: " + index);
        }
        return text.isEmpty() ? this : createPath(false, List.of(names.get(index)));
    }

    /// Returns a relative path containing a range of name elements.
    ///
    /// @throws IllegalArgumentException if the range is empty or outside the name elements
    @Override
    public final Path subpath(int beginIndex, int endIndex) {
        if (beginIndex < 0
                || beginIndex >= getNameCount()
                || endIndex <= beginIndex
                || endIndex > getNameCount()) {
            throw new IllegalArgumentException(
                    "Invalid path name range: " + beginIndex + ".." + endIndex
            );
        }
        return text.isEmpty() ? this : createPath(false, names.subList(beginIndex, endIndex));
    }

    /// Returns whether this path starts with another path.
    @Override
    public final boolean startsWith(Path other) {
        @Nullable AbstractArkivoPath<F> that = compatiblePath(other);
        if (that != null && that.text.isEmpty()) {
            return text.isEmpty();
        }
        if (that == null || absolute != that.absolute || names.size() < that.names.size()) {
            return false;
        }
        int end = that.text.length();
        return text.startsWith(that.text)
                && (end == text.length() || that.names.isEmpty() || text.charAt(end) == '/');
    }

    /// Returns whether this path starts with parsed path text.
    @Override
    public final boolean startsWith(String other) {
        return startsWith(fileSystem.getPath(other));
    }

    /// Returns whether this path ends with another path.
    @Override
    public final boolean endsWith(Path other) {
        @Nullable AbstractArkivoPath<F> that = compatiblePath(other);
        if (that != null && that.text.isEmpty()) {
            return text.isEmpty();
        }
        if (that == null || names.size() < that.names.size()) {
            return false;
        }
        if (that.absolute) {
            return text.equals(that.text);
        }
        int start = text.length() - that.text.length();
        return text.endsWith(that.text) && (start == 0 || text.charAt(start - 1) == '/');
    }

    /// Returns whether this path ends with parsed path text.
    @Override
    public final boolean endsWith(String other) {
        return endsWith(fileSystem.getPath(other));
    }

    /// Returns this path with `.` and resolvable `..` elements removed.
    @Override
    public final Path normalize() {
        if (!names.contains(".") && !names.contains("..")) {
            return this;
        }
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
    ///
    /// @throws ProviderMismatchException if `other` belongs to another path implementation or archive file system
    @Override
    public final Path resolve(Path other) {
        AbstractArkivoPath<F> that = requireCompatiblePath(other);
        if (that.absolute) {
            return that;
        }
        if (that.names.isEmpty()) {
            return this;
        }
        if (names.isEmpty()) {
            return absolute ? createPath(true, that.names) : that;
        }
        ArrayList<String> resolvedNames = new ArrayList<>(names.size() + that.names.size());
        resolvedNames.addAll(names);
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
        Objects.requireNonNull(other, "other");
        @Nullable Path parent = getParent();
        return parent != null ? parent.resolve(other) : other;
    }

    /// Resolves parsed path text against this path's parent.
    @Override
    public final Path resolveSibling(String other) {
        return resolveSibling(fileSystem.getPath(other));
    }

    /// Returns a relative path from this path to another path.
    ///
    /// Single-dot elements are ignored in both paths. Parent elements in their common prefix and in the target
    /// suffix are retained without resolving symbolic links. An unmatched parent element in this path is rejected
    /// because its traversal cannot be reversed by adding further parent elements.
    ///
    /// @throws ProviderMismatchException if `other` belongs to another path implementation or archive file system
    /// @throws IllegalArgumentException if exactly one of the paths is absolute, or this path has a parent element
    ///                                  after the common prefix
    @Override
    public final Path relativize(Path other) {
        AbstractArkivoPath<F> that = requireCompatiblePath(other);
        if (absolute != that.absolute) {
            throw new IllegalArgumentException("Cannot relativize paths with different root types");
        }

        @Unmodifiable List<String> baseNames = names.contains(".")
                ? names.stream().filter(name -> !name.equals(".")).toList() : names;
        @Unmodifiable List<String> targetNames = that.names.contains(".")
                ? that.names.stream().filter(name -> !name.equals(".")).toList() : that.names;
        int sharedNames = 0;
        while (sharedNames < baseNames.size()
                && sharedNames < targetNames.size()
                && baseNames.get(sharedNames).equals(targetNames.get(sharedNames))) {
            sharedNames++;
        }

        ArrayList<String> relativeNames = new ArrayList<>();
        for (int index = sharedNames; index < baseNames.size(); index++) {
            if ("..".equals(baseNames.get(index))) {
                throw new IllegalArgumentException("Cannot relativize a path with unmatched parent elements");
            }
            relativeNames.add("..");
        }
        relativeNames.addAll(targetNames.subList(sharedNames, targetNames.size()));
        return createPath(false, relativeNames);
    }

    /// Converts this path to an absolute archive URI without normalizing its name elements or resolving links.
    @Override
    public final URI toUri() {
        @Nullable URI sourceUri = archiveUri();
        if (sourceUri == null) {
            throw new UnsupportedOperationException("Archive paths without a source URI cannot be converted to URIs");
        }
        AbstractArkivoPath<F> absolutePath = requireCompatiblePath(toAbsolutePath());
        String sourceText = sourceUri.toASCIIString();
        if (sourceUri.isOpaque()) {
            // Preserve existing escapes while quoting the source URI's own archive delimiters.
            sourceText = sourceText.replace("%", "%25");
        }
        return URI.create(uriScheme() + ":" + sourceText.replace("!", "%21")
                + "!/" + uriPath(absolutePath.names));
    }

    /// Returns this path as an absolute path.
    @Override
    public final Path toAbsolutePath() {
        return absolute ? this : createPath(true, names);
    }

    /// Returns an absolute path to the existing entry using the requested symbolic-link policy.
    ///
    /// Following links resolves each target before processing subsequent `..` components. With `NOFOLLOW_LINKS`,
    /// link names are retained and a link followed by `..` is not cancelled.
    @Override
    public final Path toRealPath(LinkOption... options) throws IOException {
        Objects.requireNonNull(options, "options");
        boolean noFollowLinks = false;
        for (LinkOption option : options) {
            Objects.requireNonNull(option, "option");
            if (option == LinkOption.NOFOLLOW_LINKS) {
                noFollowLinks = true;
            }
        }

        return noFollowLinks ? realPathWithoutFollowingLinks() : resolvePath(true);
    }

    /// Resolves parent links and optionally the final link, processing each parent component after its predecessor.
    ///
    /// @param followFinalLink whether the final entry is replaced by its link target
    /// @return a checked absolute path suitable for direct entry lookup
    /// @throws IOException if a component is absent, a parent is not a directory, or a link cannot be resolved
    final AbstractArkivoPath<F> resolvePath(boolean followFinalLink) throws IOException {
        return resolvePath(followFinalLink, false, false);
    }

    /// Resolves existing components and optionally retains a missing suffix for formats that synthesize parents.
    /// Missing parent components are accepted only when the remaining suffix has no `.` or `..` elements.
    ///
    /// @param followFinalLink whether to resolve the final symbolic link
    /// @param allowMissingFinal whether the final entry, or a final link's target, may be absent
    /// @param allowMissingParents whether missing parent names may be retained for subsequent creation
    /// @return an absolute path with resolved parents
    /// @throws IOException if a required component is absent, a parent is not a directory, or link resolution fails
    final AbstractArkivoPath<F> resolvePath(
            boolean followFinalLink, boolean allowMissingFinal, boolean allowMissingParents
    ) throws IOException {
        // Ordinary paths can traverse their immutable components without copying a work queue.
        List<String> pending = names;
        int next = 0;
        ArrayList<String> resolved = new ArrayList<>(names.size());
        AbstractArkivoPath<F> result = this;
        @Nullable BasicFileAttributes attributes = null;
        int symbolicLinkCount = 0;
        while (next < pending.size()) {
            if (attributes != null && !attributes.isDirectory()) {
                throw new NotDirectoryException(result.toString());
            }
            String name = pending.get(next++);
            if (name.equals(".")) {
                continue;
            }
            if (name.equals("..")) {
                if (!resolved.isEmpty()) {
                    resolved.remove(resolved.size() - 1);
                }
                attributes = null;
                continue;
            }
            resolved.add(name);
            result = absolute && next == pending.size() && names.equals(resolved)
                    ? this : createPath(true, resolved);
            try {
                attributes = result.readEntryAttributes();
            } catch (NoSuchFileException exception) {
                if (allowMissingFinal && next == pending.size()) {
                    return equals(result) ? this : result;
                }
                if (allowMissingParents) {
                    List<String> remaining = pending.subList(next, pending.size());
                    if (!remaining.contains(".") && !remaining.contains("..")) {
                        resolved.addAll(remaining);
                        return createPath(true, resolved);
                    }
                }
                throw exception;
            }
            if (attributes.isSymbolicLink() && (followFinalLink || next < pending.size())) {
                final AbstractArkivoPath<F> target;
                try {
                    target = requireCompatiblePath(result.readLinkTarget());
                } catch (NotLinkException exception) {
                    // The link may have been replaced after its attributes were read.
                    attributes = result.readEntryAttributes();
                    continue;
                }
                if (++symbolicLinkCount > MAXIMUM_SYMBOLIC_LINKS) {
                    throw new FileSystemLoopException(toString());
                }
                resolved.remove(resolved.size() - 1);
                if (target.absolute) {
                    resolved.clear();
                }
                ArrayList<String> expanded = new ArrayList<>(target.names.size() + pending.size() - next);
                expanded.addAll(target.names);
                expanded.addAll(pending.subList(next, pending.size()));
                pending = expanded;
                next = 0;
                attributes = null;
            }
        }
        if (attributes == null) {
            result = absolute && names.equals(resolved) ? this : createPath(true, resolved);
            result.readEntryAttributes();
        }
        return equals(result) ? this : result;
    }

    /// Checks existence while retaining link names and parent components that cannot safely be cancelled.
    private AbstractArkivoPath<F> realPathWithoutFollowingLinks() throws IOException {
        ArrayList<String> retained = new ArrayList<>();
        for (String name : names) {
            if (name.equals(".") || name.equals("..")) {
                AbstractArkivoPath<F> prefix = createPath(true, retained);
                if (!prefix.resolvePath(true).readEntryAttributes().isDirectory()) {
                    throw new NotDirectoryException(prefix.toString());
                }
                if (name.equals("..") && !retained.isEmpty()) {
                    if (!retained.get(retained.size() - 1).equals("..")
                            && !prefix.resolvePath(false).readEntryAttributes().isSymbolicLink()) {
                        retained.remove(retained.size() - 1);
                    } else {
                        retained.add(name);
                    }
                }
            } else {
                retained.add(name);
            }
        }
        AbstractArkivoPath<F> result = createPath(true, retained);
        result.resolvePath(false);
        return equals(result) ? this : result;
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

    /// Compares this path with another compatible path.
    ///
    /// @throws ClassCastException if `other` belongs to another path implementation or archive file system
    @Override
    public final int compareTo(Path other) {
        return text.compareTo(requireComparablePath(other).text);
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
        return fileSystem == that.fileSystem && text.equals(that.text);
    }

    /// Returns the path hash code.
    @Override
    public final int hashCode() {
        return 31 * System.identityHashCode(fileSystem) + text.hashCode();
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
    ///
    /// @throws ProviderMismatchException if the path has a different implementation or owning file system
    private AbstractArkivoPath<F> requireCompatiblePath(Path path) {
        AbstractArkivoPath<F> that = compatiblePath(path);
        if (that == null) {
            throw new ProviderMismatchException("Path belongs to a different archive file system");
        }
        return that;
    }

    /// Returns a comparable path from the same concrete implementation and file system.
    ///
    /// @throws ClassCastException if the path has a different implementation or owning file system
    private AbstractArkivoPath<F> requireComparablePath(Path path) {
        AbstractArkivoPath<F> that = compatiblePath(path);
        if (that == null) {
            throw new ClassCastException("Path belongs to a different archive file system");
        }
        return that;
    }

    /// Joins path text components with archive separators.
    private static String joinedPathText(String first, String... more) {
        Objects.requireNonNull(first, "first");
        if (more.length == 0) {
            return first;
        }
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
        if (value.indexOf('/') < 0) {
            return value.isEmpty() ? List.of() : List.of(value);
        }
        ArrayList<String> names = new ArrayList<>();
        for (int start = 0; start < value.length();) {
            int end = value.indexOf('/', start);
            if (end < 0) end = value.length();
            if (end > start) names.add(value.substring(start, end));
            start = end + 1;
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
        String joinedNames = names.size() == 1 ? names.get(0) : String.join("/", names);
        return absolute ? "/" + joinedNames : joinedNames;
    }
}
