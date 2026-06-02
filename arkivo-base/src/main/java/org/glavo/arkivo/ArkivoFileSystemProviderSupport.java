package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.spi.FileSystemProvider;

/// Provides a common base class for archive-backed file system providers.
@NotNullByDefault
public abstract class ArkivoFileSystemProviderSupport extends FileSystemProvider {
    /// Creates a provider support instance.
    protected ArkivoFileSystemProviderSupport() {
    }
}
