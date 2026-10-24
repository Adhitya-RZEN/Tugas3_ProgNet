package com.bytebox.ui;

import com.bytebox.download.DownloadSnapshot;
import com.bytebox.download.DownloadStatus;

import java.util.List;

record DownloadSummary(double progress, long remainingBytes, double bytesPerSecond) {
    static DownloadSummary calculate(List<DownloadSnapshot> jobs) {
        long totalBytes = 0;
        long transferredBytes = 0;
        double bytesPerSecond = 0;
        boolean unknownTotal = false;
        boolean hasJobs = false;

        for (DownloadSnapshot job : jobs) {
            if (job.status() == DownloadStatus.CANCELLED) {
                continue;
            }
            hasJobs = true;
            if (job.status() == DownloadStatus.DOWNLOADING || job.status() == DownloadStatus.PAUSING) {
                bytesPerSecond += Math.max(0, job.bytesPerSecond());
            }

            Long jobTotal = job.totalBytes();
            if (jobTotal == null && job.status() == DownloadStatus.COMPLETED) {
                jobTotal = Math.max(0, job.transferredBytes());
            }
            if (jobTotal == null || jobTotal < 0) {
                unknownTotal = true;
                continue;
            }
            try {
                totalBytes = Math.addExact(totalBytes, jobTotal);
                transferredBytes = Math.addExact(transferredBytes,
                        Math.min(Math.max(0, job.transferredBytes()), jobTotal));
            } catch (ArithmeticException exception) {
                unknownTotal = true;
            }
        }

        if (unknownTotal) {
            return new DownloadSummary(-1, -1, bytesPerSecond);
        }
        double progress = totalBytes == 0 ? (hasJobs ? 1 : 0) : (double) transferredBytes / totalBytes;
        return new DownloadSummary(progress, Math.max(0, totalBytes - transferredBytes), bytesPerSecond);
    }
}
