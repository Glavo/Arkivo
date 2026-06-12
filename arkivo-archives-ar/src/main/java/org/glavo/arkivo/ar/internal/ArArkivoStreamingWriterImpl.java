// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.ar.internal;

import org.glavo.arkivo.ar.ArArkivoEntryAttributeView;
import org.glavo.arkivo.ar.ArArkivoEntryAttributes;
import org.glavo.arkivo.ar.ArArkivoStreamingWriter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.util.Objects;

/// Implements the public forward-only AR streaming writer API.
@NotNullByDefault
public final class ArArkivoStreamingWriterImpl extends ArArkivoStreamingWriter {
    /// The global AR archive signature.
    private static final byte @Unmodifiable [] GLOBAL_HEADER = "!<arch>\n".getBytes(StandardCharsets.US_ASCII);

    /// The AR member header trailer.
    private static final byte @Unmodifiable [] MEMBER_TRAILER = new byte[]{'`', '\n'};

    /// The default regular member mode.
    private static final int DEFAULT_FILE_MODE = 0100644;

    /// The backing archive output stream.
    private final OutputStream output;

    /// Whether the global AR archive header has been written.
    private boolean globalHeaderWritten;

    /// Whether this writer still accepts new members.
    private boolean open = true;

    /// Whether the backing archive output stream has been closed.
    private boolean outputClosed;

    /// The pending member that has not yet been committed.
    private @Nullable PendingMember pendingMember;

    /// The open member body stream that will commit a file member when closed.
    private @Nullable MemberBodyOutputStream currentBody;

    /// Creates a streaming AR writer.
    public ArArkivoStreamingWriterImpl(OutputStream output) {
        this.output = Objects.requireNonNull(output, "output");
    }

    /// Begins a pending regular file AR member for the given logical archive path.
    @Override
    public void beginFile(String path) throws IOException {
        ensureOpen();
        if (pendingMember != null) {
            throw new IllegalStateException("An AR streaming member is already pending");
        }
        if (currentBody != null) {
            throw new IllegalStateException("An AR streaming member body is still open");
        }
        pendingMember = new PendingMember(memberPathText(path));
    }

    /// AR archives do not represent directory entries.
    @Override
    public void beginDirectory(String path) throws IOException {
        ensureOpen();
        Objects.requireNonNull(path, "path");
        throw new UnsupportedOperationException("AR streaming writer does not support directory members");
    }

    /// AR archives do not represent symbolic link entries.
    @Override
    public void beginSymbolicLink(String path, String target) throws IOException {
        ensureOpen();
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(target, "target");
        throw new UnsupportedOperationException("AR streaming writer does not support symbolic link members");
    }

    /// Returns an attribute view used to configure the current pending entry before it is committed.
    @Override
    public <V extends FileAttributeView> @Nullable V attributeView(Class<V> type) {
        Objects.requireNonNull(type, "type");
        PendingMember member = requirePendingMember();
        if (type == BasicFileAttributeView.class || type == ArArkivoEntryAttributeView.class) {
            return type.cast(member.attributes);
        }
        return null;
    }

    /// Commits the current pending member as an empty regular file member.
    @Override
    public void endEntry() throws IOException {
        ensureOpen();
        PendingMember member = requirePendingMember();
        member.ensurePending();
        pendingMember = null;
        writeMember(member, new byte[0]);
    }

    /// Opens a writable channel for the current pending member and commits it when the channel is closed.
    @Override
    public WritableByteChannel openChannel() throws IOException {
        return Channels.newChannel(openOutputStream());
    }

    /// Opens an output stream for the current pending member and commits it when the stream is closed.
    @Override
    public OutputStream openOutputStream() throws IOException {
        ensureOpen();
        PendingMember member = requirePendingMember();
        member.ensurePending();
        pendingMember = null;
        MemberBodyOutputStream body = new MemberBodyOutputStream(member);
        currentBody = body;
        return body;
    }

