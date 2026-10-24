package com.bytebox.download;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class HttpDownloadWorker {
    private static final Pattern CONTENT_RANGE = Pattern.compile("(?i)^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$");
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int CONNECT_TIMEOUT_MILLIS = 20_000;
    private static final int READ_TIMEOUT_MILLIS = 120_000;
    private static final int MAX_REDIRECTS = 5;

    private final DownloadManager manager;

    HttpDownloadWorker(DownloadManager manager) {
        this.manager = manager;
    }

    void run(DownloadJob job) {
        manager.workerStarted(job);
        try {
            if (manager.shouldStop(job)) {
                finishStop(job);
                return;
            }
            if (job.restartFromZero) {
                Files.deleteIfExists(job.partialFile);
                job.restartFromZero = false;
                job.transferredBytes = 0;
                job.totalBytes = null;
            }

            long transferred = Files.isRegularFile(job.partialFile) ? Files.size(job.partialFile) : 0;
            if (transferred == 0 && job.transferredBytes != 0) {
                job.transferredBytes = 0;
                job.etag = null;
                job.lastModified = null;
            }
            if (transferred > 0 && job.totalBytes != null && transferred > job.totalBytes) {
                manager.workerFailed(job, "The saved partial file is larger than the server's recorded size. Restart from zero to continue safely.", true);
                return;
            }
            if (transferred > 0 && job.totalBytes != null && transferred == job.totalBytes) {
                complete(job, transferred);
                return;
            }

            if (transferred > 0) {
                if (strongEtag(job.etag) == null && (job.lastModified == null || job.lastModified.isBlank())) {
                    manager.workerFailed(job, "The server did not provide an ETag or Last-Modified validator, so BYTEBOX cannot verify that the remote file is unchanged. Restart from zero to continue safely.", true);
                    return;
                }
                job.transferredBytes = transferred;
                manager.reportProgress(job, transferred);
            }
            while (true) {
                if (manager.shouldStop(job)) {
                    finishStop(job);
                    return;
                }

                HttpURLConnection connection = openConnection(job, transferred);
                try {
                    int statusCode = connection.getResponseCode();
                    RangeSegment segment = validateResponse(job, connection, statusCode, transferred);
                    if (segment == null) {
                        return;
                    }
                    if (manager.shouldStop(job)) {
                        finishStop(job);
                        return;
                    }

                    long beforeSegment = transferred;
                    InputStream activeBody = null;
                    try (InputStream rawBody = connection.getInputStream();
                         InputStream body = new BufferedInputStream(rawBody, BUFFER_SIZE)) {
                        activeBody = body;
                        job.activeStream = body;
                        long segmentBytes = writeBody(job, body, beforeSegment, segment.length());
                        if (manager.shouldStop(job)) {
                            finishStop(job);
                            return;
                        }
                        transferred += segmentBytes;
                        if (segment.length() != null && segmentBytes != segment.length()) {
                            throw new IOException("Connection ended early after " + segmentBytes + " of " + segment.length() + " bytes in this response.");
                        }
                        if (segment.totalBytes() != null) {
                            if (transferred > segment.totalBytes()) {
                                manager.workerFailed(job, "The server sent more bytes than its Content-Range declared. The partial file was kept; restart from zero to continue safely.", true);
                                return;
                            }
                            if (transferred < segment.totalBytes()) {
                                continue;
                            }
                        }
                        complete(job, transferred);
                        return;
                    } finally {
                        if (job.activeStream == activeBody) {
                            job.activeStream = null;
                        }
                    }
                } finally {
                    if (job.activeConnection == connection) {
                        job.activeConnection = null;
                    }
                    closeResponseBody(connection);
                    connection.disconnect();
                }
            }
        } catch (IOException exception) {
            if (manager.shouldStop(job)) {
                finishStop(job);
            } else {
                manager.workerFailed(job, readableMessage(exception), false);
            }
        } catch (RuntimeException exception) {
            if (manager.shouldStop(job)) {
                finishStop(job);
            } else {
                manager.workerFailed(job, "Download failed: " + readableMessage(exception), false);
            }
        } finally {
            if (job.workerThread == Thread.currentThread()) {
                job.workerThread = null;
            }
        }
    }

    private HttpURLConnection openConnection(DownloadJob job, long transferred) throws IOException {
        URL current = job.source.toURL();
        Set<String> visited = new HashSet<>();
        visited.add(current.toExternalForm());
        int redirects = 0;

        while (true) {
            if (manager.shouldStop(job)) {
                throw new IOException("Download stopped before the connection was opened.");
            }
            URLConnection urlConnection = current.openConnection();
            if (!(urlConnection instanceof HttpURLConnection connection)) {
                throw new IOException("Only HTTP and HTTPS download connections are supported.");
            }

            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "BYTEBOX/1.0");
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            if (transferred > 0) {
                connection.setRequestProperty("Range", "bytes=" + transferred + "-");
                String ifRange = strongEtag(job.etag);
                if (ifRange != null) {
                    connection.setRequestProperty("If-Range", ifRange);
                } else if (job.lastModified != null && !job.lastModified.isBlank()) {
                    connection.setRequestProperty("If-Range", job.lastModified);
                }
            }
            job.activeConnection = connection;

            boolean keepConnection = false;
            int statusCode = -1;
            try {
                if (manager.shouldStop(job)) {
                    throw new IOException("Download stopped before the server response arrived.");
                }
                statusCode = connection.getResponseCode();
                if (!isRedirect(statusCode)) {
                    keepConnection = true;
                    return connection;
                }
                if (redirects >= MAX_REDIRECTS) {
                    throw new IOException("The server exceeded BYTEBOX's limit of " + MAX_REDIRECTS + " redirects.");
                }

                String location = connection.getHeaderField("Location");
                if (location == null || location.isBlank()) {
                    throw new IOException("The server returned HTTP " + statusCode + " without a redirect location.");
                }
                URL next = resolveRedirect(current, location);
                validateRedirect(current, next);
                if (!visited.add(next.toExternalForm())) {
                    throw new IOException("The server returned a redirect loop.");
                }
                redirects++;
                current = next;
            } finally {
                if (!keepConnection) {
                    if (job.activeConnection == connection) {
                        job.activeConnection = null;
                    }
                    if (statusCode >= 0) {
                        closeResponseBody(connection);
                    }
                    connection.disconnect();
                }
            }
        }
    }

    private static void validateRedirect(URL current, URL next) throws IOException {
        String protocol = next.getProtocol().toLowerCase(Locale.ROOT);
        if (!protocol.equals("http") && !protocol.equals("https")) {
            throw new IOException("The server redirected to an unsupported protocol.");
        }
        if (next.getUserInfo() != null) {
            throw new IOException("The server redirected to a URL containing embedded credentials.");
        }
        if (current.getProtocol().equalsIgnoreCase("https") && protocol.equals("http")) {
            throw new IOException("BYTEBOX refused an HTTPS-to-HTTP redirect.");
        }
    }

    private static URL resolveRedirect(URL current, String location) throws IOException {
        try {
            return current.toURI().resolve(location).toURL();
        } catch (URISyntaxException | MalformedURLException | IllegalArgumentException exception) {
            throw new IOException("The server returned an invalid redirect location.", exception);
        }
    }

    private static boolean isRedirect(int statusCode) {
        return statusCode == HttpURLConnection.HTTP_MOVED_PERM
                || statusCode == HttpURLConnection.HTTP_MOVED_TEMP
                || statusCode == HttpURLConnection.HTTP_SEE_OTHER
                || statusCode == 307
                || statusCode == 308;
    }

    private static void closeResponseBody(HttpURLConnection connection) {
        InputStream body = connection.getErrorStream();
        if (body == null) {
            try {
                body = connection.getInputStream();
            } catch (IOException ignored) {
                // Some HTTP error responses do not expose an input stream.
            }
        }
        if (body != null) {
            try {
                body.close();
            } catch (IOException ignored) {
                // Disconnect below still releases the connection if the response body cannot close cleanly.
            }
        }
    }

    private RangeSegment validateResponse(DownloadJob job, HttpURLConnection response, int statusCode,
                                          long requestedOffset) throws IOException {
        if (requestedOffset == 0) {
            if (statusCode != HttpURLConnection.HTTP_OK) {
                manager.workerFailed(job, "The server returned HTTP " + statusCode + " for the download request.", false);
                return null;
            }
            Long contentLength = contentLength(response);
            manager.setTotalBytes(job, contentLength);
            manager.updateValidators(job, header(response, "ETag"), header(response, "Last-Modified"));
            return new RangeSegment(contentLength, contentLength);
        }

        if (statusCode != HttpURLConnection.HTTP_PARTIAL) {
            String message = statusCode == HttpURLConnection.HTTP_OK
                    ? "The server ignored the Range request. Your partial file is safe; restart from zero to continue."
                    : "The server returned HTTP " + statusCode + " while resuming. Restart from zero to continue safely.";
            manager.workerFailed(job, message, true);
            return null;
        }

        String contentRange = header(response, "Content-Range");
        Matcher matcher = contentRange == null ? null : CONTENT_RANGE.matcher(contentRange.trim());
        if (matcher == null || !matcher.matches()) {
            manager.workerFailed(job, "The server returned an invalid Content-Range. Restart from zero to continue safely.", true);
            return null;
        }
        long start = parseLong(matcher.group(1), "Content-Range start");
        long end = parseLong(matcher.group(2), "Content-Range end");
        Long total = matcher.group(3).equals("*") ? null : parseLong(matcher.group(3), "Content-Range total");
        Long previousTotal = job.totalBytes;
        if (start != requestedOffset || end < start || (total != null && (total <= end || total < requestedOffset))) {
            manager.workerFailed(job, "The server returned a mismatched Content-Range. Restart from zero to continue safely.", true);
            return null;
        }
        if (total == null && previousTotal != null && end >= previousTotal) {
            manager.workerFailed(job, "The server returned a range beyond the previously known file size. Restart from zero to continue safely.", true);
            return null;
        }

        if (previousTotal != null && total != null && !previousTotal.equals(total)) {
            manager.workerFailed(job, "The server's file size changed since the partial download. Restart from zero to avoid combining different files.", true);
            return null;
        }

        String responseEtag = header(response, "ETag");
        String responseLastModified = header(response, "Last-Modified");
        String requestedEtag = strongEtag(job.etag);
        if ((requestedEtag != null && !requestedEtag.equals(responseEtag))
                || (requestedEtag == null && job.lastModified != null && !job.lastModified.isBlank()
                && !job.lastModified.equals(responseLastModified))) {
            manager.workerFailed(job, "The server's file validator changed or is missing since the partial download. Restart from zero to avoid combining different files.", true);
            return null;
        }
        if (changed(job.etag, responseEtag) || changed(job.lastModified, responseLastModified)) {
            manager.workerFailed(job, "The server's file validator changed since the partial download. Restart from zero to avoid combining different files.", true);
            return null;
        }
        Long effectiveTotal = total == null ? previousTotal : total;

        long rangeLength = end - start + 1;
        Long responseLength = contentLength(response);
        if (responseLength != null && responseLength != rangeLength) {
            manager.workerFailed(job, "The response length does not match Content-Range. Restart from zero to continue safely.", true);
            return null;
        }
        manager.updateValidators(job, responseEtag, responseLastModified);
        manager.setTotalBytes(job, effectiveTotal);
        return new RangeSegment(rangeLength, effectiveTotal);
    }

    private long writeBody(DownloadJob job, InputStream body, long currentBytes, Long expectedSegmentLength) throws IOException {
        Files.createDirectories(job.partialFile.getParent());
        long segmentBytes = 0;
        boolean append = currentBytes > 0;
        try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(job.partialFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING), BUFFER_SIZE)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = body.read(buffer)) != -1) {
                if (manager.shouldStop(job)) {
                    break;
                }
                if (expectedSegmentLength != null && segmentBytes + count > expectedSegmentLength) {
                    throw new IOException("The server sent more bytes than the response range declared.");
                }
                output.write(buffer, 0, count);
                segmentBytes += count;
                manager.reportProgress(job, currentBytes + segmentBytes);
            }
        }
        return segmentBytes;
    }

    private void complete(DownloadJob job, long bytes) throws IOException {
        if (manager.shouldStop(job)) {
            finishStop(job);
            return;
        }
        Long total = job.totalBytes;
        if (total != null && bytes != total) {
            throw new IOException("The server advertised " + total + " bytes, but only " + bytes + " bytes arrived.");
        }
        if (Files.exists(job.destination)) {
            manager.workerFailed(job, "A file with this name appeared in the download folder. The partial file was kept to prevent overwriting it.", false);
            return;
        }
        try {
            Files.move(job.partialFile, job.destination);
        } catch (java.nio.file.FileAlreadyExistsException exception) {
            manager.workerFailed(job, "A file with this name appeared in the download folder. The partial file was kept to prevent overwriting it.", false);
            return;
        }
        manager.workerCompleted(job, bytes);
    }

    private void finishStop(DownloadJob job) {
        if (job.cancelRequested) {
            String cleanupError = null;
            try {
                Files.deleteIfExists(job.partialFile);
            } catch (IOException exception) {
                cleanupError = "Cancelled, but the partial file could not be removed: " + readableMessage(exception);
            }
            long remaining = 0;
            try {
                remaining = Files.exists(job.partialFile) ? Files.size(job.partialFile) : 0;
            } catch (IOException ignored) {
                // The last recorded count is used if the file cannot be inspected.
                remaining = job.transferredBytes;
            }
            manager.workerCancelled(job, remaining, cleanupError);
            return;
        }

        // The manager transitions to PAUSED only after run() exits and its response stream closes.
    }

    private static Long contentLength(HttpURLConnection response) {
        long length = response.getContentLengthLong();
        return length >= 0 ? length : null;
    }

    private static String header(HttpURLConnection response, String name) {
        return response.getHeaderField(name);
    }

    private static boolean changed(String previous, String current) {
        return previous != null && !previous.isBlank() && current != null && !current.isBlank() && !previous.equals(current);
    }

    private static String strongEtag(String etag) {
        return etag == null || etag.isBlank() || etag.toLowerCase(Locale.ROOT).startsWith("w/") ? null : etag;
    }

    private static long parseLong(String value, String field) throws IOException {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid " + field + " from server.", exception);
        }
    }

    private static String readableMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private record RangeSegment(Long length, Long totalBytes) {
    }
}
