package com.bytebox.download;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

public record DownloadSnapshot(
        UUID id,
        URI source,
        String fileName,
        Path destination,
        Path partialFile,
        long transferredBytes,
        Long totalBytes,
        double bytesPerSecond,
        Instant createdAt,
        DownloadStatus status,
        String errorMessage,
        boolean restartRequired) {

    public boolean indeterminate() {
        return totalBytes == null || totalBytes <= 0;
    }

    public double progress() {
        if (indeterminate()) {
            return -1.0;
        }
        return Math.min(1.0, (double) transferredBytes / totalBytes);
    }
}
