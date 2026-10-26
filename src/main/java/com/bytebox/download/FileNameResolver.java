package com.bytebox.download;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.Charset;
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

        return sanitize(name, "download.bin");
    }

    static String fileNameFromContentDisposition(String contentDisposition) {
        if (contentDisposition == null || contentDisposition.isBlank()) {
            return null;
        }
        String regularName = null;
        for (String parameter : splitParameters(contentDisposition)) {
            int equals = parameter.indexOf('=');
            if (equals < 0) {
                continue;
            }
            String key = parameter.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            String value = unquote(parameter.substring(equals + 1).trim());
            if (key.equals("filename*") && !value.isBlank()) {
                String decoded = decodeExtendedFilename(value);
                if (decoded != null && !decoded.isBlank()) {
                    return sanitize(decoded, null);
                }
            } else if (key.equals("filename") && regularName == null && !value.isBlank()) {
                regularName = value;
            }
        }
        return regularName == null ? null : sanitize(regularName, null);
    }

    static Path allocate(String fileName, Path directory, Collection<Path> reservedPaths) {
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        String safeName = sanitize(fileName, "download.bin");
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

    private static String sanitize(String name, String fallback) {
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..")) {
            return fallback;
        }
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = name.substring(slash + 1);
        name = INVALID_FILENAME_CHARACTERS.matcher(name).replaceAll("_").trim();
        while (name.endsWith(".") || name.endsWith(" ")) {
            name = name.substring(0, name.length() - 1);
        }
        if (name.isBlank()) {
            return fallback;
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

    private static java.util.List<String> splitParameters(String header) {
        java.util.List<String> parameters = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < header.length(); index++) {
            char character = header.charAt(index);
            if (escaped) {
                current.append(character);
                escaped = false;
            } else if (character == '\\' && quoted) {
                current.append(character);
                escaped = true;
            } else if (character == '"') {
                current.append(character);
                quoted = !quoted;
            } else if (character == ';' && !quoted) {
                parameters.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        parameters.add(current.toString().trim());
        return parameters;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            value = value.substring(1, value.length() - 1);
        }
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String decodeExtendedFilename(String value) {
        int charsetSeparator = value.indexOf('\'');
        int languageSeparator = charsetSeparator < 0 ? -1 : value.indexOf('\'', charsetSeparator + 1);
        if (charsetSeparator <= 0 || languageSeparator < 0) {
            return null;
        }
        try {
            Charset charset = Charset.forName(value.substring(0, charsetSeparator));
            String encoded = value.substring(languageSeparator + 1).replace("+", "%2B");
            return URLDecoder.decode(encoded, charset);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public static Path allocate(URI source, Path directory, Collection<Path> reservedPaths) {
        return allocate(fileNameFrom(source), directory, reservedPaths);
    }

    private static String withNumber(String fileName, int index) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) {
            return fileName + " (" + index + ")";
        }
        return fileName.substring(0, dot) + " (" + index + ")" + fileName.substring(dot);
    }
}