    /// Closes this streaming writer and finishes the AR archive stream.
    @Override
    public void close() throws IOException {
        if (!open && outputClosed) {
            return;
        }

        IOException failure = null;
        MemberBodyOutputStream body = currentBody;
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
                writeMember(member, new byte[0]);
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

    /// Writes one AR member, including any inline BSD long name prefix.
    private void writeMember(PendingMember member, byte[] body) throws IOException {
        member.committed = true;
        ensureGlobalHeader();
        byte[] pathBytes = member.path.getBytes(StandardCharsets.UTF_8);
        String identifier = standardIdentifier(member.path);
        byte[] storedBody = body;
        if (identifier == null) {
            identifier = "#1/" + pathBytes.length;
            storedBody = new byte[pathBytes.length + body.length];
            System.arraycopy(pathBytes, 0, storedBody, 0, pathBytes.length);
            System.arraycopy(body, 0, storedBody, pathBytes.length, body.length);
        }

        PendingArEntryAttributeView attributes = member.attributes;
        writeMemberHeader(
                identifier,
                timestampSeconds(attributes.lastModifiedTime()),
                attributes.userId(),
                attributes.groupId(),
                attributes.mode(),
                storedBody.length
        );
        output.write(storedBody);
        if ((storedBody.length & 1) != 0) {
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
            int size
    ) throws IOException {
        writeField(identifier, 16, "identifier");
        writeField(Long.toString(timestamp), 12, "timestamp");
        writeField(Long.toString(userId), 6, "user id");
        writeField(Long.toString(groupId), 6, "group id");
        writeField(Integer.toOctalString(mode), 8, "mode");
        writeField(Integer.toString(size), 10, "size");
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

    /// Ensures this writer is still open.
    private void ensureOpen() throws IOException {
        if (!open) {
            throw new IOException("AR streaming writer is closed");
        }
    }

    /// Stores a pending AR member.
    private final class PendingMember {
        /// The normalized logical member path.
        private final String path;

        /// The pending AR member attribute view.
        private final PendingArEntryAttributeView attributes;

        /// Whether this member has already been committed.
        private boolean committed;

        /// Creates a pending AR member.
        private PendingMember(String path) {
            this.path = Objects.requireNonNull(path, "path");
            this.attributes = new PendingArEntryAttributeView(this);
        }

        /// Ensures this member can still be configured.
        private void ensurePending() {
            if (committed || pendingMember != this) {
                throw new IllegalStateException("AR streaming member has already been committed");
            }
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
        private int mode = DEFAULT_FILE_MODE;

        /// Creates a pending AR member attribute view.
        private PendingArEntryAttributeView(PendingMember member) {
            this.member = Objects.requireNonNull(member, "member");
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
            this.mode = mode;
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
            return true;
        }

        /// Returns whether this pending member is a directory.
        @Override
        public boolean isDirectory() {
            return false;
        }

        /// Returns whether this pending member is a symbolic link.
        @Override
        public boolean isSymbolicLink() {
            return false;
        }

        /// Returns whether this pending member has another file type.
        @Override
        public boolean isOther() {
            return false;
        }

        /// Returns the pending member data size.
        @Override
        public long size() {
            return 0L;
        }

        /// Returns no stable file key for a pending streaming member.
        @Override
        public @Nullable Object fileKey() {
            return null;
        }
    }

    /// Buffers one member body before committing its AR header and body.
    private final class MemberBodyOutputStream extends ByteArrayOutputStream {
        /// The member metadata to commit.
        private final PendingMember member;

        /// Whether this stream has been closed.
        private boolean closed;

        /// Whether this stream has committed its body successfully.
        private boolean committed;

        /// Creates a member body output stream.
        private MemberBodyOutputStream(PendingMember member) {
            this.member = member;
        }

        /// Writes one body byte.
        @Override
        public void write(int value) {
            ensureWritable();
            super.write(value);
        }

        /// Writes body bytes.
        @Override
        public void write(byte[] bytes, int offset, int length) {
            ensureWritable();
            super.write(bytes, offset, length);
        }

        /// Closes this body stream and commits its member.
        @Override
        public void close() throws IOException {
            if (committed) {
                return;
            }
            closed = true;
            writeMember(member, toByteArray());
            committed = true;
            if (currentBody == this) {
                currentBody = null;
            }
        }

        /// Ensures this body stream can still accept bytes.
        private void ensureWritable() {
            if (!open) {
                throw new IllegalStateException("AR streaming writer is closed");
            }
            if (closed) {
                throw new IllegalStateException("AR member body stream is closed");
            }
        }
    }
}
