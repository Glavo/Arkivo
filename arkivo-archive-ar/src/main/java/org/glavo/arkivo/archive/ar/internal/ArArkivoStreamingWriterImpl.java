// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar.internal;

import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoStoredContent;
import org.glavo.arkivo.archive.ar.ArArkivoEntryAttributeView;
import org.glavo.arkivo.archive.ar.ArArkivoEntryAttributes;
import org.glavo.arkivo.archive.ar.ArArkivoStreamingWriter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;

/// Implements the public forward-only AR streaming writer API.
@NotNullByDefault
public final class ArArkivoStreamingWriterImpl extends ArArkivoStreamingWriter {
    /// The global AR archive signature.
    private static final byte @Unmodifiable [] GLOBAL_HEADER = "!<arch>\n".getBytes(StandardCharsets.US_ASCII);

    /// The AR member header trailer.
    private static final byte @Unmodifiable [] MEMBER_TRAILER = new byte[]{'`', '\n'};

    /// The empty AR member body.
    private static final byte @Unmodifiable [] EMPTY_BODY = new byte[0];

    /// The default regular member mode.
    private static final int DEFAULT_FILE_MODE = 0100644;

    /// The default directory member mode.
    private static final int DEFAULT_DIRECTORY_MODE = 040755;

    /// The default symbolic link member mode.
    private static final int DEFAULT_SYMBOLIC_LINK_MODE = 0120777;

    /// Sentinel used when the member body size is not known before writing.
    private static final long UNKNOWN_SIZE = -1L;

    /// The largest AR member size representable by the fixed-width header field.
    private static final long MAX_MEMBER_SIZE = 9_999_999_999L;

    /// The backing archive output stream.
    private final OutputStream output;

    /// The storage used when a member body size is not known before writing.
    private final ArkivoEditStorage bodyStorage;

    /// Stored bodies whose first cleanup attempt failed and must be retried while closing.
    private final ArrayList<ArkivoStoredContent> retiredBodies = new ArrayList<>();

    /// Whether the global AR archive header has been written.
    private boolean globalHeaderWritten;

    /// Whether this writer still accepts new members.
    private boolean open = true;

    /// Whether the backing archive output stream has been closed.
    private boolean outputClosed;

    /// Whether the body storage has been closed.
    private boolean bodyStorageClosed;

    /// The pending member that has not yet been committed.
    private @Nullable PendingMember pendingMember;

    /// The open member body stream that will finish a file member when closed.
    private @Nullable OutputStream currentBody;

    /// Creates a streaming AR writer.
    public ArArkivoStreamingWriterImpl(OutputStream output) {
        this(output, ArkivoEditStorage.temporaryFiles(defaultBodyStorageDirectory()));
    }

    /// Creates a streaming AR writer that owns the given body storage.
    public ArArkivoStreamingWriterImpl(OutputStream output, ArkivoEditStorage bodyStorage) {
        this.output = Objects.requireNonNull(output, "output");
        this.bodyStorage = Objects.requireNonNull(bodyStorage, "bodyStorage");
    }

    /// Returns the directory used by default staged body storage.
    private static Path defaultBodyStorageDirectory() {
        return Path.of(System.getProperty("java.io.tmpdir", ".")).toAbsolutePath().normalize();
    }

    /// Begins a pending regular file AR member for the given logical archive path.
    @Override
    protected void beginFileEntry(String path) throws IOException {
        ensureOpen();
        if (pendingMember != null) {
            throw new IllegalStateException("An AR streaming member is already pending");
        }
        if (currentBody != null) {
            throw new IllegalStateException("An AR streaming member body is still open");
        }
        pendingMember = new PendingMember(memberPathText(path), DEFAULT_FILE_MODE, MemberKind.REGULAR_FILE, null);
    }

