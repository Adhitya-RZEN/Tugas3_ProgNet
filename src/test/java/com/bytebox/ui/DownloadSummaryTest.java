package com.bytebox.ui;

import com.bytebox.download.DownloadSnapshot;
import com.bytebox.download.DownloadStatus;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DownloadSummaryTest {
    @Test
    void calculatesByteWeightedProgressAndRemainingBytesAcrossTheQueue() {
        DownloadSummary summary = DownloadSummary.calculate(List.of(
                snapshot(50, 100L, 2_000, DownloadStatus.DOWNLOADING),
                snapshot(25, 100L, 0, DownloadStatus.PAUSED),
                snapshot(0, 100L, 0, DownloadStatus.QUEUED),
                snapshot(100, 100L, 0, DownloadStatus.COMPLETED),
                snapshot(100, 100L, 0, DownloadStatus.CANCELLED)));

        assertEquals(0.4375, summary.progress(), 0.000001);
        assertEquals(225, summary.remainingBytes());
        assertEquals(2_000, summary.bytesPerSecond(), 0.000001);
    }

    @Test
    void reportsUnknownAggregateSizeAsIndeterminate() {
        DownloadSummary summary = DownloadSummary.calculate(List.of(
                snapshot(10, null, 1_280, DownloadStatus.DOWNLOADING),
                snapshot(100, 100L, 0, DownloadStatus.CANCELLED)));

        assertEquals(-1, summary.progress());
        assertEquals(-1, summary.remainingBytes());
        assertEquals(1_280, summary.bytesPerSecond(), 0.000001);
    }

    private static DownloadSnapshot snapshot(long transferred, Long total, double speed, DownloadStatus status) {
        UUID id = UUID.randomUUID();
        Path destination = Path.of("download-" + id + ".bin");
        return new DownloadSnapshot(id, URI.create("https://example.test/" + id), destination.getFileName().toString(),
                destination, destination.resolveSibling("." + id + ".part"), transferred, total, speed,
                Instant.EPOCH, status, null, false);
    }
}
