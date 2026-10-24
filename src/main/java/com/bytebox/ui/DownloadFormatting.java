package com.bytebox.ui;

final class DownloadFormatting {
    private DownloadFormatting() {
    }

    static String bytes(long bytes) {
        if (bytes < 0) {
            return "—";
        }
        if (bytes < 1_000) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1_000.0;
            unit++;
        } while (value >= 1_000 && unit < units.length - 1);
        return String.format(java.util.Locale.ROOT, value >= 100 ? "%.0f %s" : "%.1f %s", value, units[unit]);
    }

    static String speed(double bytesPerSecond) {
        return bytesPerSecond <= 0 ? "—" : bytes(bytesPerSecond < 1 ? 1 : (long) bytesPerSecond) + "/s";
    }

    static String eta(long remainingBytes, double bytesPerSecond) {
        if (remainingBytes <= 0 || bytesPerSecond <= 0) {
            return "ETA —";
        }
        long seconds = (long) Math.ceil(remainingBytes / bytesPerSecond);
        if (seconds < 60) {
            return "ETA " + seconds + "s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return "ETA " + minutes + "m " + seconds % 60 + "s";
        }
        return "ETA " + minutes / 60 + "h " + minutes % 60 + "m";
    }

    static String percent(double progress) {
        return progress < 0 ? "—" : Math.round(progress * 100) + "%";
    }
}