    /// Begins a pending directory AR member for the given logical archive path.
    @Override
    protected void beginDirectoryEntry(String path) throws IOException {
        ensureOpen();
        if (pendingMember != null) {
            throw new IllegalStateException("An AR streaming member is already pending");
        }
        if (currentBody != null) {
            throw new IllegalStateException("An AR streaming member body is still open");
        }
        pendingMember = new PendingMember(
                memberPathText(path),
                DEFAULT_DIRECTORY_MODE,
                MemberKind.DIRECTORY,
                EMPTY_BODY
        );
    }

    /// Begins a pending symbolic link AR member for the given logical archive path and target path text.
    @Override
    protected void beginSymbolicLinkEntry(String path, String target) throws IOException {
        ensureOpen();
        if (pendingMember != null) {
            throw new IllegalStateException("An AR streaming member is already pending");
        }
        if (currentBody != null) {
            throw new IllegalStateException("An AR streaming member body is still open");
        }
        pendingMember = new PendingMember(
                memberPathText(path),
                DEFAULT_SYMBOLIC_LINK_MODE,
                MemberKind.SYMBOLIC_LINK,
                linkTargetText(target).getBytes(StandardCharsets.UTF_8)
        );
    }

    /// Writes an indexed AR member snapshot while preserving its metadata and body.
    void writeSnapshot(
            ArArkivoEntryAttributes attributes,
            @Nullable ReadableByteChannel body,
            long bodySize
    ) throws IOException {
        ensureOpen();
        Objects.requireNonNull(attributes, "attributes");
        if (bodySize < 0L) {
            throw new IllegalArgumentException("AR snapshot body size must not be negative");
        }
        if (bodySize > 0L && body == null) {
            throw new IllegalArgumentException("AR snapshot body channel is required for non-empty content");
        }
        if (pendingMember != null) {
            throw new IllegalStateException("An AR streaming member is already pending");
        }
        if (currentBody != null) {
            throw new IllegalStateException("An AR streaming member body is still open");
        }

        PendingMember member = new PendingMember(
                memberPathText(attributes.path()),
                attributes.mode(),
                MemberKind.REGULAR_FILE,
                null
        );
        pendingMember = member;
        try {
            member.attributes.setTimes(attributes.lastModifiedTime(), null, null);
            member.attributes.setUserId(attributes.userId());
            member.attributes.setGroupId(attributes.groupId());
            member.attributes.setSize(bodySize);
        } finally {
            pendingMember = null;
        }
        MemberLayout layout = memberLayout(member, bodySize);
        writeMemberPrefix(member, layout);
        if (bodySize > 0L) {
            writeBody(Objects.requireNonNull(body, "body"), bodySize);
        }
        writeMemberPadding(layout.storedSize());
    }

