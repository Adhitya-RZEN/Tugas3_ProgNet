package com.bytebox.download;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

record StoredDownload(
        UUID id,
        URI source,
        String fileName,
        Path destination,
        long transferredBytes,
        Long totalBytes,
        Instant createdAt,
        DownloadStatus status,
        String errorMessage,
        boolean restartRequired,
        String etag,
        String lastModified) {
}
