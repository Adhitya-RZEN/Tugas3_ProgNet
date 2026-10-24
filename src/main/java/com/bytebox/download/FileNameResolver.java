package com.bytebox.download;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class FileNameResolver {
    private static final Pattern INVALID_FILENAME_CHARACTERS = Pattern.compile("[<>:\"/\\\\|?*\\p{Cntrl}]");
    private static final Pattern WINDOWS_DEVICE_NAME = Pattern.compile("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?$");
    private static final int MAX_FILENAME_LENGTH = 160;

    private FileNameResolver() {
    }

    public static String fileNameFrom(URI source) {
        String path = source.getPath();
        String name = path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
        if (name.isBlank() || name.equals(".") || name.equals("..")) {
            String host = source.getHost() == null ? "file" : source.getHost().toLowerCase(Locale.ROOT);
            name = "download-" + host;
        }

        name = INVALID_FILENAME_CHARACTERS.matcher(name).replaceAll("_").trim();
        while (name.endsWith(".") || name.endsWith(" ")) {
            name = name.substring(0, name.length() - 1);
        }
        if (name.isBlank()) {
            name = "download.bin";
        }
        if (WINDOWS_DEVICE_NAME.matcher(name).matches()) {
            name = "_" + name;
        }
        if (name.length() > MAX_FILENAME_LENGTH) {
            int dot = name.lastIndexOf('.');
            String extension = dot > 0 && name.length() - dot <= 20 ? name.substring(dot) : "";
            name = name.substring(0, MAX_FILENAME_LENGTH - extension.length()) + extension;
        }
        return name;
    }

    public static Path allocate(URI source, Path directory, Collection<Path> reservedPaths) {
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        String safeName = fileNameFrom(source);
        Set<Path> reserved = new HashSet<>();
        for (Path path : reservedPaths) {
            reserved.add(path.toAbsolutePath().normalize());
        }

        for (int index = 0; index < 10_000; index++) {
            String candidate = index == 0 ? safeName : withNumber(safeName, index);
            Path destination = normalizedDirectory.resolve(candidate).normalize();
            if (!normalizedDirectory.equals(destination.getParent())) {
                throw new IllegalArgumentException("Resolved file name escaped the download directory");
            }
            if (!reserved.contains(destination) && !Files.exists(destination)) {
                return destination;
            }
        }
        throw new IllegalStateException("Could not find an unused file name after 10,000 attempts");
    }

    private static String withNumber(String fileName, int index) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) {
            return fileName + " (" + index + ")";
        }
        return fileName.substring(0, dot) + " (" + index + ")" + fileName.substring(dot);
    }
}
