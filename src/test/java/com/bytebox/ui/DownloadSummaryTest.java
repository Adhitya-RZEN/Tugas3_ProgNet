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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        assertEquals(DownloadSummary.ProgressState.INDETERMINATE, summary.state());
        assertEquals(0, summary.progress());
        assertEquals(-1, summary.indicatorProgress());
        assertEquals("—", summary.percentageLabel());
        assertEquals(-1, summary.remainingBytes());
        assertEquals(1_280, summary.bytesPerSecond(), 0.000001);
    }

    @Test
    void idleHistoryDoesNotAnimateAndDoesNotInventProgress() {
        DownloadSummary empty = DownloadSummary.calculate(List.of());
        DownloadSummary failed = DownloadSummary.calculate(List.of(
                snapshot(12, null, 0, DownloadStatus.FAILED),
                snapshot(0, null, 0, DownloadStatus.CANCELLED)));

        assertEquals(DownloadSummary.ProgressState.IDLE, empty.state());
        assertEquals(0, empty.indicatorProgress());
        assertEquals("0%", empty.percentageLabel());
        assertEquals(DownloadSummary.ProgressState.IDLE, failed.state());
        assertEquals(0, failed.indicatorProgress());
        assertEquals(0, failed.progress());
        assertEquals(-1, failed.remainingBytes());
    }

    @Test
    void completedHistoryShowsFinalProgressWithoutAnimation() {
        DownloadSummary summary = DownloadSummary.calculate(List.of(
                snapshot(45, 45L, 0, DownloadStatus.COMPLETED)));

        assertEquals(DownloadSummary.ProgressState.DETERMINATE, summary.state());
        assertEquals(1, summary.progress());
        assertEquals(1, summary.indicatorProgress());
        assertEquals("100%", summary.percentageLabel());
    }

    @Test
    void failedHistoryDoesNotKeepMixedCompletedHistorySpinning() {
        DownloadSummary summary = DownloadSummary.calculate(List.of(
                snapshot(30, 30L, 0, DownloadStatus.COMPLETED),
                snapshot(18, null, 0, DownloadStatus.FAILED)));

        assertEquals(DownloadSummary.ProgressState.DETERMINATE, summary.state());
        assertEquals(1, summary.progress());
        assertEquals(1, summary.indicatorProgress());
    }

    @Test
    void pausedUnknownSizeShowsUnknownWithoutAnimation() {
        DownloadSummary summary = DownloadSummary.calculate(List.of(
                snapshot(10, null, 0, DownloadStatus.PAUSED)));

        assertEquals(DownloadSummary.ProgressState.UNKNOWN, summary.state());
        assertEquals(0, summary.progress());
        assertEquals(0, summary.indicatorProgress());
        assertEquals(-1, summary.remainingBytes());
    }

    @Test
    void queuedUnknownSizeMakesMixedAggregateUnknownRatherThanInventingPercent() {
        DownloadSummary summary = DownloadSummary.calculate(List.of(
                snapshot(50, 100L, 256, DownloadStatus.DOWNLOADING),
                snapshot(0, null, 0, DownloadStatus.QUEUED)));

        assertEquals(DownloadSummary.ProgressState.UNKNOWN, summary.state());
        assertEquals(0, summary.indicatorProgress());
        assertEquals("—", summary.percentageLabel());
        assertEquals(-1, summary.remainingBytes());
        assertEquals(256, summary.bytesPerSecond(), 0.000001);
    }

    @Test
    void knownActiveDownloadsRemainByteWeighted() {
        DownloadSummary summary = DownloadSummary.calculate(List.of(
                snapshot(25, 100L, 1_000, DownloadStatus.DOWNLOADING),
                snapshot(50, 100L, 500, DownloadStatus.PAUSING)));

        assertEquals(DownloadSummary.ProgressState.DETERMINATE, summary.state());
        assertEquals(0.375, summary.progress(), 0.000001);
        assertEquals(0.375, summary.indicatorProgress(), 0.000001);
        assertEquals(1_500, summary.bytesPerSecond(), 0.000001);
    }

    @Test
    void unknownActiveSizeIsIndeterminateOnlyWhileTransferIsActive() {
        DownloadSummary active = DownloadSummary.calculate(List.of(
                snapshot(10, null, 256, DownloadStatus.DOWNLOADING)));
        DownloadSummary failed = DownloadSummary.calculate(List.of(
                snapshot(10, null, 0, DownloadStatus.FAILED)));

        assertEquals(DownloadSummary.ProgressState.INDETERMINATE, active.state());
        assertEquals(-1, active.indicatorProgress());
        assertEquals(DownloadSummary.ProgressState.IDLE, failed.state());
        assertEquals(0, failed.indicatorProgress());
    }

    @Test
    void cancelledAndRestartedPausedHistoryDoesNotAnimate() {
        DownloadSummary cancelled = DownloadSummary.calculate(List.of(
                snapshot(0, 200L, 0, DownloadStatus.CANCELLED)));
        DownloadSnapshot pausedSnapshot = snapshot(38, null, 0, DownloadStatus.PAUSED);
        DownloadSummary recoveredPaused = DownloadSummary.calculate(List.of(pausedSnapshot));

        assertEquals(DownloadSummary.ProgressState.IDLE, cancelled.state());
        assertEquals(0, cancelled.indicatorProgress());
        assertEquals(DownloadSummary.ProgressState.UNKNOWN, recoveredPaused.state());
        assertEquals(0, recoveredPaused.indicatorProgress());
        assertFalse(recoveredPaused.indeterminate());
        assertTrue(pausedSnapshot.sizeUnknown());
        assertEquals(0, pausedSnapshot.progress());
    }

    @Test
    void failedTransitionFromUnknownActiveStopsIndeterminateState() {
        DownloadSummary active = DownloadSummary.calculate(List.of(
                snapshot(2, null, 32, DownloadStatus.DOWNLOADING)));
        DownloadSummary terminal = DownloadSummary.calculate(List.of(
                snapshot(2, null, 0, DownloadStatus.FAILED)));

        assertTrue(active.indeterminate());
        assertFalse(terminal.indeterminate());
        assertEquals(DownloadSummary.ProgressState.IDLE, terminal.state());
    }

    private static DownloadSnapshot snapshot(long transferred, Long total, double speed, DownloadStatus status) {
        UUID id = UUID.randomUUID();
        Path destination = Path.of("download-" + id + ".bin");
        return new DownloadSnapshot(id, URI.create("https://example.test/" + id), destination.getFileName().toString(),
                destination, destination.resolveSibling("." + id + ".part"), transferred, total, speed,
                Instant.EPOCH, status, null, false);
    }
}
