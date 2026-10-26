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
        return totalBytes == null && (status == DownloadStatus.DOWNLOADING || status == DownloadStatus.PAUSING);
    }

    public boolean sizeUnknown() {
        return totalBytes == null;
    }

    public double progress() {
        if (totalBytes == null || totalBytes <= 0) {
            return status == DownloadStatus.COMPLETED ? 1.0 : 0.0;
        }
        return Math.min(1.0, (double) transferredBytes / totalBytes);
    }
}
