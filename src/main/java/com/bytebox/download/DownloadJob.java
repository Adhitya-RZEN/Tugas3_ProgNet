package com.bytebox.download;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Future;

final class DownloadJob {
    final UUID id;
    final URI source;
    volatile String fileName;
    volatile Path destination;
    final Path partialFile;
    final Instant createdAt;

    volatile DownloadStatus status;
    volatile long transferredBytes;
    volatile Long totalBytes;
    volatile double bytesPerSecond;
    volatile String errorMessage;
    volatile boolean restartRequired;
    volatile String etag;
    volatile String lastModified;
    volatile boolean pauseRequested;
    volatile boolean cancelRequested;
    volatile boolean restartFromZero;
    volatile InputStream activeStream;
    volatile HttpURLConnection activeConnection;
    volatile Thread workerThread;
    volatile Future<?> workerFuture;
    long lastProgressBytes;
    long lastProgressNanos;
    long lastPersistNanos;

    DownloadJob(UUID id, URI source, String fileName, Path destination, Instant createdAt) {
        this.id = id;
        this.source = source;
        this.fileName = fileName;
        this.destination = destination.toAbsolutePath().normalize();
        this.partialFile = this.destination.getParent().resolve(".bytebox-" + id + ".part");
        this.createdAt = createdAt;
        this.status = DownloadStatus.QUEUED;
        this.lastProgressNanos = System.nanoTime();
    }

    DownloadJob(StoredDownload stored) {
        this(stored.id(), stored.source(), stored.fileName(), stored.destination(), stored.createdAt());
        this.transferredBytes = stored.transferredBytes();
        this.totalBytes = stored.totalBytes();
        this.status = stored.status();
        this.errorMessage = stored.errorMessage();
        this.restartRequired = stored.restartRequired();
        this.etag = stored.etag();
        this.lastModified = stored.lastModified();
    }

    DownloadSnapshot snapshot() {
        return new DownloadSnapshot(id, source, fileName, destination, partialFile, transferredBytes,
                totalBytes, bytesPerSecond, createdAt, status, errorMessage, restartRequired);
    }

    StoredDownload stored() {
        return new StoredDownload(id, source, fileName, destination, transferredBytes, totalBytes,
                createdAt, status, errorMessage, restartRequired, etag, lastModified);
    }
}
