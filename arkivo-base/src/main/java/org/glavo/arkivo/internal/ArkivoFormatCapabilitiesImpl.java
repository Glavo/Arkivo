// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.internal;

import org.glavo.arkivo.ArkivoFormatCapabilities;
import org.jetbrains.annotations.NotNullByDefault;

/// Stores archive format capability flags.
@NotNullByDefault
public final class ArkivoFormatCapabilitiesImpl implements ArkivoFormatCapabilities {
    /// Whether the format supports sequential reading.
    private final boolean streamingRead;

    /// Whether the format supports sequential writing.
    private final boolean streamingWrite;

    /// Whether the format supports random-access reading.
    private final boolean randomRead;

    /// Whether the format supports random-access writing.
    private final boolean randomWrite;

    /// Whether the format supports editing an existing archive.
    private final boolean editing;

    /// Whether the format supports a NIO file system view.
    private final boolean fileSystem;

    /// Whether the format supports reading encrypted archives or entries.
    private final boolean encryptedRead;

    /// Whether the format supports writing encrypted archives or entries.
    private final boolean encryptedWrite;

    /// Whether the format supports reading split or multi-volume archives.
    private final boolean splitRead;

    /// Whether the format supports writing split or multi-volume archives.
    private final boolean splitWrite;

    /// Creates a capability set implementation.
    public ArkivoFormatCapabilitiesImpl(
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
        this.streamingRead = streamingRead;
        this.streamingWrite = streamingWrite;
        this.randomRead = randomRead;
        this.randomWrite = randomWrite;
        this.editing = editing;
        this.fileSystem = fileSystem;
        this.encryptedRead = encryptedRead;
        this.encryptedWrite = encryptedWrite;
        this.splitRead = splitRead;
        this.splitWrite = splitWrite;
    }

    /// Returns whether the format supports sequential reading.
    @Override
    public boolean streamingRead() {
        return streamingRead;
    }

    /// Returns whether the format supports sequential writing.
    @Override
    public boolean streamingWrite() {
        return streamingWrite;
    }

    /// Returns whether the format supports random-access reading.
    @Override
    public boolean randomRead() {
        return randomRead;
    }

    /// Returns whether the format supports random-access writing.
    @Override
    public boolean randomWrite() {
        return randomWrite;
    }

    /// Returns whether the format supports editing an existing archive.
    @Override
    public boolean editing() {
        return editing;
    }

    /// Returns whether the format supports a NIO file system view.
    @Override
    public boolean fileSystem() {
        return fileSystem;
    }

    /// Returns whether the format supports reading encrypted archives or entries.
    @Override
    public boolean encryptedRead() {
        return encryptedRead;
    }

    /// Returns whether the format supports writing encrypted archives or entries.
    @Override
    public boolean encryptedWrite() {
        return encryptedWrite;
    }

    /// Returns whether the format supports reading split or multi-volume archives.
    @Override
    public boolean splitRead() {
        return splitRead;
    }

    /// Returns whether the format supports writing split or multi-volume archives.
    @Override
    public boolean splitWrite() {
        return splitWrite;
    }
}
