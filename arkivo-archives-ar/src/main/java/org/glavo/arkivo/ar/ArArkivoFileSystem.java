// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.ar;

import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.ar.internal.ArArkivoFileSystemImpl;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/// Opens AR archives as NIO file systems.
///
/// Supplying `READ` and `WRITE` through `ArkivoFileSystem.OPEN_OPTIONS` opens a complete rewrite update session.
/// Closing a changed update session atomically replaces the source by default; `ArkivoFileSystem.COMMIT_TARGET` can
/// select another publication policy. AR symbol indexes are omitted from rewritten archives because member offsets
/// change; callers that require a linker index must rebuild it with a platform tool such as `ranlib`.
@NotNullByDefault
public abstract sealed class ArArkivoFileSystem extends ArkivoFileSystem permits ArArkivoFileSystemImpl {
    /// Creates an AR archive file system base instance.
    protected ArArkivoFileSystem(ArkivoFileSystemThreadSafety threadSafety) {
        super(threadSafety);
    }

    /// Opens an AR archive file system.
    public static ArArkivoFileSystem open(Path path) throws IOException {
        return open(path, Map.of());
    }

    /// Opens an AR archive file system with environment options.
    ///
    /// `READ` and `WRITE` select update mode. `CREATE` additionally allows a missing source archive.
    public static ArArkivoFileSystem open(Path path, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(environment, "environment");
        return ArArkivoFileSystemProvider.instance().newFileSystem(path, environment);
    }
}
