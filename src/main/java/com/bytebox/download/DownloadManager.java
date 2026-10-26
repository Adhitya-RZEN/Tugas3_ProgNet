package com.bytebox.download;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class DownloadManager implements AutoCloseable {
    public static final int DEFAULT_CONCURRENT_DOWNLOADS = 3;
    private static final long UI_UPDATE_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(200);
    private static final long PERSIST_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(900);

    private final Object lock = new Object();
    private final Map<UUID, DownloadJob> jobs = new LinkedHashMap<>();
    private final Set<UUID> activeJobs = new java.util.HashSet<>();
    private final DownloadRepository repository;
    private final ExecutorService workers;
    private final ExecutorService control;
    private final int maxConcurrentDownloads;
    private volatile Consumer<List<DownloadSnapshot>> listener = ignored -> { };
    private volatile Path downloadDirectory;
    private volatile String persistenceError;
    private boolean closed;
    private long lastUiUpdateNanos;

    public DownloadManager(Path metadataDirectory, Path defaultDownloadDirectory) throws IOException {
        this(metadataDirectory, defaultDownloadDirectory, DEFAULT_CONCURRENT_DOWNLOADS);
    }

    public DownloadManager(Path metadataDirectory, Path defaultDownloadDirectory, int maxConcurrentDownloads) throws IOException {
        if (maxConcurrentDownloads < 1) {
            throw new IllegalArgumentException("At least one concurrent download slot is required");
        }
        this.maxConcurrentDownloads = maxConcurrentDownloads;
        this.repository = new DownloadRepository(metadataDirectory);
        this.downloadDirectory = repository.loadDownloadDirectory(defaultDownloadDirectory);
        this.workers = Executors.newFixedThreadPool(maxConcurrentDownloads, namedThreads("bytebox-download"));
        this.control = Executors.newSingleThreadExecutor(namedThreads("bytebox-storage"));

        for (StoredDownload stored : repository.loadAll()) {
            DownloadJob job = new DownloadJob(stored);
            jobs.put(job.id, job);
        }
        for (DownloadJob job : snapshotJobsInternal()) {
            persistAsync(job);
        }
        scheduleEligible();
    }

    public void setListener(Consumer<List<DownloadSnapshot>> listener) {
        this.listener = listener == null ? ignored -> { } : listener;
        notifyListener();
    }

    public List<DownloadSnapshot> snapshots() {
        synchronized (lock) {
            return snapshotJobsInternal().stream().map(DownloadJob::snapshot).toList();
        }
    }

    public Path downloadDirectory() {
        return downloadDirectory;
    }

    public int activeConnections() {
        synchronized (lock) {
            return activeJobs.size();
        }
    }

    public String persistenceError() {
        return persistenceError;
    }

    public CompletableFuture<UUID> add(String rawUrl) {
        URI source;
        try {
            source = validateHttpUri(rawUrl);
        } catch (IllegalArgumentException exception) {
            return CompletableFuture.failedFuture(exception);
        }

        CompletableFuture<UUID> result = new CompletableFuture<>();
        executeControl(() -> {
            try {
                Path directory = downloadDirectory.toAbsolutePath().normalize();
                Files.createDirectories(directory);
                Collection<Path> reserved;
                synchronized (lock) {
                    ensureOpen();
                    reserved = jobs.values().stream().map(job -> job.destination).toList();
                }
                Path destination = FileNameResolver.allocate(source, directory, reserved);
                DownloadJob job = new DownloadJob(UUID.randomUUID(), source,
                        destination.getFileName().toString(), destination, Instant.now());
                synchronized (lock) {
                    ensureOpen();
                    jobs.put(job.id, job);
                }
                repository.save(job.stored());
                result.complete(job.id);
                scheduleEligible();
                notifyListener();
            } catch (Exception exception) {
                result.completeExceptionally(exception);
            }
        }, result);
        return result;
    }

    public void pause(UUID id) {
        DownloadJob job;
        Thread worker;
        InputStream input;
        HttpURLConnection connection;
        synchronized (lock) {
            job = requireJob(id);
            if (job.status == DownloadStatus.QUEUED) {
                transition(job, DownloadStatus.PAUSED);
                job.errorMessage = null;
                persistAsync(job);
                notifyListener();
                return;
            }
            if (job.status != DownloadStatus.DOWNLOADING) {
                return;
            }
            transition(job, DownloadStatus.PAUSING);
            job.pauseRequested = true;
            worker = job.workerThread;
            input = job.activeStream;
            connection = job.activeConnection;
        }
        if (worker != null) {
            worker.interrupt();
        }
        closeTransferAsync(input, connection);
        persistAsync(job);
        notifyListener();
    }

    public void resume(UUID id) {
        DownloadJob job;
        synchronized (lock) {
            job = requireJob(id);
            if (job.restartRequired) {
                throw new IllegalStateException("The server did not confirm a safe resume. Restart from zero instead.");
            }
            if (job.status != DownloadStatus.PAUSED && job.status != DownloadStatus.FAILED) {
                return;
            }
            job.pauseRequested = false;
            job.cancelRequested = false;
            job.errorMessage = null;
            transition(job, DownloadStatus.QUEUED);
        }
        persistAsync(job);
        scheduleEligible();
        notifyListener();
    }

    public void restartFromZero(UUID id) {
        DownloadJob job;
        synchronized (lock) {
            job = requireJob(id);
            if (!job.restartRequired || job.status != DownloadStatus.FAILED) {
                throw new IllegalStateException("This download does not require a restart from zero");
            }
            job.restartFromZero = true;
            job.pauseRequested = false;
            job.cancelRequested = false;
            job.transferredBytes = 0;
            job.totalBytes = null;
            job.etag = null;
            job.lastModified = null;
            job.restartRequired = false;
            job.errorMessage = null;
            transition(job, DownloadStatus.QUEUED);
        }
        persistAsync(job);
        scheduleEligible();
        notifyListener();
    }

    public void cancel(UUID id) {
        DownloadJob job;
        Thread worker;
        InputStream input;
        HttpURLConnection connection;
        boolean workerActive;
        synchronized (lock) {
            job = requireJob(id);
            if (job.status.isTerminal() || job.status == DownloadStatus.CANCELLING) {
                return;
            }
            workerActive = activeJobs.contains(id);
            transition(job, DownloadStatus.CANCELLING);
            job.cancelRequested = true;
            job.pauseRequested = false;
            worker = job.workerThread;
            input = job.activeStream;
            connection = job.activeConnection;
        }
        if (worker != null) {
            worker.interrupt();
        }
        closeTransferAsync(input, connection);
        if (!workerActive) {
            executeControl(() -> finishQueuedCancellation(job), null);
        }
        persistAsync(job);
        notifyListener();
    }

    public CompletableFuture<Path> setDownloadDirectory(Path directory) {
        Path normalized = directory.toAbsolutePath().normalize();
        CompletableFuture<Path> result = new CompletableFuture<>();
        executeControl(() -> {
            try {
                Files.createDirectories(normalized);
                repository.saveDownloadDirectory(normalized);
                downloadDirectory = normalized;
                result.complete(normalized);
                notifyListener();
            } catch (Exception exception) {
                result.completeExceptionally(exception);
            }
        }, result);
        return result;
    }

    void workerStarted(DownloadJob job) {
        job.workerThread = Thread.currentThread();
    }

    boolean shouldStop(DownloadJob job) {
        return job.pauseRequested || job.cancelRequested || Thread.currentThread().isInterrupted();
    }

    void updateValidators(DownloadJob job, String etag, String lastModified) {
        synchronized (lock) {
            if (etag != null && !etag.isBlank()) {
                job.etag = etag;
            }
            if (lastModified != null && !lastModified.isBlank()) {
                job.lastModified = lastModified;
            }
        }
    }

    void applyContentDispositionFilename(DownloadJob job, String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return;
        }
        boolean changedName = false;
        synchronized (lock) {
            if (job.transferredBytes > 0 || job.cancelRequested
                    || (job.status != DownloadStatus.DOWNLOADING && job.status != DownloadStatus.PAUSING)) {
                return;
            }
            Collection<Path> reserved = jobs.values().stream()
                    .filter(candidate -> candidate != job)
                    .map(candidate -> candidate.destination)
                    .toList();
            Path destination = FileNameResolver.allocate(fileName, job.destination.getParent(), reserved);
            String resolvedName = destination.getFileName().toString();
            if (!destination.equals(job.destination) || !resolvedName.equals(job.fileName)) {
                job.destination = destination;
                job.fileName = resolvedName;
                changedName = true;
            }
        }
        if (changedName) {
            persistAsync(job);
            notifyListener();
        }
    }

    void setTotalBytes(DownloadJob job, Long totalBytes) {
        synchronized (lock) {
            job.totalBytes = totalBytes;
        }
    }

    void reportProgress(DownloadJob job, long transferredBytes) {
        boolean notify;
        boolean persist;
        long now = System.nanoTime();
        synchronized (lock) {
            long elapsed = now - job.lastProgressNanos;
            if (elapsed >= TimeUnit.MILLISECONDS.toNanos(300)) {
                long delta = Math.max(0, transferredBytes - job.lastProgressBytes);
                job.bytesPerSecond = elapsed == 0 ? 0 : (delta * 1_000_000_000.0 / elapsed);
                job.lastProgressNanos = now;
                job.lastProgressBytes = transferredBytes;
            }
            job.transferredBytes = transferredBytes;
            notify = now - lastUiUpdateNanos >= UI_UPDATE_INTERVAL_NANOS;
            if (notify) {
                lastUiUpdateNanos = now;
            }
            persist = now - job.lastPersistNanos >= PERSIST_INTERVAL_NANOS;
            if (persist) {
                job.lastPersistNanos = now;
            }
        }
        if (persist) {
            persistAsync(job);
        }
        if (notify) {
            notifyListener();
        }
    }

    void workerPaused(DownloadJob job, long actualBytes) {
        synchronized (lock) {
            job.transferredBytes = actualBytes;
            job.bytesPerSecond = 0;
            job.workerThread = null;
            job.activeStream = null;
            if (job.status == DownloadStatus.PAUSING) {
                transition(job, DownloadStatus.PAUSED);
            } else if (job.status == DownloadStatus.DOWNLOADING) {
                transition(job, DownloadStatus.PAUSED);
            }
            activeJobs.remove(job.id);
        }
        persistAsync(job);
        notifyListener();
        scheduleEligible();
    }

    void workerCancelled(DownloadJob job, long remainingBytes, String cleanupError) {
        synchronized (lock) {
            job.transferredBytes = remainingBytes;
            job.bytesPerSecond = 0;
            job.workerThread = null;
            job.activeStream = null;
            job.errorMessage = cleanupError;
            if (job.status == DownloadStatus.CANCELLING) {
                transition(job, DownloadStatus.CANCELLED);
            }
            activeJobs.remove(job.id);
        }
        persistAsync(job);
        notifyListener();
        scheduleEligible();
    }

    void workerCompleted(DownloadJob job, long bytes) {
        synchronized (lock) {
            job.transferredBytes = bytes;
            job.bytesPerSecond = 0;
            job.workerThread = null;
            job.activeStream = null;
            job.errorMessage = null;
            job.restartRequired = false;
            job.pauseRequested = false;
            job.cancelRequested = false;
            if (job.status == DownloadStatus.DOWNLOADING || job.status == DownloadStatus.PAUSING
                    || job.status == DownloadStatus.CANCELLING) {
                transition(job, DownloadStatus.COMPLETED);
            }
            activeJobs.remove(job.id);
        }
        persistAsync(job);
        notifyListener();
        scheduleEligible();
    }

    void workerFailed(DownloadJob job, String message, boolean requiresRestart) {
        boolean cancelled = false;
        String cleanupError = null;
        long remainingBytes = job.transferredBytes;
        if (job.cancelRequested) {
            try {
                Files.deleteIfExists(job.partialFile);
                remainingBytes = 0;
            } catch (IOException exception) {
                cleanupError = "Cancelled, but the partial file could not be removed: " + exception.getMessage();
            }
            try {
                remainingBytes = Files.exists(job.partialFile) ? Files.size(job.partialFile) : 0;
            } catch (IOException ignored) {
                // Keep the last recorded count if the partial file cannot be inspected.
            }
            cancelled = true;
        }
        synchronized (lock) {
            job.bytesPerSecond = 0;
            job.workerThread = null;
            job.activeStream = null;
            if (cancelled) {
                job.transferredBytes = remainingBytes;
                job.errorMessage = cleanupError;
                if (job.status == DownloadStatus.CANCELLING) {
                    transition(job, DownloadStatus.CANCELLED);
                }
            } else {
                job.errorMessage = message;
                job.restartRequired = requiresRestart;
                if (job.status == DownloadStatus.DOWNLOADING || job.status == DownloadStatus.PAUSING) {
                    transition(job, DownloadStatus.FAILED);
                }
            }
            if (cancelled) {
                activeJobs.remove(job.id);
            }
        }
        persistAsync(job);
        notifyListener();
        scheduleEligible();
    }

    private void finishQueuedCancellation(DownloadJob job) {
        String error = null;
        long remaining = job.transferredBytes;
        try {
            Files.deleteIfExists(job.partialFile);
            remaining = 0;
        } catch (IOException exception) {
            error = "Cancelled, but the partial file could not be removed: " + exception.getMessage();
        }
        workerCancelled(job, remaining, error);
    }

    private void closeTransferAsync(InputStream input, HttpURLConnection connection) {
        if (input == null && connection == null) {
            return;
        }
        executeControl(() -> {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                    // Closing the stream is a best-effort wake-up for a blocked worker.
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }, null);
    }

    private void scheduleEligible() {
        List<DownloadJob> scheduled = new ArrayList<>();
        synchronized (lock) {
            if (closed) {
                return;
            }
            for (DownloadJob job : jobs.values()) {
                if (activeJobs.size() >= maxConcurrentDownloads) {
                    break;
                }
                if (job.status != DownloadStatus.QUEUED || activeJobs.contains(job.id)) {
                    continue;
                }
                transition(job, DownloadStatus.DOWNLOADING);
                job.errorMessage = null;
                job.pauseRequested = false;
                job.cancelRequested = false;
                job.bytesPerSecond = 0;
                job.lastProgressBytes = job.transferredBytes;
                job.lastProgressNanos = System.nanoTime();
                activeJobs.add(job.id);
                scheduled.add(job);
                job.workerFuture = workers.submit(() -> {
                    try {
                        new HttpDownloadWorker(this).run(job);
                    } finally {
                        workerStopped(job);
                    }
                });
            }
        }
        scheduled.forEach(this::persistAsync);
        if (!scheduled.isEmpty()) {
            notifyListener();
        }
    }

    private void workerStopped(DownloadJob job) {
        boolean shouldPause;
        synchronized (lock) {
            if (job.workerThread == Thread.currentThread()) {
                job.workerThread = null;
            }
            shouldPause = job.status == DownloadStatus.PAUSING && job.pauseRequested && !job.cancelRequested;
            if (!shouldPause) {
                activeJobs.remove(job.id);
            }
        }
        if (!shouldPause) {
            scheduleEligible();
            return;
        }
        long actualBytes = job.transferredBytes;
        try {
            actualBytes = Files.isRegularFile(job.partialFile) ? Files.size(job.partialFile) : 0;
        } catch (IOException ignored) {
            // Preserve the last reported byte count if the filesystem cannot report the partial size.
        }
        workerPaused(job, actualBytes);
    }

    private void persistAsync(DownloadJob job) {
        executeControl(() -> {
            try {
                repository.save(job.stored());
                persistenceError = null;
            } catch (IOException exception) {
                persistenceError = "Could not save download history: " + exception.getMessage();
            }
        }, null);
    }

    private void notifyListener() {
        Consumer<List<DownloadSnapshot>> currentListener = listener;
        try {
            currentListener.accept(snapshots());
        } catch (RuntimeException ignored) {
            // A view refresh must not interrupt the worker that reported it.
        }
    }

    private List<DownloadJob> snapshotJobsInternal() {
        return jobs.values().stream()
                .sorted(Comparator.comparing((DownloadJob job) -> job.createdAt).reversed())
                .toList();
    }

    private DownloadJob requireJob(UUID id) {
        DownloadJob job = jobs.get(id);
        if (job == null) {
            throw new IllegalArgumentException("Download not found: " + id);
        }
        return job;
    }

    private static URI validateHttpUri(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("Enter a URL to download.");
        }
        try {
            URI uri = URI.create(rawUrl.trim()).normalize();
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException("Only HTTP and HTTPS URLs are supported.");
            }
            if (uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("Enter a valid HTTP or HTTPS URL without embedded credentials.");
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            if (exception.getMessage() != null && exception.getMessage().startsWith("Only ")) {
                throw exception;
            }
            if (exception.getMessage() != null && exception.getMessage().startsWith("Enter a valid")) {
                throw exception;
            }
            throw new IllegalArgumentException("Enter a valid HTTP or HTTPS URL.", exception);
        }
    }

    private static ThreadFactory namedThreads(String prefix) {
        return new ThreadFactory() {
            private int sequence;

            @Override
            public synchronized Thread newThread(Runnable task) {
                Thread thread = new Thread(task, prefix + "-" + ++sequence);
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    private void executeControl(Runnable action, CompletableFuture<?> result) {
        try {
            control.execute(action);
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            if (result != null) {
                result.completeExceptionally(new IllegalStateException("Download manager is shutting down", exception));
            }
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Download manager is closed");
        }
    }

    private static void transition(DownloadJob job, DownloadStatus next) {
        if (!job.status.canTransitionTo(next)) {
            throw new IllegalStateException("Invalid download transition: " + job.status + " -> " + next);
        }
        job.status = next;
    }

    @Override
    public void close() {
        List<DownloadJob> active;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            active = jobs.values().stream().filter(job -> activeJobs.contains(job.id)).toList();
            for (DownloadJob job : active) {
                if (job.status == DownloadStatus.DOWNLOADING) {
                    transition(job, DownloadStatus.PAUSING);
                    job.pauseRequested = true;
                }
            }
        }
        for (DownloadJob job : active) {
            Thread worker = job.workerThread;
            if (worker != null) {
                worker.interrupt();
            }
            InputStream input = job.activeStream;
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                    // The worker will also exit on interruption.
                }
            }
            HttpURLConnection connection = job.activeConnection;
            if (connection != null) {
                connection.disconnect();
            }
        }
        workers.shutdown();
        boolean workersStopped;
        try {
            workersStopped = workers.awaitTermination(8, TimeUnit.SECONDS);
            if (!workersStopped) {
                workers.shutdownNow();
                workersStopped = workers.awaitTermination(3, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            workers.shutdownNow();
            workersStopped = workers.isTerminated();
            Thread.currentThread().interrupt();
        }

        synchronized (lock) {
            if (workersStopped) {
                for (DownloadJob job : active) {
                    if (job.status != DownloadStatus.PAUSING) {
                        continue;
                    }
                    job.status = DownloadStatus.PAUSED;
                    try {
                        job.transferredBytes = Files.exists(job.partialFile) ? Files.size(job.partialFile) : 0;
                    } catch (IOException ignored) {
                        // The last recorded byte count remains available for recovery.
                    }
                }
            }
        }
        for (DownloadJob job : snapshotJobsInternal()) {
            try {
                control.execute(() -> {
                    try {
                        repository.save(job.stored());
                    } catch (IOException exception) {
                        persistenceError = "Could not save download history: " + exception.getMessage();
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                break;
            }
        }
        control.shutdown();
        try {
            if (!control.awaitTermination(4, TimeUnit.SECONDS)) {
                control.shutdownNow();
            }
        } catch (InterruptedException exception) {
            control.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
