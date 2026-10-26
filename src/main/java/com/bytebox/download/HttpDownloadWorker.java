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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;

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
            if (isVideoPage(job.source)) {
                manager.workerFailed(job, "This URL points to a video webpage, not a direct video file.", false);
                return;
            }
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
                         BufferedInputStream body = new BufferedInputStream(rawBody, BUFFER_SIZE)) {
                        activeBody = body;
                        job.activeStream = body;
                        if (isUnexpectedHtmlBody(job, connection, body)) {
                            manager.workerFailed(job, htmlResponseMessage(job, null), transferred > 0);
                            return;
                        }
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
        String contentDisposition = header(response, "Content-Disposition");
        String responseFileName = FileNameResolver.fileNameFromContentDisposition(contentDisposition);
        if (requestedOffset == 0) {
            if (statusCode != HttpURLConnection.HTTP_OK) {
                manager.workerFailed(job, httpFailureMessage(response, statusCode, false), false);
                return null;
            }
            if (isVideoPage(response.getURL())
                    || (isHtmlMime(header(response, "Content-Type")) && !explicitHtml(job.source, responseFileName))) {
                manager.workerFailed(job, isVideoPage(response.getURL())
                        ? "This URL points to a video webpage, not a direct video file."
                        : htmlResponseMessage(job, responseFileName), false);
                return null;
            }
            manager.applyContentDispositionFilename(job, responseFileName);
            Long contentLength = contentLength(response);
            manager.setTotalBytes(job, contentLength);
            manager.updateValidators(job, header(response, "ETag"), header(response, "Last-Modified"));
            return new RangeSegment(contentLength, contentLength);
        }

        if (statusCode != HttpURLConnection.HTTP_PARTIAL) {
            if (statusCode == HttpURLConnection.HTTP_OK
                    && (isVideoPage(response.getURL())
                    || (isHtmlMime(header(response, "Content-Type")) && !explicitHtml(job.source, responseFileName)))) {
                manager.workerFailed(job, isVideoPage(response.getURL())
                        ? "This URL points to a video webpage, not a direct video file."
                        : htmlResponseMessage(job, responseFileName), true);
                return null;
            }
            String message = statusCode == HttpURLConnection.HTTP_OK
                    ? "The server ignored the Range request. Your partial file is safe; restart from zero to continue."
                    : httpFailureMessage(response, statusCode, true);
            manager.workerFailed(job, message, true);
            return null;
        }

        if (isVideoPage(response.getURL())
                || (isHtmlMime(header(response, "Content-Type")) && !explicitHtml(job.source, responseFileName))) {
            manager.workerFailed(job, isVideoPage(response.getURL())
                    ? "This URL points to a video webpage, not a direct video file."
                    : htmlResponseMessage(job, responseFileName), true);
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

    private static boolean isUnexpectedHtmlBody(DownloadJob job, HttpURLConnection response, BufferedInputStream body)
            throws IOException {
        String fileName = FileNameResolver.fileNameFromContentDisposition(header(response, "Content-Disposition"));
        if (explicitHtml(job.source, fileName)) {
            return false;
        }
        if (isHtmlMime(header(response, "Content-Type"))) {
            return true;
        }
        return looksLikeHtml(body);
    }

    private static boolean looksLikeHtml(BufferedInputStream body) throws IOException {
        body.mark(4096);
        byte[] prefix = new byte[4096];
        int length = 0;
        while (length < prefix.length) {
            int count = body.read(prefix, length, prefix.length - length);
            if (count < 0) {
                break;
            }
            length += count;
            if (length >= 512) {
                break;
            }
        }
        body.reset();
        if (length == 0) {
            return false;
        }
        String text = new String(prefix, 0, length, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\ufeff') {
            text = text.substring(1);
        }
        text = text.stripLeading().toLowerCase(Locale.ROOT);
        while (text.startsWith("<!--")) {
            int commentEnd = text.indexOf("-->");
            if (commentEnd < 0) {
                return false;
            }
            text = text.substring(commentEnd + 3).stripLeading();
        }
        return text.startsWith("<!doctype html") || text.startsWith("<html")
                || text.startsWith("<head") || text.startsWith("<body") || text.startsWith("<title");
    }

    private static boolean explicitHtml(java.net.URI source, String contentDispositionFileName) {
        return isHtmlFileName(source.getPath()) || isHtmlFileName(contentDispositionFileName);
    }

    private static boolean isHtmlFileName(String fileName) {
        if (fileName == null) {
            return false;
        }
        String normalized = fileName.toLowerCase(Locale.ROOT);
        return normalized.endsWith(".html") || normalized.endsWith(".htm");
    }

    private static boolean isHtmlMime(String contentType) {
        if (contentType == null) {
            return false;
        }
        String mimeType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return mimeType.equals("text/html") || mimeType.equals("application/xhtml+xml");
    }

    private static boolean isVideoPage(java.net.URI uri) {
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        return isVideoPage(host, uri.getPath());
    }

    private static boolean isVideoPage(URL url) {
        return isVideoPage(url.getHost(), url.getPath());
    }

    private static boolean isVideoPage(String rawHost, String rawPath) {
        String host = rawHost.toLowerCase(Locale.ROOT);
        String path = rawPath == null ? "/" : rawPath.toLowerCase(Locale.ROOT);
        if (host.equals("youtu.be") || host.endsWith(".youtu.be")) {
            return path.length() > 1;
        }
        boolean youtubeHost = host.equals("youtube.com") || host.endsWith(".youtube.com")
                || host.equals("youtube-nocookie.com") || host.endsWith(".youtube-nocookie.com");
        return youtubeHost && (path.equals("/watch") || path.startsWith("/shorts/")
                || path.startsWith("/live/") || path.startsWith("/embed/")
                || path.startsWith("/v/") || path.startsWith("/clip/"));
    }

    private static String htmlResponseMessage(DownloadJob job, String responseFileName) {
        String candidate = responseFileName == null ? job.fileName : responseFileName;
        int dot = candidate.lastIndexOf('.');
        String extension = dot < 0 ? "" : candidate.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (Set.of("mp4", "m4v", "mov", "mkv", "webm", "avi", "mp3", "m4a", "aac", "ogg", "wav", "flac")
                .contains(extension)) {
            return "The server returned HTML instead of the expected media file.";
        }
        return "The server returned an HTML page instead of a direct file download.";
    }

    private static String httpFailureMessage(HttpURLConnection response, int statusCode, boolean resuming) {
        String reason = statusReason(statusCode);
        String status = "HTTP " + statusCode + (reason.isEmpty() ? "" : " " + reason);
        if (isCloudflareChallenge(response, statusCode)) {
            return "The server returned a Cloudflare challenge (" + status + "). Direct downloads are unavailable for this response.";
        }
        return "The server returned " + status + (resuming
                ? " while resuming. Restart from zero to continue safely."
                : " for the download request.");
    }

    private static boolean isCloudflareChallenge(HttpURLConnection response, int statusCode) {
        String mitigation = header(response, "cf-mitigated");
        if (mitigation != null && mitigation.equalsIgnoreCase("challenge")) {
            return true;
        }
        String server = header(response, "Server");
        return (statusCode == 403 || statusCode == 429 || statusCode == 503)
                && server != null && server.toLowerCase(Locale.ROOT).contains("cloudflare")
                && isHtmlMime(header(response, "Content-Type"));
    }

    private static String statusReason(int statusCode) {
        return switch (statusCode) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 408 -> "Request Timeout";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> "";
        };
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

    static String readableMessage(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof SSLException) {
                return "TLS connection failed: " + messageOf(cause);
            }
        }
        return messageOf(throwable);
    }

    private static String messageOf(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private record RangeSegment(Long length, Long totalBytes) {
    }
}
