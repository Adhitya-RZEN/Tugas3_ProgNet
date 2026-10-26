package com.bytebox.ui;

import com.bytebox.download.DownloadSnapshot;
import com.bytebox.download.DownloadStatus;

import java.util.List;

record DownloadSummary(ProgressState state, double progress, long remainingBytes, double bytesPerSecond) {
    enum ProgressState {
        IDLE,
        DETERMINATE,
        INDETERMINATE,
        UNKNOWN
    }

    static DownloadSummary calculate(List<DownloadSnapshot> jobs) {
        long totalBytes = 0;
        long transferredBytes = 0;
        double bytesPerSecond = 0;
        boolean hasIncludedJobs = false;
        boolean unknownPendingSize = false;
        boolean unknownActiveSize = false;
        boolean overflow = false;

        for (DownloadSnapshot job : jobs) {
            DownloadStatus status = job.status();
            if (status == DownloadStatus.CANCELLED || status == DownloadStatus.FAILED) {
                continue;
            }
            hasIncludedJobs = true;
            boolean active = status == DownloadStatus.DOWNLOADING || status == DownloadStatus.PAUSING;
            if (active) {
                bytesPerSecond += Math.max(0, job.bytesPerSecond());
            }

            Long jobTotal = job.totalBytes();
            if (jobTotal == null && status == DownloadStatus.COMPLETED) {
                jobTotal = Math.max(0, job.transferredBytes());
            }
            if (jobTotal == null || jobTotal < 0) {
                if (active) {
                    unknownActiveSize = true;
                } else {
                    unknownPendingSize = true;
                }
                continue;
            }
            try {
                totalBytes = Math.addExact(totalBytes, jobTotal);
                transferredBytes = Math.addExact(transferredBytes,
                        Math.min(Math.max(0, job.transferredBytes()), jobTotal));
            } catch (ArithmeticException exception) {
                overflow = true;
            }
        }

        if (unknownActiveSize) {
            return new DownloadSummary(ProgressState.INDETERMINATE, 0, -1, bytesPerSecond);
        }
        if (unknownPendingSize || overflow) {
            return new DownloadSummary(ProgressState.UNKNOWN, 0, -1, bytesPerSecond);
        }
        if (!hasIncludedJobs) {
            return new DownloadSummary(ProgressState.IDLE, 0, -1, bytesPerSecond);
        }

        double progress = totalBytes == 0 ? 1 : (double) transferredBytes / totalBytes;
        return new DownloadSummary(ProgressState.DETERMINATE, Math.max(0, Math.min(1, progress)),
                Math.max(0, totalBytes - transferredBytes), bytesPerSecond);
    }

    boolean indeterminate() {
        return state == ProgressState.INDETERMINATE;
    }

    double indicatorProgress() {
        return indeterminate() ? -1 : progress;
    }

    String percentageLabel() {
        return state == ProgressState.UNKNOWN || state == ProgressState.INDETERMINATE
                ? "—" : DownloadFormatting.percent(progress);
    }
}
