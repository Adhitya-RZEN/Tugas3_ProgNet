package com.bytebox.download;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

final class DownloadRepository {
    private static final String SETTINGS_FILE = "settings.properties";
    private final Path root;
    private final Path jobsDirectory;

    DownloadRepository(Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.jobsDirectory = this.root.resolve("jobs");
    }

    List<StoredDownload> loadAll() throws IOException {
        if (!Files.isDirectory(jobsDirectory)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(jobsDirectory, "*.properties")) {
            stream.forEach(files::add);
        }
        files.sort(Comparator.comparing(path -> path.getFileName().toString()));

        List<StoredDownload> jobs = new ArrayList<>();
        for (Path file : files) {
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(file)) {
                properties.load(input);
            }
            try {
                UUID id = UUID.fromString(required(properties, "id"));
                URI source = URI.create(required(properties, "source"));
                Path destination = Path.of(required(properties, "destination")).toAbsolutePath().normalize();
                String fileName = required(properties, "fileName");
                if (!destination.getFileName().toString().equals(fileName)) {
                    throw new IllegalArgumentException("Stored name does not match destination");
                }
                DownloadStatus status = DownloadStatus.valueOf(required(properties, "status"));
                if (status == DownloadStatus.DOWNLOADING || status == DownloadStatus.PAUSING) {
                    status = DownloadStatus.PAUSED;
                } else if (status == DownloadStatus.CANCELLING) {
                    status = DownloadStatus.CANCELLED;
                }
                String total = properties.getProperty("totalBytes", "");
                String error = properties.getProperty("errorMessage", "");
                jobs.add(new StoredDownload(
                        id,
                        source,
                        fileName,
                        destination,
                        Math.max(0, Long.parseLong(properties.getProperty("transferredBytes", "0"))),
                        total.isBlank() ? null : Long.parseLong(total),
                        java.time.Instant.parse(required(properties, "createdAt")),
                        status,
                        error.isBlank() ? null : error,
                        Boolean.parseBoolean(properties.getProperty("restartRequired", "false")),
                        properties.getProperty("etag", ""),
                        properties.getProperty("lastModified", "")));
            } catch (RuntimeException exception) {
                throw new IOException("Invalid download metadata in " + file.getFileName(), exception);
            }
        }
        return List.copyOf(jobs);
    }

    void save(StoredDownload job) throws IOException {
        Files.createDirectories(jobsDirectory);
        Properties properties = new Properties();
        properties.setProperty("id", job.id().toString());
        properties.setProperty("source", job.source().toASCIIString());
        properties.setProperty("fileName", job.fileName());
        properties.setProperty("destination", job.destination().toAbsolutePath().normalize().toString());
        properties.setProperty("transferredBytes", Long.toString(job.transferredBytes()));
        if (job.totalBytes() != null) {
            properties.setProperty("totalBytes", Long.toString(job.totalBytes()));
        }
        properties.setProperty("createdAt", job.createdAt().toString());
        properties.setProperty("status", job.status().name());
        properties.setProperty("restartRequired", Boolean.toString(job.restartRequired()));
        if (job.errorMessage() != null && !job.errorMessage().isBlank()) {
            properties.setProperty("errorMessage", job.errorMessage());
        }
        if (job.etag() != null && !job.etag().isBlank()) {
            properties.setProperty("etag", job.etag());
        }
        if (job.lastModified() != null && !job.lastModified().isBlank()) {
            properties.setProperty("lastModified", job.lastModified());
        }

        Path target = jobsDirectory.resolve(job.id() + ".properties");
        Path temporary = jobsDirectory.resolve(job.id() + "." + UUID.randomUUID() + ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "BYTEBOX download metadata");
            }
            moveReplacing(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    Path loadDownloadDirectory(Path fallback) throws IOException {
        Path settings = root.resolve(SETTINGS_FILE);
        if (!Files.isRegularFile(settings)) {
            return fallback.toAbsolutePath().normalize();
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(settings)) {
            properties.load(input);
        }
        String value = properties.getProperty("downloadDirectory", "").trim();
        return value.isEmpty() ? fallback.toAbsolutePath().normalize() : Path.of(value).toAbsolutePath().normalize();
    }

    void saveDownloadDirectory(Path directory) throws IOException {
        Files.createDirectories(root);
        Properties properties = new Properties();
        properties.setProperty("downloadDirectory", directory.toAbsolutePath().normalize().toString());
        Path target = root.resolve(SETTINGS_FILE);
        Path temporary = root.resolve(SETTINGS_FILE + "." + UUID.randomUUID() + ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "BYTEBOX settings");
            }
            moveReplacing(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + key);
        }
        return value;
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