    /// Copies exactly one indexed member body to the archive output using bounded memory.
    private void writeBody(ReadableByteChannel body, long size) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        long remaining = size;
        while (remaining > 0L) {
            buffer.clear();
            buffer.limit((int) Math.min(remaining, buffer.capacity()));
            int count = body.read(buffer);
            if (count < 0) {
                throw new IOException("AR snapshot body ended before its declared size");
            }
            if (count == 0) {
                continue;
            }
            output.write(buffer.array(), 0, count);
            remaining -= count;
        }
    }

    /// Returns an attribute view used to configure the current pending entry before it is committed.
    @Override
    protected <V extends FileAttributeView> @Nullable V currentAttributeView(Class<V> type) {
        Objects.requireNonNull(type, "type");
        PendingMember member = requirePendingMember();
        if (type == BasicFileAttributeView.class || type == ArArkivoEntryAttributeView.class) {
            return type.cast(member.attributes);
        }
        return null;
    }

    /// Commits the current pending member as an empty regular file member.
    @Override
    protected void finishCurrentEntry() throws IOException {
        ensureOpen();
        PendingMember member = requirePendingMember();
        member.ensurePending();
        byte @Unmodifiable [] body = member.fixedBodyOrEmpty();
        ensureMemberBodySize(member, body.length);
        pendingMember = null;
        writeMember(member, body);
    }

    /// Opens a writable channel for the current pending member and commits it when the channel is closed.
    @Override
    protected WritableByteChannel openCurrentChannel() throws IOException {
        return StreamChannelAdapters.writableChannel(openBodyStream());
    }

    /// Opens an output stream for the current pending member and commits it when the stream is closed.
    private OutputStream openBodyStream() throws IOException {
        ensureOpen();
        PendingMember member = requirePendingMember();
        member.ensurePending();
        if (member.kind() != MemberKind.REGULAR_FILE) {
            throw new IllegalStateException("AR " + member.kind().description() + " entries cannot open a body stream");
        }
        long expectedSize = member.attributes.expectedSize();
        if (expectedSize != UNKNOWN_SIZE) {
            MemberLayout layout = memberLayout(member, expectedSize);
            pendingMember = null;
            writeMemberPrefix(member, layout);
            DirectMemberBodyOutputStream body = new DirectMemberBodyOutputStream(member, layout);
            currentBody = body;
            return body;
        }

        pendingMember = null;
        StoredMemberBodyOutputStream body = new StoredMemberBodyOutputStream(member);
        currentBody = body;
        return body;
    }

    /// Closes this streaming writer and finishes the AR archive stream.
    @Override
    protected void closeWriter() throws IOException {
        if (!open && outputClosed && retiredBodies.isEmpty() && bodyStorageClosed) {
            return;
        }

        IOException failure = null;
        OutputStream body = currentBody;
        if (body != null) {
            try {
                body.close();
            } catch (IOException exception) {
                failure = exception;
            }
        }

        PendingMember member = pendingMember;
        if (failure == null && member != null) {
            pendingMember = null;
            try {
                member.ensurePending();
                byte @Unmodifiable [] memberBody = member.fixedBodyOrEmpty();
                ensureMemberBodySize(member, memberBody.length);
                writeMember(member, memberBody);
            } catch (IOException exception) {
                pendingMember = member;
                failure = exception;
            }
        }

        if (failure == null) {
            try {
                ensureGlobalHeader();
            } catch (IOException exception) {
                failure = exception;
            }
        }

        open = false;
        try {
            output.close();
            outputClosed = true;
        } catch (IOException exception) {
            if (failure != null) {
                failure.addSuppressed(exception);
            } else {
                failure = exception;
            }
        }

        failure = closeBodyStorage(failure);

        if (failure != null) {
            throw failure;
        }
    }

    /// Returns the current pending member.
    private PendingMember requirePendingMember() {
        PendingMember member = pendingMember;
        if (member == null) {
            throw new IllegalStateException("No AR streaming member is pending");
        }
        return member;
    }

    /// Ensures that a pending member's actual body size matches any configured size.
    private void ensureMemberBodySize(PendingMember member, long actualSize) throws IOException {
        long expectedSize = member.attributes.expectedSize();
        if (expectedSize != UNKNOWN_SIZE && expectedSize != actualSize) {
            throw new IOException("AR member body size does not match configured size for " + member.path);
        }
    }

    /// Writes one AR member, including any inline BSD long name prefix.
    private void writeMember(PendingMember member, byte[] body) throws IOException {
        MemberLayout layout = memberLayout(member, body.length);
        writeMemberPrefix(member, layout);
        output.write(body);
        writeMemberPadding(layout.storedSize());
    }

    /// Writes one staged AR member body from a readable channel.
    private void writeStoredMember(PendingMember member, ReadableByteChannel body, long bodySize) throws IOException {
        MemberLayout layout = memberLayout(member, bodySize);
        writeMemberPrefix(member, layout);
        writeBody(body, bodySize);
        writeMemberPadding(layout.storedSize());
    }

    /// Closes retired staged bodies and their owning storage, preserving cleanup failures.
    private @Nullable IOException closeBodyStorage(@Nullable IOException failure) {
        var iterator = retiredBodies.iterator();
        while (iterator.hasNext()) {
            ArkivoStoredContent content = iterator.next();
            try {
                content.close();
                iterator.remove();
            } catch (IOException exception) {
                if (failure != null) {
                    failure.addSuppressed(exception);
                } else {
                    failure = exception;
                }
            }
        }
        if (!bodyStorageClosed) {
            try {
                bodyStorage.close();
                bodyStorageClosed = true;
            } catch (IOException exception) {
                if (failure != null) {
                    failure.addSuppressed(exception);
                } else {
                    failure = exception;
                }
            }
        }
        return failure;
    }

    /// Releases staged content now or retains it for a close-time cleanup retry.
    private void releaseBody(ArkivoStoredContent content) {
        try {
            content.close();
        } catch (IOException exception) {
            retiredBodies.add(content);
        }
    }

    /// Calculates the AR header layout for a member body.
    private MemberLayout memberLayout(PendingMember member, long bodySize) throws IOException {
        if (bodySize < 0L) {
            throw new IOException("AR member body size must not be negative");
        }

        byte[] pathBytes = member.path.getBytes(StandardCharsets.UTF_8);
        String identifier = standardIdentifier(member.path);
        byte[] namePrefix = new byte[0];
        long storedSize = bodySize;
        if (identifier == null) {
            identifier = "#1/" + pathBytes.length;
            namePrefix = pathBytes;
            storedSize = bodySize + pathBytes.length;
            if (storedSize < bodySize) {
                throw new IOException("AR member size field is too wide");
            }
        }
        requireMemberSize(storedSize);
        return new MemberLayout(identifier, namePrefix, bodySize, storedSize);
    }

    /// Writes the AR member header and any BSD inline long-name prefix.
    private void writeMemberPrefix(PendingMember member, MemberLayout layout) throws IOException {
        member.committed = true;
        ensureGlobalHeader();
        PendingArEntryAttributeView attributes = member.attributes;
        writeMemberHeader(
                layout.identifier(),
                timestampSeconds(attributes.lastModifiedTime()),
                attributes.userId(),
                attributes.groupId(),
                attributes.mode(),
                layout.storedSize()
        );
        output.write(layout.namePrefix());
    }

    /// Writes the AR member padding byte when the stored member size is odd.
    private void writeMemberPadding(long storedSize) throws IOException {
        if ((storedSize & 1L) != 0L) {
            output.write('\n');
        }
    }

    /// Writes the global AR archive header if needed.
    private void ensureGlobalHeader() throws IOException {
        if (!globalHeaderWritten) {
            output.write(GLOBAL_HEADER);
            globalHeaderWritten = true;
        }
    }

    /// Writes one fixed-width AR member header.
    private void writeMemberHeader(
            String identifier,
            long timestamp,
            long userId,
            long groupId,
            int mode,
            long size
    ) throws IOException {
        writeField(identifier, 16, "identifier");
        writeField(Long.toString(timestamp), 12, "timestamp");
        writeField(Long.toString(userId), 6, "user id");
        writeField(Long.toString(groupId), 6, "group id");
        writeField(Integer.toOctalString(mode), 8, "mode");
        writeField(Long.toString(size), 10, "size");
        output.write(MEMBER_TRAILER);
    }

    /// Writes one space-padded AR header field.
    private void writeField(String value, int width, String fieldName) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length > width) {
            throw new IOException("AR " + fieldName + " field is too wide");
        }
        output.write(bytes);
        for (int index = bytes.length; index < width; index++) {
            output.write(' ');
        }
    }

    /// Ensures that a stored AR member size fits the fixed-width size field.
    private static void requireMemberSize(long size) throws IOException {
        if (size > MAX_MEMBER_SIZE) {
            throw new IOException("AR member size field is too wide");
        }
    }

    /// Returns the standard AR identifier for a path, or `null` when a BSD long name is required.
    private static @Nullable String standardIdentifier(String path) {
        if (path.startsWith("#1/") || path.equals("/") || path.equals("//") || path.equals("/SYM64/")) {
            return null;
        }
        for (int index = 0; index < path.length(); index++) {
            if (path.charAt(index) > 0x7f) {
                return null;
            }
        }
        String identifier = path + "/";
        return identifier.getBytes(StandardCharsets.US_ASCII).length <= 16 ? identifier : null;
    }

    /// Returns an AR timestamp in epoch seconds.
    private static long timestampSeconds(FileTime time) throws IOException {
        long seconds = time.toInstant().getEpochSecond();
        if (seconds < 0L) {
            throw new IOException("AR timestamp must not be negative");
        }
        return seconds;
    }

    /// Converts a logical member path to normalized AR path text.
    private static String memberPathText(String path) {
        Objects.requireNonNull(path, "path");
        String normalizedSeparators = path.replace('\\', '/');
        if (normalizedSeparators.startsWith("/") || normalizedSeparators.length() >= 2
                && normalizedSeparators.charAt(1) == ':') {
            throw new IllegalArgumentException("AR streaming member paths must be relative");
        }

        StringBuilder builder = new StringBuilder();
        int start = 0;
        while (start <= normalizedSeparators.length()) {
            int end = normalizedSeparators.indexOf('/', start);
            if (end < 0) {
                end = normalizedSeparators.length();
            }
            String name = normalizedSeparators.substring(start, end);
            if (!name.isEmpty() && !".".equals(name)) {
                if ("..".equals(name)) {
                    throw new IllegalArgumentException("AR streaming member paths must not contain ..");
                }
                if (!builder.isEmpty()) {
                    builder.append('/');
                }
                builder.append(name);
            }
            start = end + 1;
        }
        if (builder.isEmpty()) {
            throw new IllegalArgumentException("AR streaming member path is empty");
        }
        return builder.toString();
    }

    /// Converts a symbolic link target to AR member body text.
    private static String linkTargetText(String target) {
        Objects.requireNonNull(target, "target");
        if (target.isEmpty()) {
            throw new IllegalArgumentException("AR symbolic link target is empty");
        }
        return target;
    }

    /// Ensures this writer is still open.
    private void ensureOpen() throws IOException {
        if (!open) {
            throw new IOException("AR streaming writer is closed");
        }
    }

    /// Stores the encoded header layout for one AR member.
    ///
    /// @param identifier the fixed-width header identifier
    /// @param namePrefix the BSD inline long-name prefix bytes written before the logical body
    /// @param bodySize   the logical member body size
    /// @param storedSize the complete stored member size, including any inline name prefix
    @NotNullByDefault
    private record MemberLayout(
            String identifier,
            byte @Unmodifiable [] namePrefix,
            long bodySize,
            long storedSize
    ) {
        /// Creates an AR member layout.
        private MemberLayout {
            Objects.requireNonNull(identifier, "identifier");
            namePrefix = Objects.requireNonNull(namePrefix, "namePrefix").clone();
        }
    }

    /// Describes the logical kind of pending AR member.
    @NotNullByDefault
    private enum MemberKind {
        /// A regular file member whose body can be streamed by callers.
        REGULAR_FILE("regular file"),

        /// A directory member with a fixed empty body.
        DIRECTORY("directory"),

        /// A symbolic link member whose body stores the link target text.
        SYMBOLIC_LINK("symbolic link");

        /// The phrase used in diagnostics for this member kind.
        private final String description;

        /// Creates a member kind.
        MemberKind(String description) {
            this.description = Objects.requireNonNull(description, "description");
        }

        /// Returns the phrase used in diagnostics for this member kind.
        private String description() {
            return description;
        }
    }

    /// Stores a pending AR member.
    @NotNullByDefault
    private final class PendingMember {
        /// The normalized logical member path.
        private final String path;

        /// The logical member kind.
        private final MemberKind kind;

        /// The pending AR member attribute view.
        private final PendingArEntryAttributeView attributes;

        /// The fixed member body, or `null` when callers stream the body.
        private final byte @Nullable @Unmodifiable [] fixedBody;

        /// Whether this member has already been committed.
        private boolean committed;

        /// Creates a pending AR member.
        private PendingMember(String path, int initialMode, MemberKind kind, byte @Nullable [] fixedBody) {
            this.path = Objects.requireNonNull(path, "path");
            this.kind = Objects.requireNonNull(kind, "kind");
            this.attributes = new PendingArEntryAttributeView(this, initialMode);
            this.fixedBody = fixedBody != null ? fixedBody.clone() : null;
        }

        /// Ensures this member can still be configured.
        private void ensurePending() {
            if (committed || pendingMember != this) {
                throw new IllegalStateException("AR streaming member has already been committed");
            }
        }

        /// Returns whether this pending member has a fixed body.
        private boolean hasFixedBody() {
            return fixedBody != null;
        }

        /// Returns the logical member kind.
        private MemberKind kind() {
            return kind;
        }

        /// Returns the fixed member body size, or zero for streamable members.
        private long fixedBodySize() {
            byte @Nullable @Unmodifiable [] body = fixedBody;
            return body != null ? body.length : 0L;
        }

        /// Returns the fixed member body, or an empty body for streamable members.
        private byte @Unmodifiable [] fixedBodyOrEmpty() {
            byte @Nullable @Unmodifiable [] body = fixedBody;
            return body != null ? body : EMPTY_BODY;
        }
    }

    /// Stores writable AR member metadata before a streaming member is committed.
    @NotNullByDefault
    private final class PendingArEntryAttributeView implements ArArkivoEntryAttributeView {
        /// The member configured by this view.
        private final PendingMember member;

        /// The member last modified timestamp.
        private FileTime lastModifiedTime = FileTime.fromMillis(0L);

        /// The member numeric user identifier.
        private long userId;

        /// The member numeric group identifier.
        private long groupId;

        /// The member POSIX mode bits.
        private int mode;

        /// The expected member body size, or the unknown-size sentinel.
        private long expectedSize = UNKNOWN_SIZE;

        /// Creates a pending AR member attribute view.
        private PendingArEntryAttributeView(PendingMember member, int initialMode) {
            this.member = Objects.requireNonNull(member, "member");
            this.mode = initialMode;
        }

        /// Reads the pending AR-specific member attributes.
        @Override
        public ArArkivoEntryAttributes readAttributes() {
            return new PendingArEntryAttributes(member, this);
        }

        /// Sets the member timestamp.
        @Override
        public void setTimes(
                @Nullable FileTime lastModifiedTime,
                @Nullable FileTime lastAccessTime,
                @Nullable FileTime createTime
        ) {
            member.ensurePending();
            if (lastModifiedTime != null) {
                this.lastModifiedTime = lastModifiedTime;
            }
        }

        /// Sets the numeric user identifier stored by the AR header.
        @Override
        public void setUserId(long userId) {
            if (userId < 0L) {
                throw new IllegalArgumentException("userId must not be negative");
            }
            member.ensurePending();
            this.userId = userId;
        }

        /// Sets the numeric group identifier stored by the AR header.
        @Override
        public void setGroupId(long groupId) {
            if (groupId < 0L) {
                throw new IllegalArgumentException("groupId must not be negative");
            }
            member.ensurePending();
            this.groupId = groupId;
        }

        /// Sets the POSIX mode bits stored by the AR header.
        @Override
        public void setMode(int mode) {
            if (mode < 0) {
                throw new IllegalArgumentException("mode must not be negative");
            }
            member.ensurePending();
            if (member.kind() == MemberKind.DIRECTORY && !ArPosixSupport.isDirectory(mode)) {
                throw new IllegalArgumentException("mode must describe an AR directory");
            }
            if (member.kind() == MemberKind.SYMBOLIC_LINK && !ArPosixSupport.isSymbolicLink(mode)) {
                throw new IllegalArgumentException("mode must describe an AR symbolic link");
            }
            this.mode = mode;
        }

        /// Sets the expected member data size before the body stream is opened.
        @Override
        public void setSize(long size) throws IOException {
            if (size < 0L) {
                throw new IllegalArgumentException("size must not be negative");
            }
            member.ensurePending();
            if (member.hasFixedBody() && size != member.fixedBodySize()) {
                throw new IOException("AR member body size does not match configured size for " + member.path);
            }
            memberLayout(member, size);
            this.expectedSize = size;
        }

        /// Returns the member last modified timestamp.
        private FileTime lastModifiedTime() {
            return lastModifiedTime;
        }

        /// Returns the member numeric user identifier.
        private long userId() {
            return userId;
        }

        /// Returns the member numeric group identifier.
        private long groupId() {
            return groupId;
        }

        /// Returns the member POSIX mode bits.
        private int mode() {
            return mode;
        }

        /// Returns the configured expected member data size, or the unknown-size sentinel.
        private long expectedSize() {
            return expectedSize;
        }

        /// Returns the visible pending member data size.
        private long size() {
            return expectedSize == UNKNOWN_SIZE ? member.fixedBodySize() : expectedSize;
        }
    }

    /// Exposes a snapshot of pending AR member metadata.
    @NotNullByDefault
    private static final class PendingArEntryAttributes implements ArArkivoEntryAttributes {
        /// The pending member.
        private final PendingMember member;

        /// The pending metadata view.
        private final PendingArEntryAttributeView attributes;

        /// Creates a pending AR member attribute snapshot.
        private PendingArEntryAttributes(PendingMember member, PendingArEntryAttributeView attributes) {
            this.member = Objects.requireNonNull(member, "member");
            this.attributes = Objects.requireNonNull(attributes, "attributes");
        }

        /// Returns the decoded archive member path.
        @Override
        public String path() {
            return member.path;
        }

        /// Returns the raw AR member identifier before long-name resolution.
        @Override
        public String identifier() {
            String identifier = standardIdentifier(member.path);
            if (identifier != null) {
                return identifier;
            }
            return "#1/" + member.path.getBytes(StandardCharsets.UTF_8).length;
        }

        /// Returns the numeric user identifier stored by the AR header.
        @Override
        public long userId() {
            return attributes.userId();
        }

        /// Returns the numeric group identifier stored by the AR header.
        @Override
        public long groupId() {
            return attributes.groupId();
        }

        /// Returns the POSIX mode bits stored by the AR header.
        @Override
        public int mode() {
            return attributes.mode();
        }

        /// Returns the last modified timestamp.
        @Override
        public FileTime lastModifiedTime() {
            return attributes.lastModifiedTime();
        }

        /// Returns the last access timestamp.
        @Override
        public FileTime lastAccessTime() {
            return attributes.lastModifiedTime();
        }

        /// Returns the creation timestamp.
        @Override
        public FileTime creationTime() {
            return attributes.lastModifiedTime();
        }

        /// Returns whether this pending member is a regular file.
        @Override
        public boolean isRegularFile() {
            return ArPosixSupport.isRegularFile(attributes.mode());
        }

        /// Returns whether this pending member is a directory.
        @Override
        public boolean isDirectory() {
            return ArPosixSupport.isDirectory(attributes.mode());
        }

        /// Returns whether this pending member is a symbolic link.
        @Override
        public boolean isSymbolicLink() {
            return ArPosixSupport.isSymbolicLink(attributes.mode());
        }

        /// Returns whether this pending member has another file type.
        @Override
        public boolean isOther() {
            return ArPosixSupport.isOther(attributes.mode());
        }

        /// Returns the pending member data size.
        @Override
        public long size() {
            return attributes.size();
        }

        /// Returns no stable file key for a pending streaming member.
        @Override
        public @Nullable Object fileKey() {
            return null;
        }
    }

    /// Stages one unknown-size member body before committing its AR header and body.
    @NotNullByDefault
    private final class StoredMemberBodyOutputStream extends OutputStream {
        /// The member metadata to commit.
        private final PendingMember member;

        /// The stored body owned by this stream until commit finishes.
        private final ArkivoStoredContent content;

        /// The output stream opened over the stored body.
        private final OutputStream storageOutput;

        /// The number of staged body bytes.
        private long written;

        /// Whether this stream has been closed.
        private boolean closed;

        /// Whether this stream has finished its commit attempt.
        private boolean finishedBody;

        /// Creates a stored member body output stream.
        private StoredMemberBodyOutputStream(PendingMember member) throws IOException {
            this.member = member;
            this.content = bodyStorage.createContent(member.path, ArkivoEditStorage.UNKNOWN_SIZE);
            try {
                this.storageOutput = Channels.newOutputStream(content.openChannel(Set.of(
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                )));
            } catch (IOException | RuntimeException | Error exception) {
                releaseBody(content);
                throw exception;
            }
        }

        /// Writes one body byte.
        @Override
        public void write(int value) throws IOException {
            ensureWritable();
            if (written == MAX_MEMBER_SIZE) {
                throw memberTooLarge();
            }
            storageOutput.write(value);
            written++;
        }

        /// Writes body bytes.
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            ensureWritable();
            if (length > MAX_MEMBER_SIZE - written) {
                throw memberTooLarge();
            }
            storageOutput.write(bytes, offset, length);
            written += length;
        }

        /// Flushes staged member bytes.
        @Override
        public void flush() throws IOException {
            ensureWritable();
            storageOutput.flush();
        }

        /// Closes this body stream and commits its member.
        @Override
        public void close() throws IOException {
            if (finishedBody) {
                return;
            }
            closed = true;
            @Nullable IOException failure = null;
            try {
                storageOutput.close();
                long size = content.size();
                try (SeekableByteChannel input = content.openChannel(Set.of(StandardOpenOption.READ))) {
                    writeStoredMember(member, input, size);
                }
            } catch (IOException exception) {
                failure = exception;
            } finally {
                finishedBody = true;
                releaseBody(content);
                if (currentBody == this) {
                    currentBody = null;
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        /// Creates an exception for a body larger than the AR size field.
        private IOException memberTooLarge() {
            return new IOException("AR member body exceeds the maximum representable size for " + member.path);
        }

        /// Ensures this body stream can still accept bytes.
        private void ensureWritable() throws IOException {
            if (!open) {
                throw new IOException("AR streaming writer is closed");
            }
            if (closed) {
                throw new IOException("AR member body stream is closed");
            }
        }
    }

    /// Writes a known-size member body directly to the archive output stream.
    @NotNullByDefault
    private final class DirectMemberBodyOutputStream extends OutputStream {
        /// The member metadata being written.
        private final PendingMember member;

        /// The expected logical body size.
        private final long expectedSize;

        /// The complete stored member size, including any inline name prefix.
        private final long storedSize;

        /// The number of logical body bytes written so far.
        private long written;

        /// Whether this stream has been closed.
        private boolean closed;

        /// Whether this stream has finished its member successfully.
        private boolean committed;

        /// Creates a direct member body output stream.
        private DirectMemberBodyOutputStream(PendingMember member, MemberLayout layout) {
            this.member = Objects.requireNonNull(member, "member");
            this.expectedSize = layout.bodySize();
            this.storedSize = layout.storedSize();
        }

        /// Writes one body byte directly to the archive output.
        @Override
        public void write(int value) throws IOException {
            ensureWritable();
            if (written == expectedSize) {
                throw memberTooLarge();
            }
            output.write(value);
            written++;
        }

        /// Writes body bytes directly to the archive output.
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            ensureWritable();
            if (length > expectedSize - written) {
                throw memberTooLarge();
            }
            output.write(bytes, offset, length);
            written += length;
        }

        /// Closes this body stream and writes member padding after an exact-size body.
        @Override
        public void close() throws IOException {
            if (committed) {
                return;
            }
            closed = true;
            if (written != expectedSize) {
                throw new IOException("AR member body size does not match configured size for " + member.path);
            }
            writeMemberPadding(storedSize);
            committed = true;
            if (currentBody == this) {
                currentBody = null;
            }
        }

        /// Creates an exception for a body larger than the configured size.
        private IOException memberTooLarge() {
            return new IOException("AR member body exceeds configured size for " + member.path);
        }

        /// Ensures this body stream can still accept bytes.
        private void ensureWritable() throws IOException {
            if (!open) {
                throw new IOException("AR streaming writer is closed");
            }
            if (closed) {
                throw new IOException("AR member body stream is closed");
            }
        }
    }
}
