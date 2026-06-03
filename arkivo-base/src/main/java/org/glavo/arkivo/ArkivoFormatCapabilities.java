// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.glavo.arkivo.internal.ArkivoFormatCapabilitiesImpl;
import org.jetbrains.annotations.NotNullByDefault;

/// Describes which operations an archive format supports.
@NotNullByDefault
public sealed interface ArkivoFormatCapabilities permits ArkivoFormatCapabilitiesImpl {
    /// Creates a builder for a capability set.
    static Builder builder() {
        return new Builder();
    }

    /// Creates a capability set.
    static ArkivoFormatCapabilities of(
            boolean streamingRead,
            boolean streamingWrite,
            boolean randomRead,
            boolean randomWrite,
            boolean editing,
            boolean fileSystem,
            boolean encryptedRead,
            boolean encryptedWrite,
            boolean splitRead,
            boolean splitWrite
    ) {
        return new ArkivoFormatCapabilitiesImpl(
                streamingRead,
                streamingWrite,
                randomRead,
                randomWrite,
                editing,
                fileSystem,
                encryptedRead,
                encryptedWrite,
                splitRead,
                splitWrite
        );
    }

    /// Returns whether the format supports sequential reading.
    boolean streamingRead();

    /// Returns whether the format supports sequential writing.
    boolean streamingWrite();

    /// Returns whether the format supports random-access reading.
    boolean randomRead();

    /// Returns whether the format supports random-access writing.
    boolean randomWrite();

    /// Returns whether the format supports editing an existing archive.
    boolean editing();

    /// Returns whether the format supports a NIO file system view.
    boolean fileSystem();

    /// Returns whether the format supports reading encrypted archives or entries.
    boolean encryptedRead();

    /// Returns whether the format supports writing encrypted archives or entries.
    boolean encryptedWrite();

    /// Returns whether the format supports reading split or multi-volume archives.
    boolean splitRead();

    /// Returns whether the format supports writing split or multi-volume archives.
    boolean splitWrite();

    /// Builds `ArkivoFormatCapabilities` instances.
    @NotNullByDefault
    final class Builder {
        /// Whether the format supports sequential reading.
        private boolean streamingRead;

        /// Whether the format supports sequential writing.
        private boolean streamingWrite;

        /// Whether the format supports random-access reading.
        private boolean randomRead;

        /// Whether the format supports random-access writing.
        private boolean randomWrite;

        /// Whether the format supports editing an existing archive.
        private boolean editing;

        /// Whether the format supports a NIO file system view.
        private boolean fileSystem;

        /// Whether the format supports reading encrypted archives or entries.
        private boolean encryptedRead;

        /// Whether the format supports writing encrypted archives or entries.
        private boolean encryptedWrite;

        /// Whether the format supports reading split or multi-volume archives.
        private boolean splitRead;

        /// Whether the format supports writing split or multi-volume archives.
        private boolean splitWrite;

        /// Creates a capability builder.
        public Builder() {
        }

        /// Sets whether the format supports sequential reading.
        public Builder streamingRead(boolean streamingRead) {
            this.streamingRead = streamingRead;
            return this;
        }

        /// Sets whether the format supports sequential writing.
        public Builder streamingWrite(boolean streamingWrite) {
            this.streamingWrite = streamingWrite;
            return this;
        }

        /// Sets whether the format supports random-access reading.
        public Builder randomRead(boolean randomRead) {
            this.randomRead = randomRead;
            return this;
        }

        /// Sets whether the format supports random-access writing.
        public Builder randomWrite(boolean randomWrite) {
            this.randomWrite = randomWrite;
            return this;
        }

        /// Sets whether the format supports editing an existing archive.
        public Builder editing(boolean editing) {
            this.editing = editing;
            return this;
        }

        /// Sets whether the format supports a NIO file system view.
        public Builder fileSystem(boolean fileSystem) {
            this.fileSystem = fileSystem;
            return this;
        }

        /// Sets whether the format supports reading encrypted archives or entries.
        public Builder encryptedRead(boolean encryptedRead) {
            this.encryptedRead = encryptedRead;
            return this;
        }

        /// Sets whether the format supports writing encrypted archives or entries.
        public Builder encryptedWrite(boolean encryptedWrite) {
            this.encryptedWrite = encryptedWrite;
            return this;
        }

        /// Sets whether the format supports reading split or multi-volume archives.
        public Builder splitRead(boolean splitRead) {
            this.splitRead = splitRead;
            return this;
        }

        /// Sets whether the format supports writing split or multi-volume archives.
        public Builder splitWrite(boolean splitWrite) {
            this.splitWrite = splitWrite;
            return this;
        }

        /// Builds the capability set.
        public ArkivoFormatCapabilities build() {
            return ArkivoFormatCapabilities.of(
                    streamingRead,
                    streamingWrite,
                    randomRead,
                    randomWrite,
                    editing,
                    fileSystem,
                    encryptedRead,
                    encryptedWrite,
                    splitRead,
                    splitWrite
            );
        }
    }
}
