package com.bytebox.download;

public enum DownloadStatus {
    QUEUED,
    DOWNLOADING,
    PAUSING,
    PAUSED,
    CANCELLING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED;
    }

    public boolean canTransitionTo(DownloadStatus next) {
        return switch (this) {
            case QUEUED -> next == DOWNLOADING || next == PAUSED || next == CANCELLING || next == CANCELLED || next == FAILED;
            case DOWNLOADING -> next == PAUSING || next == CANCELLING || next == COMPLETED || next == FAILED;
            case PAUSING -> next == PAUSED || next == CANCELLING || next == COMPLETED || next == FAILED;
            case PAUSED -> next == QUEUED || next == CANCELLING || next == CANCELLED;
            case CANCELLING -> next == CANCELLED || next == COMPLETED || next == FAILED;
            case FAILED -> next == QUEUED || next == CANCELLING || next == CANCELLED;
            case COMPLETED, CANCELLED -> false;
        };
    }
}
