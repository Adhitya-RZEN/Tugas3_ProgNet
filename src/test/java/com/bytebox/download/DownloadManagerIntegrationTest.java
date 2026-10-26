package com.bytebox.download;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.net.ssl.SSLHandshakeException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadManagerIntegrationTest {
    private static final Duration WAIT_LIMIT = Duration.ofSeconds(12);

    @TempDir
    Path temporaryDirectory;

    private HttpServer server;
    private ExecutorService serverExecutor;
    private ProxySelector previousProxySelector;
    private final AtomicInteger activeResponses = new AtomicInteger();
    private final AtomicInteger peakResponses = new AtomicInteger();
    private final AtomicReference<RangeBehavior> rangeBehavior = new AtomicReference<>(RangeBehavior.NORMAL);
    private final Map<String, AtomicInteger> requestCounts = new ConcurrentHashMap<>();
    private final Map<String, byte[]> contentByPath = new ConcurrentHashMap<>();
    private final Map<String, FixtureResponse> fixtureResponses = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> rangeHeaders = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> ifRangeHeaders = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void startServer() throws IOException {
        previousProxySelector = ProxySelector.getDefault();
        ProxySelector.setDefault(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                String host = uri.getHost();
                if (host != null && (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1"))) {
                    return List.of(Proxy.NO_PROXY);
                }
                return previousProxySelector == null ? List.of(Proxy.NO_PROXY) : previousProxySelector.select(uri);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException exception) {
                if (previousProxySelector != null) {
                    previousProxySelector.connectFailed(uri, address, exception);
                }
            }
        });
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        serverExecutor = Executors.newFixedThreadPool(8);
        server.setExecutor(serverExecutor);
        server.createContext("/", this::respond);
        server.start();
    }

    @AfterEach
    void stopServer() {
        ProxySelector.setDefault(previousProxySelector);
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void downloadsRealBytesAndAllocatesDistinctNamesForDuplicates() throws Exception {
        Path downloads = temporaryDirectory.resolve("downloads");
        try (DownloadManager manager = newManager(downloads, 2)) {
            UUID firstId = manager.add(url("/a/report.txt")).get(3, TimeUnit.SECONDS);
            UUID secondId = manager.add(url("/b/report.txt")).get(3, TimeUnit.SECONDS);
            DownloadSnapshot first = await(manager, firstId, job -> job.status() == DownloadStatus.COMPLETED);
            DownloadSnapshot second = await(manager, secondId, job -> job.status() == DownloadStatus.COMPLETED);

            assertNotEquals(first.destination(), second.destination());
            assertEquals("report.txt", first.fileName());
            assertEquals("report (1).txt", second.fileName());
            assertArrayEquals(content("/a/report.txt"), Files.readAllBytes(first.destination()));
            assertArrayEquals(content("/b/report.txt"), Files.readAllBytes(second.destination()));
        }
    }

    @Test
    void runsThreeTransfersAtOnceAndKeepsEachProgressIndependent() throws Exception {
        Path downloads = temporaryDirectory.resolve("downloads");
        try (DownloadManager manager = newManager(downloads, 3)) {
            List<UUID> ids = new ArrayList<>();
            ids.add(manager.add(url("/slow/one.bin")).get(3, TimeUnit.SECONDS));
            ids.add(manager.add(url("/slow/two.bin")).get(3, TimeUnit.SECONDS));
            ids.add(manager.add(url("/slow/three.bin")).get(3, TimeUnit.SECONDS));

            awaitCondition(() -> peakResponses.get() >= 3, WAIT_LIMIT, "three simultaneous HTTP responses");
            assertEquals(3, manager.activeConnections());
            for (UUID id : ids) {
                DownloadSnapshot snapshot = await(manager, id,
                        job -> job.status() == DownloadStatus.DOWNLOADING && job.transferredBytes() > 0);
                assertTrue(snapshot.transferredBytes() < snapshot.totalBytes());
            }

            for (UUID id : ids) {
                DownloadSnapshot completed = await(manager, id, job -> job.status() == DownloadStatus.COMPLETED);
                assertArrayEquals(content("/slow/" + completed.fileName()), Files.readAllBytes(completed.destination()));
            }
        }
    }

    @Test
    void startsQueuedTransferWhenAConcurrentSlotBecomesAvailable() throws Exception {
        Path downloads = temporaryDirectory.resolve("downloads");
        try (DownloadManager manager = newManager(downloads, 3)) {
            List<UUID> ids = new ArrayList<>();
            for (int index = 1; index <= 4; index++) {
                ids.add(manager.add(url("/slow/queued-" + index + ".bin")).get(3, TimeUnit.SECONDS));
            }

            awaitCondition(() -> peakResponses.get() >= 3, WAIT_LIMIT, "three simultaneous HTTP responses");
            DownloadSnapshot fourth = manager.snapshots().stream()
                    .filter(job -> job.id().equals(ids.get(3)))
                    .findFirst().orElseThrow();
            assertEquals(DownloadStatus.QUEUED, fourth.status());

            DownloadSnapshot queuedCompletion = await(manager, ids.get(3), job -> job.status() == DownloadStatus.COMPLETED);
            assertArrayEquals(content("/slow/queued-4.bin"), Files.readAllBytes(queuedCompletion.destination()));
            for (UUID id : ids.subList(0, 3)) {
                DownloadSnapshot completed = await(manager, id, job -> job.status() == DownloadStatus.COMPLETED);
                assertArrayEquals(content("/slow/" + completed.fileName()), Files.readAllBytes(completed.destination()));
            }
        }
    }

    @Test
    void pausesAndResumesUsingRangeAndIfRangeWithoutCorruptingBytes() throws Exception {
        Path downloads = temporaryDirectory.resolve("downloads");
        try (DownloadManager manager = newManager(downloads, 1)) {
            UUID id = manager.add(url("/slow/range.zip")).get(3, TimeUnit.SECONDS);
            await(manager, id, job -> job.status() == DownloadStatus.DOWNLOADING && job.transferredBytes() >= 1);
            manager.pause(id);
            DownloadSnapshot paused = await(manager, id, job -> job.status() == DownloadStatus.PAUSED);
            long savedBytes = Files.size(paused.partialFile());
            assertTrue(savedBytes > 0);
            assertEquals(savedBytes, paused.transferredBytes());

            manager.resume(id);
            DownloadSnapshot completed = await(manager, id, job -> job.status() == DownloadStatus.COMPLETED);
            assertArrayEquals(content("/slow/range.zip"), Files.readAllBytes(completed.destination()));
            assertTrue(rangeHeaders.stream().anyMatch(value -> value.startsWith("bytes=" + savedBytes + "-")));
            assertTrue(ifRangeHeaders.contains("\"v1\""));
        }
    }

    @Test
    void followsRedirectAndPreservesRangeValidatorsWhenResuming() throws Exception {
        try (DownloadManager manager = newManager(temporaryDirectory.resolve("downloads"), 1)) {
            UUID id = manager.add(url("/redirect/slow/range-redirect.bin")).get(3, TimeUnit.SECONDS);
            await(manager, id, job -> job.status() == DownloadStatus.DOWNLOADING && job.transferredBytes() > 0);
            manager.pause(id);
            DownloadSnapshot paused = await(manager, id, job -> job.status() == DownloadStatus.PAUSED);
            long savedBytes = Files.size(paused.partialFile());

            manager.resume(id);
            DownloadSnapshot completed = await(manager, id, job -> job.status() == DownloadStatus.COMPLETED);

            assertArrayEquals(content("/slow/range-redirect.bin"), Files.readAllBytes(completed.destination()));
            assertTrue(rangeHeaders.stream().anyMatch(value -> value.startsWith("bytes=" + savedBytes + "-")));
            assertTrue(ifRangeHeaders.contains("\"v1\""));
        }
    }

    @Test
    void reportsFailureWhenServerExceedsRedirectLimit() throws Exception {
        try (DownloadManager manager = newManager(temporaryDirectory.resolve("downloads"), 1)) {
            UUID id = manager.add(url("/redirect-chain/0")).get(3, TimeUnit.SECONDS);
            DownloadSnapshot failed = await(manager, id, job -> job.status() == DownloadStatus.FAILED);

            assertFalse(failed.restartRequired());
            assertTrue(failed.errorMessage().toLowerCase(java.util.Locale.ROOT).contains("limit"), failed.errorMessage());
        }
    }

    @Test
    void reportsFailureForRedirectLoop() throws Exception {
        try (DownloadManager manager = newManager(temporaryDirectory.resolve("downloads"), 1)) {
            UUID id = manager.add(url("/redirect-loop")).get(3, TimeUnit.SECONDS);
            DownloadSnapshot failed = await(manager, id, job -> job.status() == DownloadStatus.FAILED);

            assertFalse(failed.restartRequired());
            assertTrue(failed.errorMessage().toLowerCase(java.util.Locale.ROOT).contains("redirect loop"));
        }
    }

    @Test
    void reportsHttpFailureStatusWithoutCreatingAPartialFile() throws Exception {
        try (DownloadManager manager = newManager(temporaryDirectory.resolve("downloads"), 1)) {
            UUID id = manager.add(url("/status/503")).get(3, TimeUnit.SECONDS);
            DownloadSnapshot failed = await(manager, id, job -> job.status() == DownloadStatus.FAILED);

            assertFalse(failed.restartRequired());
            assertTrue(failed.errorMessage().contains("HTTP 503"));
            assertFalse(Files.exists(failed.partialFile()));
        }
    }

    @Test
    void pausingOneTransferLeavesAnotherTransferRunning() throws Exception {
        try (DownloadManager manager = newManager(temporaryDirectory.resolve("downloads"), 2)) {
            UUID pausedId = manager.add(url("/slow/pause-one.bin")).get(3, TimeUnit.SECONDS);
            UUID continuingId = manager.add(url("/slow/keep-moving.bin")).get(3, TimeUnit.SECONDS);
            await(manager, pausedId, job -> job.status() == DownloadStatus.DOWNLOADING && job.transferredBytes() > 0);
            DownloadSnapshot continuingBefore = await(manager, continuingId,
                    job -> job.status() == DownloadStatus.DOWNLOADING && job.transferredBytes() > 0);

            manager.pause(pausedId);
            DownloadSnapshot paused = await(manager, pausedId, job -> job.status() == DownloadStatus.PAUSED);
            DownloadSnapshot continuingAfter = await(manager, continuingId,
                    job -> job.status() == DownloadStatus.DOWNLOADING
                            && job.transferredBytes() > continuingBefore.transferredBytes());
            assertTrue(Files.size(paused.partialFile()) > 0);
            assertTrue(continuingAfter.transferredBytes() > continuingBefore.transferredBytes());

            manager.resume(pausedId);
            DownloadSnapshot pausedTransferCompleted = await(manager, pausedId,
                    job -> job.status() == DownloadStatus.COMPLETED);
            DownloadSnapshot continuingTransferCompleted = await(manager, continuingId,
                    job -> job.status() == DownloadStatus.COMPLETED);
            assertArrayEquals(content("/slow/pause-one.bin"), Files.readAllBytes(pausedTransferCompleted.destination()));
            assertArrayEquals(content("/slow/keep-moving.bin"), Files.readAllBytes(continuingTransferCompleted.destination()));
        }
    }

    @Test
    void refusesToAppendWhenServerIgnoresRangeAndAllowsExplicitRestart() throws Exception {
        Path downloads = temporaryDirectory.resolve("downloads");
        try (DownloadManager manager = newManager(downloads, 1)) {
            UUID id = manager.add(url("/slow/ignore-range.zip")).get(3, TimeUnit.SECONDS);
            await(manager, id, job -> job.status() == DownloadStatus.DOWNLOADING && job.transferredBytes() >= 1);
            manager.pause(id);
            DownloadSnapshot paused = await(manager, id, job -> job.status() == DownloadStatus.PAUSED);
            long savedBytes = Files.size(paused.partialFile());

            rangeBehavior.set(RangeBehavior.IGNORE);
            manager.resume(id);
            DownloadSnapshot failed = await(manager, id, job -> job.status() == DownloadStatus.FAILED);
            assertTrue(failed.restartRequired());
            assertTrue(failed.errorMessage().contains("ignored the Range"));
            assertEquals(savedBytes, Files.size(failed.partialFile()));

            manager.restartFromZero(id);
            DownloadSnapshot completed = await(manager, id, job -> job.status() == DownloadStatus.COMPLETED);
            assertArrayEquals(content("/slow/ignore-range.zip"), Files.readAllBytes(completed.destination()));
        }
    }

    @Test
    void rejectsWrongContentRangeAndChangedValidatorsBeforeAppending() throws Exception {
        Path downloads = temporaryDirectory.resolve("downloads");
        try (DownloadManager manager = newManager(downloads, 1)) {
            UUID wrongRangeId = manager.add(url("/slow/wrong-range.bin")).get(3, TimeUnit.SECONDS);
            await(manager, wrongRangeId, job -> job.status() == DownloadStatus.DOWNLOADING && job.transferredBytes() > 0);
            manager.pause(wrongRangeId);
            DownloadSnapshot wrongRangePaused = await(manager, wrongRangeId, job -> job.status() == DownloadStatus.PAUSED);
            long wrongRangeLength = Files.size(wrongRangePaused.partialFile());
            rangeBehavior.set(RangeBehavior.WRONG_RANGE);
            manager.resume(wrongRangeId);
            DownloadSnapshot wrongRange = await(manager, wrongRangeId, job -> job.status() == DownloadStatus.FAILED);
            assertTrue(wrongRange.restartRequired());
            assertEquals(wrongRangeLength, Files.size(wrongRange.partialFile()));

            rangeBehavior.set(RangeBehavior.NORMAL);
            UUID changedId = manager.add(url("/slow/changed-validator.bin")).get(3, TimeUnit.SECONDS);
            await(manager, changedId, job -> job.status() == DownloadStatus.DOWNLOADING && job.transferredBytes() > 0);
            manager.pause(changedId);
            DownloadSnapshot changedPaused = await(manager, changedId, job -> job.status() == DownloadStatus.PAUSED);
            long changedLength = Files.size(changedPaused.partialFile());
            rangeBehavior.set(RangeBehavior.CHANGED_ETAG);
            manager.resume(changedId);
            DownloadSnapshot changed = await(manager, changedId, job -> job.status() == DownloadStatus.FAILED);
            assertTrue(changed.restartRequired());
            assertTrue(changed.errorMessage().contains("validator changed"));
            assertEquals(changedLength, Files.size(changed.partialFile()));

            rangeBehavior.set(RangeBehavior.NORMAL);
            UUID changedDateId = manager.add(url("/slow/changed-modified-date.bin")).get(3, TimeUnit.SECONDS);
            await(manager, changedDateId, job -> job.status() == DownloadStatus.DOWNLOADING && job.transferredBytes() > 0);
            manager.pause(changedDateId);
            DownloadSnapshot changedDatePaused = await(manager, changedDateId, job -> job.status() == DownloadStatus.PAUSED);
            long changedDateLength = Files.size(changedDatePaused.partialFile());
            rangeBehavior.set(RangeBehavior.CHANGED_LAST_MODIFIED);
            manager.resume(changedDateId);
            DownloadSnapshot changedDate = await(manager, changedDateId, job -> job.status() == DownloadStatus.FAILED);
            assertTrue(changedDate.restartRequired());
            assertTrue(changedDate.errorMessage().contains("validator changed"));
            assertEquals(changedDateLength, Files.size(changedDate.partialFile()));
        }
    }

    @Test
    void refusesToAppendWhenResumedContentRangeAdvertisesADifferentTotal() throws Exception {
        Path downloads = temporaryDirectory.resolve("downloads");
        try (DownloadManager manager = newManager(downloads, 1)) {
            UUID id = manager.add(url("/slow/changed-total.bin")).get(3, TimeUnit.SECONDS);
            await(manager, id, job -> job.status() == DownloadStatus.DOWNLOADING && job.transferredBytes() > 0);
            manager.pause(id);
            DownloadSnapshot paused = await(manager, id, job -> job.status() == DownloadStatus.PAUSED);
            long savedBytes = Files.size(paused.partialFile());

            rangeBehavior.set(RangeBehavior.CHANGED_TOTAL);
            manager.resume(id);
            DownloadSnapshot failed = await(manager, id, job -> job.status() == DownloadStatus.FAILED);

            assertTrue(failed.restartRequired());
            assertTrue(failed.errorMessage().contains("file size changed"));
            assertEquals(savedBytes, Files.size(failed.partialFile()));
        }
    }

    @Test
    void refusesToAppendUnknownTotalRangeBeyondPreviouslyKnownFileSize() throws Exception {
        try (DownloadManager manager = newManager(temporaryDirectory.resolve("downloads"), 1)) {
            UUID id = manager.add(url("/slow/unknown-total-overrun.bin")).get(3, TimeUnit.SECONDS);
            await(manager, id, job -> job.status() == DownloadStatus.DOWNLOADING && job.transferredBytes() > 0);
            manager.pause(id);
            DownloadSnapshot paused = await(manager, id, job -> job.status() == DownloadStatus.PAUSED);
            long savedBytes = Files.size(paused.partialFile());

            rangeBehavior.set(RangeBehavior.UNKNOWN_TOTAL_OVERRUN);
            manager.resume(id);
            DownloadSnapshot failed = await(manager, id, job -> job.status() == DownloadStatus.FAILED);

            assertTrue(failed.restartRequired());
            assertEquals(savedBytes, Files.size(failed.partialFile()));
        }
    }

    @Test
    void refusesToAppendWhenResumedResponseOmitsTheStrongEtag() throws Exception {
        try (DownloadManager manager = newManager(temporaryDirectory.resolve("downloads"), 1)) {
            UUID id = manager.add(url("/slow/missing-etag.bin")).get(3, TimeUnit.SECONDS);
            await(manager, id, job -> job.status() == DownloadStatus.DOWNLOADING && job.transferredBytes() > 0);
            manager.pause(id);
            DownloadSnapshot paused = await(manager, id, job -> job.status() == DownloadStatus.PAUSED);
            long savedBytes = Files.size(paused.partialFile());

            rangeBehavior.set(RangeBehavior.MISSING_ETAG);
            manager.resume(id);
            DownloadSnapshot failed = await(manager, id, job -> job.status() == DownloadStatus.FAILED);

            assertTrue(failed.restartRequired());
            assertTrue(failed.errorMessage().contains("validator"));
            assertEquals(savedBytes, Files.size(failed.partialFile()));
        }
    }

    @Test
    void cancelsOnlyTheRequestedTransferAndDeletesItsPartialFile() throws Exception {
        Path downloads = temporaryDirectory.resolve("downloads");
        try (DownloadManager manager = newManager(downloads, 2)) {
            UUID cancelledId = manager.add(url("/slow/cancel.zip")).get(3, TimeUnit.SECONDS);
            UUID keptId = manager.add(url("/files/keep.zip")).get(3, TimeUnit.SECONDS);
            DownloadSnapshot active = await(manager, cancelledId,
                    job -> job.status() == DownloadStatus.DOWNLOADING && job.transferredBytes() > 0);
            manager.cancel(cancelledId);
            DownloadSnapshot cancelled = await(manager, cancelledId, job -> job.status() == DownloadStatus.CANCELLED);
            DownloadSnapshot kept = await(manager, keptId, job -> job.status() == DownloadStatus.COMPLETED);

            assertFalse(Files.exists(active.partialFile()));
            assertFalse(Files.exists(cancelled.destination()));
            assertArrayEquals(content("/files/keep.zip"), Files.readAllBytes(kept.destination()));
        }
    }

    @Test
    void handlesUnknownContentLengthAsIndeterminateAndCompletesAtEof() throws Exception {
        try (DownloadManager manager = newManager(temporaryDirectory.resolve("downloads"), 1)) {
            UUID id = manager.add(url("/unknown/size.bin")).get(3, TimeUnit.SECONDS);
            DownloadSnapshot completed = await(manager, id, job -> job.status() == DownloadStatus.COMPLETED);

            assertFalse(completed.indeterminate());
            assertTrue(completed.sizeUnknown());
            assertEquals(1, completed.progress());
            assertArrayEquals(content("/unknown/size.bin"), Files.readAllBytes(completed.destination()));
        }
    }

    @Test
    void recoversInterruptedTransferAfterManagerRestartAndClosesWorkersOnShutdown() throws Exception {
        Path downloads = temporaryDirectory.resolve("downloads");
        Path metadata = temporaryDirectory.resolve("metadata");
        DownloadManager firstManager = new DownloadManager(metadata, downloads, 1);
        UUID id = firstManager.add(url("/slow/recover.zip")).get(3, TimeUnit.SECONDS);
        DownloadSnapshot active = await(firstManager, id,
                job -> job.status() == DownloadStatus.DOWNLOADING && job.transferredBytes() > 0);
        firstManager.close();
        assertEquals(0, firstManager.activeConnections());
        assertTrue(Files.size(active.partialFile()) > 0);

        try (DownloadManager restoredManager = new DownloadManager(metadata, downloads, 1)) {
            DownloadSnapshot restored = restoredManager.snapshots().stream().filter(job -> job.id().equals(id)).findFirst().orElseThrow();
            assertEquals(DownloadStatus.PAUSED, restored.status());
            restoredManager.resume(id);
            DownloadSnapshot completed = await(restoredManager, id, job -> job.status() == DownloadStatus.COMPLETED);
            assertArrayEquals(content("/slow/recover.zip"), Files.readAllBytes(completed.destination()));
        }
    }

    @Test
    void resumesAfterAConnectionEndsBeforeTheAdvertisedLength() throws Exception {
        try (InterruptedThenResumeServer droppedConnection = new InterruptedThenResumeServer(content("/interrupted/connection.bin"));
             DownloadManager manager = newManager(temporaryDirectory.resolve("downloads"), 1)) {
            UUID id = manager.add(droppedConnection.url()).get(3, TimeUnit.SECONDS);
            DownloadSnapshot failed = await(manager, id, job -> job.status() == DownloadStatus.FAILED);
            assertFalse(failed.restartRequired());
            assertTrue(Files.size(failed.partialFile()) > 0);

            manager.resume(id);
            DownloadSnapshot completed = await(manager, id, job -> job.status() == DownloadStatus.COMPLETED);
            assertArrayEquals(content("/interrupted/connection.bin"), Files.readAllBytes(completed.destination()));
            assertTrue(droppedConnection.resumedRequestReceived());
        }
    }

    @Test
    void rejectsInvalidInputBeforeCreatingAJob() throws Exception {
        try (DownloadManager manager = newManager(temporaryDirectory.resolve("downloads"), 1)) {
            Exception error = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                    () -> manager.add("file:///C:/windows/win.ini").get(3, TimeUnit.SECONDS));
            assertTrue(error.getMessage().contains("Only HTTP and HTTPS"));
            assertTrue(manager.snapshots().isEmpty());
        }
    }

    @Test
    void downloadsRepresentativeMediaImagesDocumentsArchivesAndGenericBytesExactly() throws Exception {
        Map<String, Payload> formats = Map.of(
                "/formats/photo.jpg", new Payload(Base64.getDecoder().decode("/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////2wBDAf//////////////////////////////////////////////////////////////////////////////////////wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAb/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIQAxAAAAFf/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABBQJ//8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAgBAwEBPwF//8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAgBAgEBPwF//8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQAGPwJ//8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPyF//9oADAMBAAIAAwAAABD/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oACAEBAAE/IV//2Q=="), "image/jpeg"),
                "/formats/photo.png", new Payload(Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aG9sAAAAASUVORK5CYII="), "image/png"),
                "/formats/photo.webp", new Payload(Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEAAUAmJaQAA3AA/v89WAAAAA=="), "image/webp"),
                "/formats/video.mp4", new Payload(new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 2, 0, 'i', 's', 'o', 'm', 'i', 's', 'o', '2'}, "video/mp4"),
                "/formats/audio.mp3", new Payload(new byte[]{'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xfb, (byte) 0x90, 0x64}, "audio/mpeg"),
                "/formats/document.pdf", new Payload("%PDF-1.4\n1 0 obj<<>>endobj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII), "application/pdf"),
                "/formats/archive.zip", new Payload(new byte[]{'P', 'K', 5, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, "application/zip"),
                "/formats/payload.bin", new Payload(new byte[]{0, (byte) 0xff, 1, 2, 'B', 'Y', 'T', 'E', 0, 13}, "application/octet-stream"));
        formats.forEach((path, payload) -> fixture(path, payload.bytes(), payload.contentType(), null, false, 200, null));

        try (DownloadManager manager = newManager(temporaryDirectory.resolve("downloads"), 3)) {
            Map<UUID, Payload> expected = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, Payload> entry : formats.entrySet()) {
                expected.put(manager.add(url(entry.getKey())).get(3, TimeUnit.SECONDS), entry.getValue());
            }

            for (Map.Entry<UUID, Payload> entry : expected.entrySet()) {
                DownloadSnapshot completed = await(manager, entry.getKey(), job -> job.status() == DownloadStatus.COMPLETED);
                assertArrayEquals(entry.getValue().bytes(), Files.readAllBytes(completed.destination()), completed.fileName());
            }
        }
    }

    @Test
    void rejectsYouTubeVideoPagesBeforeTreatingThemAsFiles() throws Exception {
        try (DownloadManager manager = newManager(temporaryDirectory.resolve("downloads"), 1)) {
            UUID shortLink = manager.add("https://youtu.be/QDia3e12czc?si=Em8pjt72-36MlZ7f").get(3, TimeUnit.SECONDS);
            UUID watchPage = manager.add("https://www.youtube.com/watch?v=QDia3e12czc").get(3, TimeUnit.SECONDS);
            DownloadSnapshot failed = await(manager, shortLink, job -> job.status() == DownloadStatus.FAILED);
            DownloadSnapshot watchFailed = await(manager, watchPage, job -> job.status() == DownloadStatus.FAILED);

            assertEquals("This URL points to a video webpage, not a direct video file.", failed.errorMessage());
            assertEquals("This URL points to a video webpage, not a direct video file.", watchFailed.errorMessage());
            assertFalse(Files.exists(failed.destination()));
            assertFalse(Files.exists(failed.partialFile()));
        }
    }

    @Test
    void rejectsHtmlReturnedForMediaButAllowsAnExplicitHtmlFile() throws Exception {
        byte[] page = "<!doctype html><html><title>not a video</title></html>".getBytes(StandardCharsets.UTF_8);
        fixture("/media/not-really.mp4", page, "text/html; charset=UTF-8", null, false, 200, null);
        fixture("/documents/manual.html", page, "text/html; charset=UTF-8", null, false, 200, null);
        fixture("/documents/attached-html", page, "text/html; charset=UTF-8", "attachment; filename=manual.html", false, 200, null);
        fixture("/media/hidden-page.mp4", page, "application/octet-stream", null, false, 200, null);

        try (DownloadManager manager = newManager(temporaryDirectory.resolve("downloads"), 2)) {
            UUID mediaId = manager.add(url("/media/not-really.mp4")).get(3, TimeUnit.SECONDS);
            UUID htmlId = manager.add(url("/documents/manual.html")).get(3, TimeUnit.SECONDS);
            UUID attachedHtmlId = manager.add(url("/documents/attached-html")).get(3, TimeUnit.SECONDS);
            UUID hiddenPageId = manager.add(url("/media/hidden-page.mp4")).get(3, TimeUnit.SECONDS);
            DownloadSnapshot rejected = await(manager, mediaId, job -> job.status() == DownloadStatus.FAILED);
            DownloadSnapshot explicitHtml = await(manager, htmlId, job -> job.status() == DownloadStatus.COMPLETED);
            DownloadSnapshot attachedHtml = await(manager, attachedHtmlId, job -> job.status() == DownloadStatus.COMPLETED);
            DownloadSnapshot sniffed = await(manager, hiddenPageId, job -> job.status() == DownloadStatus.FAILED);

            assertTrue(rejected.errorMessage().contains("HTML instead of the expected media file"));
            assertTrue(sniffed.errorMessage().contains("HTML"));
            assertArrayEquals(page, Files.readAllBytes(explicitHtml.destination()));
            assertEquals("manual (1).html", attachedHtml.fileName());
            assertArrayEquals(page, Files.readAllBytes(attachedHtml.destination()));
            assertFalse(Files.exists(rejected.destination()));
            assertFalse(Files.exists(sniffed.destination()));
        }
    }

    @Test
    void rejectsRedirectsThatEndAtAnHtmlPage() throws Exception {
        byte[] page = "<html><body>landing page</body></html>".getBytes(StandardCharsets.UTF_8);
        fixture("/documents/landing.html", page, "text/html", null, false, 200, null);

        try (DownloadManager manager = newManager(temporaryDirectory.resolve("downloads"), 1)) {
            UUID id = manager.add(url("/redirect-to-html.mp4")).get(3, TimeUnit.SECONDS);
            DownloadSnapshot failed = await(manager, id, job -> job.status() == DownloadStatus.FAILED);

            assertTrue(failed.errorMessage().contains("HTML"));
            assertFalse(Files.exists(failed.destination()));
        }
    }

    @Test
    void usesAndSanitizesContentDispositionFilename() throws Exception {
        byte[] document = "%PDF-1.7\nfixture\n%%EOF".getBytes(StandardCharsets.US_ASCII);
        fixture("/download", document, "application/pdf", "attachment; filename*=UTF-8''r%C3%A9sum%C3%A9.pdf", false, 200, null);
        fixture("/download-two", document, "application/pdf", "attachment; filename*=UTF-8''r%C3%A9sum%C3%A9.pdf", false, 200, null);

        try (DownloadManager manager = newManager(temporaryDirectory.resolve("downloads"), 2)) {
            UUID id = manager.add(url("/download")).get(3, TimeUnit.SECONDS);
            UUID secondId = manager.add(url("/download-two")).get(3, TimeUnit.SECONDS);
            DownloadSnapshot completed = await(manager, id, job -> job.status() == DownloadStatus.COMPLETED);
            DownloadSnapshot secondCompleted = await(manager, secondId, job -> job.status() == DownloadStatus.COMPLETED);

            assertEquals(java.util.Set.of("résumé.pdf", "résumé (1).pdf"),
                    java.util.Set.of(completed.fileName(), secondCompleted.fileName()));
            assertNotEquals(completed.destination(), secondCompleted.destination());
            assertEquals(temporaryDirectory.resolve("downloads").toAbsolutePath().normalize(), completed.destination().getParent());
            assertArrayEquals(document, Files.readAllBytes(completed.destination()));
            assertArrayEquals(document, Files.readAllBytes(secondCompleted.destination()));
        }
    }

    @Test
    void reportsCloudflareChallengeSeparatelyFromHttpStatusFailure() throws Exception {
        fixture("/challenge", "challenge".getBytes(StandardCharsets.UTF_8), "text/html", null, false, 403, "challenge");
        fixture("/forbidden", "denied".getBytes(StandardCharsets.UTF_8), "text/plain", null, false, 403, null);

        try (DownloadManager manager = newManager(temporaryDirectory.resolve("downloads"), 1)) {
            UUID id = manager.add(url("/challenge")).get(3, TimeUnit.SECONDS);
            UUID forbiddenId = manager.add(url("/forbidden")).get(3, TimeUnit.SECONDS);
            DownloadSnapshot failed = await(manager, id, job -> job.status() == DownloadStatus.FAILED);
            DownloadSnapshot forbidden = await(manager, forbiddenId, job -> job.status() == DownloadStatus.FAILED);

            assertTrue(failed.errorMessage().contains("Cloudflare"));
            assertTrue(failed.errorMessage().contains("HTTP 403 Forbidden"));
            assertTrue(forbidden.errorMessage().contains("HTTP 403 Forbidden"));
            assertFalse(forbidden.errorMessage().contains("Cloudflare"));
        }
    }

    @Test
    void labelsTlsFailuresSeparatelyFromHttpFailures() {
        String message = HttpDownloadWorker.readableMessage(
                new IOException("connection setup failed", new SSLHandshakeException("certificate check failed")));

        assertTrue(message.startsWith("TLS connection failed:"));
        assertTrue(message.contains("certificate check failed"));
    }

    private DownloadManager newManager(Path downloads, int concurrency) throws IOException {
        return new DownloadManager(temporaryDirectory.resolve("metadata"), downloads, concurrency);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private void fixture(String path, byte[] bytes, String contentType, String contentDisposition,
                        boolean unknownLength, int status, String cloudflareChallenge) {
        fixtureResponses.put(path, new FixtureResponse(bytes, contentType, contentDisposition,
                unknownLength, status, cloudflareChallenge));
    }

    private DownloadSnapshot await(DownloadManager manager, UUID id, Predicate<DownloadSnapshot> condition) throws Exception {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        while (System.nanoTime() < deadline) {
            DownloadSnapshot snapshot = manager.snapshots().stream().filter(job -> job.id().equals(id)).findFirst().orElse(null);
            if (snapshot != null && condition.test(snapshot)) {
                return snapshot;
            }
            Thread.sleep(10);
        }
        DownloadSnapshot last = manager.snapshots().stream().filter(job -> job.id().equals(id)).findFirst().orElse(null);
        throw new AssertionError("Timed out waiting for download state; last state="
                + (last == null ? "missing" : last.status() + ": " + last.errorMessage())
                + ", requests=" + requestCounts + ", activeResponses=" + activeResponses.get());
    }

    private static void awaitCondition(java.util.function.BooleanSupplier condition, Duration timeout, String description) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Timed out waiting for " + description);
    }

    private byte[] content(String path) {
        return contentByPath.computeIfAbsent(path, key -> {
            byte[] bytes = new byte[1_500_000];
            int seed = key.hashCode();
            for (int index = 0; index < bytes.length; index++) {
                bytes[index] = (byte) (index * 31 + seed);
            }
            return bytes;
        });
    }

    private void respond(HttpExchange exchange) throws IOException {
        int active = activeResponses.incrementAndGet();
        peakResponses.accumulateAndGet(active, Math::max);
        String path = exchange.getRequestURI().getPath();
        int requestNumber = requestCounts.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
        try {
            if (path.equals("/redirect-loop")) {
                exchange.getResponseHeaders().set("Location", "/redirect-loop");
                exchange.sendResponseHeaders(302, -1);
                return;
            }
            if (path.equals("/redirect-to-html.mp4")) {
                exchange.getResponseHeaders().set("Location", "/documents/landing.html");
                exchange.sendResponseHeaders(302, -1);
                return;
            }
            if (path.startsWith("/redirect-chain/")) {
                int hop = Integer.parseInt(path.substring(path.lastIndexOf('/') + 1));
                if (hop < 6) {
                    exchange.getResponseHeaders().set("Location", "/redirect-chain/" + (hop + 1));
                    exchange.sendResponseHeaders(302, -1);
                    return;
                }
            }
            if (path.startsWith("/redirect/")) {
                exchange.getResponseHeaders().set("Location", path.substring("/redirect".length()));
                exchange.sendResponseHeaders(302, -1);
                return;
            }
            if (path.equals("/status/503")) {
                byte[] message = "Fixture service unavailable".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(503, message.length);
                write(exchange.getResponseBody(), message, 0, message.length, false);
                return;
            }

            FixtureResponse fixture = fixtureResponses.get(path);
            if (fixture != null) {
                if (fixture.contentType() != null) {
                    exchange.getResponseHeaders().set("Content-Type", fixture.contentType());
                }
                if (fixture.contentDisposition() != null) {
                    exchange.getResponseHeaders().set("Content-Disposition", fixture.contentDisposition());
                }
                if (fixture.cloudflareChallenge() != null) {
                    exchange.getResponseHeaders().set("cf-mitigated", fixture.cloudflareChallenge());
                    exchange.getResponseHeaders().set("Server", "cloudflare");
                }
                long responseLength = fixture.unknownLength() ? 0 : fixture.bytes().length;
                exchange.sendResponseHeaders(fixture.status(), responseLength);
                write(exchange.getResponseBody(), fixture.bytes(), 0, fixture.bytes().length, false);
                return;
            }

            byte[] content = content(path);
            String tag = "\"v1\"";
            String range = exchange.getRequestHeaders().getFirst("Range");
            if (range != null) {
                rangeHeaders.add(range);
                String ifRange = exchange.getRequestHeaders().getFirst("If-Range");
                if (ifRange != null) {
                    ifRangeHeaders.add(ifRange);
                }
            }
            boolean slow = path.startsWith("/slow/");
            exchange.getResponseHeaders().set("ETag", tag);
            exchange.getResponseHeaders().set("Last-Modified", "Wed, 21 Oct 2026 07:28:00 GMT");
            if (path.startsWith("/unknown/")) {
                exchange.sendResponseHeaders(200, 0);
                write(exchange.getResponseBody(), content, 0, content.length, false);
            } else if (range != null && rangeBehavior.get() == RangeBehavior.IGNORE) {
                exchange.sendResponseHeaders(200, content.length);
                write(exchange.getResponseBody(), content, 0, content.length, slow);
            } else if (range != null) {
                long requestedStart = Long.parseLong(range.substring("bytes=".length(), range.length() - 1));
                long actualStart = rangeBehavior.get() == RangeBehavior.WRONG_RANGE ? requestedStart + 1 : requestedStart;
                if (rangeBehavior.get() == RangeBehavior.CHANGED_ETAG) {
                    exchange.getResponseHeaders().set("ETag", "\"v2\"");
                } else if (rangeBehavior.get() == RangeBehavior.CHANGED_LAST_MODIFIED) {
                    exchange.getResponseHeaders().set("Last-Modified", "Thu, 22 Oct 2026 07:28:00 GMT");
                } else if (rangeBehavior.get() == RangeBehavior.MISSING_ETAG) {
                    exchange.getResponseHeaders().remove("ETag");
                }
                boolean unknownTotalOverrun = rangeBehavior.get() == RangeBehavior.UNKNOWN_TOTAL_OVERRUN;
                long advertisedEnd = unknownTotalOverrun ? content.length + 99L : content.length - 1L;
                String advertisedTotal = rangeBehavior.get() == RangeBehavior.CHANGED_TOTAL
                        ? Integer.toString(content.length + 1)
                        : unknownTotalOverrun ? "*" : Integer.toString(content.length);
                exchange.getResponseHeaders().set("Content-Range", "bytes " + actualStart + "-" + advertisedEnd + "/" + advertisedTotal);
                byte[] responseContent = unknownTotalOverrun ? Arrays.copyOf(content, content.length + 100) : content;
                int responseLength = responseContent.length - Math.toIntExact(actualStart);
                exchange.sendResponseHeaders(206, responseLength);
                write(exchange.getResponseBody(), responseContent, Math.toIntExact(actualStart), responseLength, slow);
            } else {
                exchange.sendResponseHeaders(200, content.length);
                write(exchange.getResponseBody(), content, 0, content.length, slow);
            }
        } catch (IOException ignored) {
            // A paused or cancelled client closes its connection while the test server is streaming.
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
            activeResponses.decrementAndGet();
        }
    }

    private static void write(OutputStream output, byte[] content, int start, int length, boolean slow) throws IOException, InterruptedException {
        try (output) {
            int end = start + length;
            for (int offset = start; offset < end; ) {
                int count = Math.min(16_384, end - offset);
                output.write(content, offset, count);
                output.flush();
                offset += count;
                if (slow) {
                    Thread.sleep(20);
                }
            }
        }
    }

    private enum RangeBehavior {
        NORMAL,
        IGNORE,
        WRONG_RANGE,
        CHANGED_ETAG,
        CHANGED_LAST_MODIFIED,
        MISSING_ETAG,
        UNKNOWN_TOTAL_OVERRUN,
        CHANGED_TOTAL
    }

    private record Payload(byte[] bytes, String contentType) {
    }

    private record FixtureResponse(byte[] bytes, String contentType, String contentDisposition,
                                   boolean unknownLength, int status, String cloudflareChallenge) {
    }

    private static final class InterruptedThenResumeServer implements AutoCloseable {
        private final byte[] content;
        private final ServerSocket serverSocket;
        private final Thread worker;
        private volatile boolean resumedRequestReceived;
        private volatile boolean closed;

        private InterruptedThenResumeServer(byte[] content) throws IOException {
            this.content = content;
            this.serverSocket = new ServerSocket();
            serverSocket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            worker = new Thread(this::serve, "bytebox-test-interruption-server");
            worker.setDaemon(true);
            worker.start();
        }

        private String url() {
            return "http://127.0.0.1:" + serverSocket.getLocalPort() + "/connection.bin";
        }

        private boolean resumedRequestReceived() {
            return resumedRequestReceived;
        }

        private void serve() {
            try (Socket first = serverSocket.accept()) {
                first.setSoTimeout(5_000);
                readHeaders(first);
                OutputStream output = first.getOutputStream();
                writeHeaders(output, "HTTP/1.1 200 OK", content.length, null);
                int initialBytes = Math.min(96 * 1024, content.length / 3);
                output.write(content, 0, initialBytes);
                output.flush();
            } catch (IOException exception) {
                if (!closed) {
                    return;
                }
            }

            try (Socket resumed = serverSocket.accept()) {
                resumed.setSoTimeout(5_000);
                List<String> headers = readHeaders(resumed);
                String range = headers.stream().filter(line -> line.toLowerCase(java.util.Locale.ROOT).startsWith("range:")).findFirst().orElse("");
                long start = parseRangeStart(range);
                resumedRequestReceived = start > 0;
                if (!resumedRequestReceived || start >= content.length) {
                    return;
                }
                OutputStream output = resumed.getOutputStream();
                long remaining = content.length - start;
                writeHeaders(output, "HTTP/1.1 206 Partial Content", remaining,
                        "Content-Range: bytes " + start + "-" + (content.length - 1) + "/" + content.length);
                output.write(content, Math.toIntExact(start), Math.toIntExact(remaining));
                output.flush();
            } catch (IOException exception) {
                if (!closed) {
                    throw new RuntimeException(exception);
                }
            }
        }

        private static List<String> readHeaders(Socket socket) throws IOException {
            List<String> headers = new ArrayList<>();
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                headers.add(line);
            }
            return headers;
        }

        private static long parseRangeStart(String header) {
            int begin = header.indexOf("bytes=");
            int dash = header.indexOf('-', begin + 6);
            return begin < 0 || dash < 0 ? -1 : Long.parseLong(header.substring(begin + 6, dash).trim());
        }

        private static void writeHeaders(OutputStream output, String status, long length, String extraHeader) throws IOException {
            StringBuilder response = new StringBuilder(status).append("\r\n")
                    .append("Content-Length: ").append(length).append("\r\n")
                    .append("ETag: \"v1\"\r\n")
                    .append("Last-Modified: Wed, 21 Oct 2026 07:28:00 GMT\r\n")
                    .append("Connection: close\r\n");
            if (extraHeader != null) {
                response.append(extraHeader).append("\r\n");
            }
            response.append("\r\n");
            output.write(response.toString().getBytes(StandardCharsets.US_ASCII));
            output.flush();
        }

        @Override
        public void close() throws Exception {
            closed = true;
            serverSocket.close();
            worker.join(1_000);
        }
    }
}
